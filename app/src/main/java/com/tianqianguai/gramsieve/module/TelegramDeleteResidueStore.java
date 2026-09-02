package com.tianqianguai.gramsieve.module;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Content-free inspection and targeted repair for persisted deleted-message tombstones. */
final class TelegramDeleteResidueStore {
    private static final int FLAG_DELETED = 1 << 31;
    private static final int TASK_DELETE_SCHEDULED_MESSAGES = 18;
    private static final int TASK_DELETE_MESSAGES = 24;
    private static final int TASK_DELETE_QUICK_REPLY_MESSAGES = 103;
    private static final String[] MESSAGE_TABLES = {"messages_v2", "messages_topics"};

    private TelegramDeleteResidueStore() {
    }

    static ScanResult scan(Context context, int accountId, long dialogId, int limit) {
        if (context == null || dialogId == 0L) {
            return new ScanResult("", 0, Collections.emptyList(), "invalid arguments");
        }
        File databaseFile = databaseFile(context, accountId);
        if (!databaseFile.isFile()) {
            return new ScanResult(databaseFile.getAbsolutePath(), 0,
                    Collections.emptyList(), "database missing");
        }
        int boundedLimit = Math.max(1, Math.min(500, limit));
        Map<Integer, Residue> residues = new LinkedHashMap<>();
        int rowsScanned = 0;
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(
                    databaseFile.getAbsolutePath(),
                    null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS
            );
            for (String table : MESSAGE_TABLES) {
                if (!tableExists(database, table)) {
                    continue;
                }
                Cursor cursor = null;
                try {
                    cursor = database.rawQuery(
                            "SELECT mid, data FROM " + table
                                    + " WHERE uid=? ORDER BY mid DESC LIMIT ?",
                            new String[]{String.valueOf(dialogId), String.valueOf(boundedLimit)}
                    );
                    while (cursor.moveToNext()) {
                        rowsScanned++;
                        int messageId = cursor.getInt(0);
                        byte[] data = cursor.getBlob(1);
                        int flags = serializedFlags(data);
                        if (messageId <= 0 || (flags & FLAG_DELETED) == 0) {
                            continue;
                        }
                        Residue previous = residues.get(messageId);
                        if (previous == null) {
                            residues.put(messageId, new Residue(messageId, flags, table));
                        } else if (!previous.tables.contains(table)) {
                            previous.tables.add(table);
                        }
                    }
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
            return new ScanResult(databaseFile.getAbsolutePath(), rowsScanned,
                    new ArrayList<>(residues.values()), "");
        } catch (SQLiteException exception) {
            return new ScanResult(databaseFile.getAbsolutePath(), rowsScanned,
                    new ArrayList<>(residues.values()), exception.getClass().getSimpleName());
        } finally {
            if (database != null) {
                database.close();
            }
        }
    }

    static PurgeResult purgeFlagged(Context context, int accountId, long dialogId, int messageId) {
        if (context == null || dialogId == 0L || messageId <= 0) {
            return new PurgeResult("", 0, Collections.emptyList(), "invalid arguments");
        }
        File databaseFile = databaseFile(context, accountId);
        if (!databaseFile.isFile()) {
            return new PurgeResult(databaseFile.getAbsolutePath(), 0,
                    Collections.emptyList(), "database missing");
        }
        SQLiteDatabase database = null;
        List<String> touchedTables = new ArrayList<>();
        int deletedRows = 0;
        try {
            database = SQLiteDatabase.openDatabase(
                    databaseFile.getAbsolutePath(),
                    null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS
            );
            setBusyTimeout(database);
            database.beginTransaction();
            for (String table : MESSAGE_TABLES) {
                if (!tableExists(database, table)
                        || !isFlagged(database, table, dialogId, messageId)) {
                    continue;
                }
                int deleted = database.delete(
                        table,
                        "uid=? AND mid=?",
                        new String[]{String.valueOf(dialogId), String.valueOf(messageId)}
                );
                if (deleted > 0) {
                    deletedRows += deleted;
                    touchedTables.add(table);
                }
            }
            database.setTransactionSuccessful();
            return new PurgeResult(databaseFile.getAbsolutePath(), deletedRows,
                    touchedTables, deletedRows > 0 ? "" : "flagged message not found");
        } catch (SQLiteException exception) {
            return new PurgeResult(databaseFile.getAbsolutePath(), deletedRows,
                    touchedTables, exception.getClass().getSimpleName());
        } finally {
            if (database != null) {
                if (database.inTransaction()) {
                    database.endTransaction();
                }
                database.close();
            }
        }
    }

    static PendingTaskScanResult scanPendingDeleteTasks(Context context, int accountId) {
        if (context == null) {
            return new PendingTaskScanResult("", 0, Collections.emptyList(),
                    "invalid arguments");
        }
        File databaseFile = databaseFile(context, accountId);
        if (!databaseFile.isFile()) {
            return new PendingTaskScanResult(databaseFile.getAbsolutePath(), 0,
                    Collections.emptyList(), "database missing");
        }
        SQLiteDatabase database = null;
        Cursor cursor = null;
        int rowsScanned = 0;
        List<PendingDeleteTask> deleteTasks = new ArrayList<>();
        try {
            database = SQLiteDatabase.openDatabase(
                    databaseFile.getAbsolutePath(),
                    null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS
            );
            if (!tableExists(database, "pending_tasks")) {
                return new PendingTaskScanResult(databaseFile.getAbsolutePath(), 0,
                        Collections.emptyList(), "pending_tasks missing");
            }
            cursor = database.rawQuery(
                    "SELECT id, data FROM pending_tasks ORDER BY id ASC",
                    null
            );
            while (cursor.moveToNext()) {
                rowsScanned++;
                long taskId = cursor.getLong(0);
                byte[] data = cursor.getBlob(1);
                PendingDeleteTask task = decodePendingDeleteTask(taskId, data);
                if (task != null) {
                    deleteTasks.add(task);
                }
            }
            return new PendingTaskScanResult(databaseFile.getAbsolutePath(), rowsScanned,
                    deleteTasks, "");
        } catch (SQLiteException exception) {
            return new PendingTaskScanResult(databaseFile.getAbsolutePath(), rowsScanned,
                    deleteTasks, exception.getClass().getSimpleName());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            if (database != null) {
                database.close();
            }
        }
    }

    static int serializedFlags(byte[] data) {
        if (data == null || data.length < 8) {
            return 0;
        }
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
    }

    static PendingDeleteTask decodePendingDeleteTask(long taskId, byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int taskType = buffer.getInt();
        if (taskType != TASK_DELETE_MESSAGES
                && taskType != TASK_DELETE_SCHEDULED_MESSAGES
                && taskType != TASK_DELETE_QUICK_REPLY_MESSAGES) {
            return null;
        }
        long dialogId = buffer.getLong();
        return new PendingDeleteTask(taskId, taskType, dialogId, data.length);
    }

    private static boolean isFlagged(
            SQLiteDatabase database,
            String table,
            long dialogId,
            int messageId
    ) {
        Cursor cursor = null;
        try {
            cursor = database.rawQuery(
                    "SELECT data FROM " + table + " WHERE uid=? AND mid=? LIMIT 1",
                    new String[]{String.valueOf(dialogId), String.valueOf(messageId)}
            );
            return cursor.moveToFirst()
                    && (serializedFlags(cursor.getBlob(0)) & FLAG_DELETED) != 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static boolean tableExists(SQLiteDatabase database, String table) {
        Cursor cursor = null;
        try {
            cursor = database.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                    new String[]{table}
            );
            return cursor.moveToFirst();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static void setBusyTimeout(SQLiteDatabase database) {
        Cursor cursor = null;
        try {
            cursor = database.rawQuery("PRAGMA busy_timeout=2500", null);
            cursor.moveToFirst();
        } catch (SQLiteException ignored) {
            // Best effort; the targeted delete still reports a lock error if Telegram is busy.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static File databaseFile(Context context, int accountId) {
        File filesDir = context.getFilesDir();
        if (accountId <= 0) {
            return new File(filesDir, "cache4.db");
        }
        return new File(new File(filesDir, "account" + accountId), "cache4.db");
    }

    static final class Residue {
        final int messageId;
        final int flags;
        final List<String> tables = new ArrayList<>();

        Residue(int messageId, int flags, String table) {
            this.messageId = messageId;
            this.flags = flags;
            this.tables.add(table);
        }
    }

    static final class ScanResult {
        final String databasePath;
        final int rowsScanned;
        final List<Residue> residues;
        final String error;

        ScanResult(String databasePath, int rowsScanned, List<Residue> residues, String error) {
            this.databasePath = databasePath;
            this.rowsScanned = rowsScanned;
            this.residues = residues;
            this.error = error;
        }
    }

    static final class PurgeResult {
        final String databasePath;
        final int deletedRows;
        final List<String> tables;
        final String error;

        PurgeResult(String databasePath, int deletedRows, List<String> tables, String error) {
            this.databasePath = databasePath;
            this.deletedRows = deletedRows;
            this.tables = tables;
            this.error = error;
        }
    }

    static final class PendingDeleteTask {
        final long taskId;
        final int taskType;
        final long dialogId;
        final int serializedBytes;

        PendingDeleteTask(long taskId, int taskType, long dialogId, int serializedBytes) {
            this.taskId = taskId;
            this.taskType = taskType;
            this.dialogId = dialogId;
            this.serializedBytes = serializedBytes;
        }
    }

    static final class PendingTaskScanResult {
        final String databasePath;
        final int rowsScanned;
        final List<PendingDeleteTask> deleteTasks;
        final String error;

        PendingTaskScanResult(
                String databasePath,
                int rowsScanned,
                List<PendingDeleteTask> deleteTasks,
                String error
        ) {
            this.databasePath = databasePath;
            this.rowsScanned = rowsScanned;
            this.deleteTasks = deleteTasks;
            this.error = error;
        }
    }
}
