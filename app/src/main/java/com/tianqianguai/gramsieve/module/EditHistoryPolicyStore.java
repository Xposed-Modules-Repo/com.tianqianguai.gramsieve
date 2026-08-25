package com.tianqianguai.gramsieve.module;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.tianqianguai.gramsieve.config.AntiRecallConfigStore;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stores edit-history capture policy in the existing GramSieve message database. */
public final class EditHistoryPolicyStore implements AutoCloseable {
    public enum Mode {
        BLACKLIST,
        WHITELIST
    }

    private static final String SETTINGS_TABLE = "edit_history_settings";
    private static final String RULES_TABLE = "edit_history_rules";
    private static final int MAX_CACHED_RULES = 256;

    private final SQLiteDatabase database;
    private final AntiRecallConfigStore compatibilityConfig;
    private final Map<Integer, AccountPolicy> policies = new LinkedHashMap<>();

    public EditHistoryPolicyStore(Context context) {
        this(context, context == null ? null : new AntiRecallConfigStore(context));
    }

    EditHistoryPolicyStore(Context context, AntiRecallConfigStore compatibilityConfig) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        Context appContext = context.getApplicationContext();
        Context databaseContext = appContext == null ? context : appContext;
        database = databaseContext.openOrCreateDatabase(
                MessageDatabaseHelper.DATABASE_NAME, Context.MODE_PRIVATE, null);
        this.compatibilityConfig = compatibilityConfig;
        createTables();
    }

    public synchronized boolean isEnabled(int accountId) {
        return policy(accountId).enabled;
    }

    public synchronized void setEnabled(int accountId, boolean enabled) {
        AccountPolicy policy = policy(accountId);
        policy.enabled = enabled;
        ContentValues values = new ContentValues();
        values.put("enabled", enabled ? 1 : 0);
        database.update(SETTINGS_TABLE, values, "account_id = ?",
                new String[]{String.valueOf(normalizeAccount(accountId))});
    }

    public synchronized Mode getMode(int accountId) {
        return policy(accountId).mode;
    }

    public synchronized void setMode(int accountId, Mode mode) {
        AccountPolicy policy = policy(accountId);
        policy.mode = mode == null ? Mode.BLACKLIST : mode;
        policy.ruleCache.clear();
        ContentValues values = new ContentValues();
        values.put("mode", policy.mode.name());
        database.update(SETTINGS_TABLE, values, "account_id = ?",
                new String[]{String.valueOf(normalizeAccount(accountId))});
    }

    public synchronized boolean shouldRecord(int accountId, long dialogId) {
        if (dialogId == 0L) {
            return false;
        }
        AccountPolicy policy = policy(accountId);
        Boolean explicit = policy.ruleCache.get(dialogId);
        if (explicit == null && !policy.ruleCache.containsKey(dialogId)) {
            explicit = queryRule(accountId, dialogId);
            policy.ruleCache.put(dialogId, explicit);
        }
        return evaluate(policy.enabled, policy.mode, explicit);
    }

    static boolean evaluate(boolean enabled, Mode mode, Boolean explicit) {
        if (!enabled) {
            return false;
        }
        if (explicit != null) {
            return explicit;
        }
        return mode == Mode.BLACKLIST;
    }

    public synchronized void setDialogRecorded(int accountId, long dialogId, boolean record) {
        if (dialogId == 0L) {
            return;
        }
        int normalizedAccount = normalizeAccount(accountId);
        ContentValues values = new ContentValues();
        values.put("account_id", normalizedAccount);
        values.put("dialog_id", dialogId);
        values.put("record", record ? 1 : 0);
        values.put("updated_at", System.currentTimeMillis());
        database.insertWithOnConflict(RULES_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        AccountPolicy policy = policy(normalizedAccount);
        policy.ruleCache.put(dialogId, record);
    }

    public synchronized Boolean getDialogRule(int accountId, long dialogId) {
        if (dialogId == 0L) {
            return null;
        }
        AccountPolicy policy = policy(accountId);
        if (!policy.ruleCache.containsKey(dialogId)) {
            policy.ruleCache.put(dialogId, queryRule(accountId, dialogId));
        }
        return policy.ruleCache.get(dialogId);
    }

    public synchronized void clearDialogRule(int accountId, long dialogId) {
        if (dialogId == 0L) {
            return;
        }
        int normalizedAccount = normalizeAccount(accountId);
        database.delete(RULES_TABLE, "account_id = ? AND dialog_id = ?",
                new String[]{String.valueOf(normalizedAccount), String.valueOf(dialogId)});
        policy(normalizedAccount).ruleCache.remove(dialogId);
    }

    public synchronized boolean isInitialized(int accountId) {
        return policy(accountId).initialized;
    }

    @Override
    public synchronized void close() {
        if (database.isOpen()) {
            database.close();
        }
    }

    private AccountPolicy policy(int accountId) {
        int normalizedAccount = normalizeAccount(accountId);
        AccountPolicy existing = policies.get(normalizedAccount);
        if (existing != null) {
            return existing;
        }

        ensureSettingsRow(normalizedAccount);
        AccountPolicy loaded = loadPolicy(normalizedAccount);
        if (!loaded.initialized) {
            policies.put(normalizedAccount, loaded);
            initializeAccount(normalizedAccount, loaded);
        }
        policies.put(normalizedAccount, loaded);
        return loaded;
    }

    private void ensureSettingsRow(int accountId) {
        ContentValues values = new ContentValues();
        values.put("account_id", accountId);
        values.put("enabled", 0);
        values.put("mode", Mode.BLACKLIST.name());
        values.put("initialized", 0);
        database.insertWithOnConflict(SETTINGS_TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private AccountPolicy loadPolicy(int accountId) {
        Cursor cursor = database.query(SETTINGS_TABLE,
                new String[]{"enabled", "mode", "initialized"}, "account_id = ?",
                new String[]{String.valueOf(accountId)}, null, null, null);
        try {
            if (!cursor.moveToFirst()) {
                return new AccountPolicy(false, Mode.BLACKLIST, false);
            }
            String mode = cursor.getString(1);
            Mode parsedMode;
            try {
                parsedMode = Mode.valueOf(mode);
            } catch (IllegalArgumentException | NullPointerException ignored) {
                parsedMode = Mode.BLACKLIST;
            }
            return new AccountPolicy(cursor.getInt(0) != 0, parsedMode, cursor.getInt(2) != 0);
        } finally {
            cursor.close();
        }
    }

    private void initializeAccount(int accountId, AccountPolicy policy) {
        if (accountId == 0 && compatibilityConfig != null) {
            java.util.Set<Long> enabledChatIds = compatibilityConfig.getEnabledChatIds();
            if (!enabledChatIds.isEmpty()) {
                policy.enabled = true;
                policy.mode = Mode.WHITELIST;
                ContentValues migrated = new ContentValues();
                migrated.put("enabled", 1);
                migrated.put("mode", Mode.WHITELIST.name());
                database.update(SETTINGS_TABLE, migrated, "account_id = ?",
                        new String[]{String.valueOf(accountId)});
            }
            for (Long dialogId : enabledChatIds) {
                if (dialogId != null && dialogId != 0L) {
                    setDialogRecorded(accountId, dialogId, true);
                }
            }
        }
        ContentValues values = new ContentValues();
        values.put("initialized", 1);
        database.update(SETTINGS_TABLE, values, "account_id = ?",
                new String[]{String.valueOf(accountId)});
        policy.initialized = true;
    }

    private Boolean queryRule(int accountId, long dialogId) {
        Cursor cursor = database.query(RULES_TABLE, new String[]{"record"},
                "account_id = ? AND dialog_id = ?",
                new String[]{String.valueOf(normalizeAccount(accountId)), String.valueOf(dialogId)},
                null, null, null);
        try {
            return cursor.moveToFirst() ? cursor.getInt(0) != 0 : null;
        } finally {
            cursor.close();
        }
    }

    private void createTables() {
        database.execSQL("CREATE TABLE IF NOT EXISTS " + SETTINGS_TABLE + " ("
                + "account_id INTEGER PRIMARY KEY, enabled INTEGER NOT NULL, "
                + "mode TEXT NOT NULL, initialized INTEGER NOT NULL)");
        database.execSQL("CREATE TABLE IF NOT EXISTS " + RULES_TABLE + " ("
                + "account_id INTEGER NOT NULL, dialog_id INTEGER NOT NULL, record INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL, PRIMARY KEY(account_id, dialog_id))");
    }

    private static int normalizeAccount(int accountId) {
        return Math.max(0, accountId);
    }

    private static final class AccountPolicy {
        boolean enabled;
        Mode mode;
        boolean initialized;
        final LinkedHashMap<Long, Boolean> ruleCache = new LinkedHashMap<Long, Boolean>(
                MAX_CACHED_RULES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                return size() > MAX_CACHED_RULES;
            }
        };

        AccountPolicy(boolean enabled, Mode mode, boolean initialized) {
            this.enabled = enabled;
            this.mode = mode;
            this.initialized = initialized;
        }
    }
}
