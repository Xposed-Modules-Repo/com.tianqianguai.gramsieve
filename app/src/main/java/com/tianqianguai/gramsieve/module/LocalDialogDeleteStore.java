package com.tianqianguai.gramsieve.module;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class LocalDialogDeleteStore {
    private static final String PREFS_NAME = "gramsieve_local_dialog_deletes";
    private static final String KEY_PREFIX_V2 = "hidden_dialog_v2_";
    private static final String LEGACY_KEY_PREFIX = "hidden_dialog_";
    private static final String LEGACY_ACCOUNT_PREFIX = "hidden_dialog_account_";

    private final SharedPreferences prefs;

    LocalDialogDeleteStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    void hide(long dialogId) {
        hide(dialogId, 0);
    }

    void hide(long dialogId, int account) {
        if (dialogId == 0L) {
            return;
        }
        prefs.edit()
                .putBoolean(preferenceKey(Math.max(0, account), dialogId), true)
                .apply();
    }

    boolean isHidden(long dialogId, int account) {
        return dialogId != 0L
                && prefs.getBoolean(preferenceKey(Math.max(0, account), dialogId), false);
    }

    Set<HiddenDialog> hiddenDialogs(int fallbackAccount) {
        Map<String, ?> entries = prefs.getAll();
        Set<HiddenDialog> hiddenDialogs = new HashSet<>();
        for (Map.Entry<String, ?> entry : entries.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(KEY_PREFIX_V2) || !(entry.getValue() instanceof Boolean)
                    || !((Boolean) entry.getValue())) {
                continue;
            }
            HiddenDialog hiddenDialog = parseV2Key(key);
            if (hiddenDialog != null) {
                hiddenDialogs.add(hiddenDialog);
            }
        }

        SharedPreferences.Editor migration = null;
        for (Map.Entry<String, ?> entry : entries.entrySet()) {
            String key = entry.getKey();
            if (!isLegacyHiddenKey(key) || !(entry.getValue() instanceof Boolean)
                    || !((Boolean) entry.getValue())) {
                continue;
            }
            long dialogId;
            try {
                dialogId = Long.parseLong(key.substring(LEGACY_KEY_PREFIX.length()));
            } catch (NumberFormatException ignored) {
                continue;
            }
            Object storedAccount = entries.get(LEGACY_ACCOUNT_PREFIX + dialogId);
            int account = storedAccount instanceof Integer
                    ? Math.max(0, (Integer) storedAccount)
                    : Math.max(0, fallbackAccount);
            HiddenDialog hiddenDialog = new HiddenDialog(account, dialogId);
            hiddenDialogs.add(hiddenDialog);
            if (migration == null) {
                migration = prefs.edit();
            }
            migration.putBoolean(preferenceKey(account, dialogId), true);
            migration.remove(key);
            migration.remove(LEGACY_ACCOUNT_PREFIX + dialogId);
        }
        if (migration != null) {
            migration.apply();
        }
        return hiddenDialogs;
    }

    static String preferenceKey(int account, long dialogId) {
        return KEY_PREFIX_V2 + Math.max(0, account) + "_" + dialogId;
    }

    private static boolean isLegacyHiddenKey(String key) {
        return key.startsWith(LEGACY_KEY_PREFIX)
                && !key.startsWith(KEY_PREFIX_V2)
                && !key.startsWith(LEGACY_ACCOUNT_PREFIX);
    }

    private static HiddenDialog parseV2Key(String key) {
        String value = key.substring(KEY_PREFIX_V2.length());
        int separator = value.indexOf('_');
        if (separator <= 0 || separator >= value.length() - 1) {
            return null;
        }
        try {
            int account = Math.max(0, Integer.parseInt(value.substring(0, separator)));
            long dialogId = Long.parseLong(value.substring(separator + 1));
            return dialogId == 0L ? null : new HiddenDialog(account, dialogId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static final class HiddenDialog {
        final int account;
        final long dialogId;

        HiddenDialog(int account, long dialogId) {
            this.account = Math.max(0, account);
            this.dialogId = dialogId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof HiddenDialog)) {
                return false;
            }
            HiddenDialog that = (HiddenDialog) other;
            return account == that.account && dialogId == that.dialogId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(account, dialogId);
        }
    }
}
