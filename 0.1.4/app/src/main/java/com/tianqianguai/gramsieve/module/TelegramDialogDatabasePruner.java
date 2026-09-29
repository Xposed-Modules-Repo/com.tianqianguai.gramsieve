package com.tianqianguai.gramsieve.module;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

final class TelegramDialogDatabasePruner {
    interface Logger {
        void info(String message);

        void error(String message, Throwable throwable);
    }

    private static final long RECENT_PRUNE_WINDOW_MS = 15_000L;
    private static final long FINAL_DELETE_SETTLE_MS = 1_500L;

    private final Context context;
    private final Logger logger;
    private final Map<String, Long> recentPrunes = new LinkedHashMap<String, Long>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 64;
        }
    };

    TelegramDialogDatabasePruner(Context context, Logger logger) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.logger = logger;
    }

    void pruneAsync(long dialogId, int account, String source) {
        if (dialogId == 0L || shouldSkipRecent(dialogId, account)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(FINAL_DELETE_SETTLE_MS);
                prune(dialogId, account, source);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                logger.error("DialogDatabasePrune: interrupted dialogId=" + dialogId
                        + " account=" + account + " source=" + source, interrupted);
            } catch (Throwable throwable) {
                logger.error("DialogDatabasePrune: failed dialogId=" + dialogId
                        + " account=" + account + " source=" + source, throwable);
            }
        }, "GramSieve-DialogPrune");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean shouldSkipRecent(long dialogId, int account) {
        long now = System.currentTimeMillis();
        String key = account + ":" + dialogId;
        synchronized (recentPrunes) {
            Long previous = recentPrunes.get(key);
            if (previous != null && now - previous < RECENT_PRUNE_WINDOW_MS) {
                return true;
            }
            recentPrunes.put(key, now);
            return false;
        }
    }

    private void prune(long dialogId, int account, String source) {
        File databaseFile = databaseFileForAccount(account);
        if (!databaseFile.isFile()) {
            logger.info("DialogDatabasePrune: cache4 missing account=" + account
                    + " path=" + databaseFile.getAbsolutePath());
            return;
        }
        int deletedTotal = 0;
        int touchedTables = 0;
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(
                    databaseFile.getAbsolutePath(),
                    null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS
            );
            setBusyTimeout(database);
            database.beginTransaction();
            for (TelegramDialogPrunePlan.Operation operation : TelegramDialogPrunePlan.directDialogOperations()) {
                int deleted = deleteFromTable(database, operation, dialogId);
                if (deleted > 0) {
                    touchedTables++;
                    deletedTotal += deleted;
                }
            }
            database.setTransactionSuccessful();
        } finally {
            if (database != null) {
                if (database.inTransaction()) {
                    database.endTransaction();
                }
                database.close();
            }
        }
        logger.info("DialogDatabasePrune: pruned dialogId=" + dialogId
                + " account=" + account
                + " source=" + source
                + " tables=" + touchedTables
                + " rows=" + deletedTotal);
    }

    private void setBusyTimeout(SQLiteDatabase database) {
        Cursor cursor = null;
        try {
            cursor = database.rawQuery("PRAGMA busy_timeout=2500", null);
            if (cursor != null) {
                cursor.moveToFirst();
            }
        } catch (SQLiteException exception) {
            logger.info("DialogDatabasePrune: busy_timeout unavailable reason="
                    + exception.getClass().getSimpleName());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private int deleteFromTable(SQLiteDatabase database, TelegramDialogPrunePlan.Operation operation, long dialogId) {
        if (!tableExists(database, operation.table)) {
            return 0;
        }
        try {
            return database.delete(
                    operation.table,
                    operation.column + "=?",
                    new String[]{String.valueOf(dialogId)}
            );
        } catch (SQLiteException exception) {
            logger.info("DialogDatabasePrune: skip table=" + operation.table
                    + " column=" + operation.column
                    + " reason=" + exception.getClass().getSimpleName());
            return 0;
        }
    }

    private boolean tableExists(SQLiteDatabase database, String table) {
        Cursor cursor = null;
        try {
            cursor = database.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                    new String[]{table}
            );
            return cursor.moveToFirst();
        } catch (SQLiteException ignored) {
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private File databaseFileForAccount(int account) {
        File filesDir = context.getFilesDir();
        if (account <= 0) {
            return new File(filesDir, "cache4.db");
        }
        return new File(new File(filesDir, "account" + account), "cache4.db");
    }
}
