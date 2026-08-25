package com.tianqianguai.gramsieve.module;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class MessageDatabaseHelper extends SQLiteOpenHelper implements MessageStore {
    static final String DATABASE_NAME = "gramsieve_messages.db";
    private static final int DATABASE_VERSION = 8;
    private static final String TABLE_NAME = "cached_messages";
    private static final String EDIT_HISTORY_TABLE_NAME = "edit_history";

    public MessageDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createCachedMessagesTable(db);
        createCachedMessageIndexes(db);
        createEditHistoryTable(db);
        createPolicyTables(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_recalled ON " + TABLE_NAME
                    + " (dialog_id, is_recalled)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_edited ON " + TABLE_NAME
                    + " (dialog_id, is_edited)");
        }
        if (oldVersion < 3) {
            ContentValues values = new ContentValues();
            values.put("is_edited", 0);
            values.put("is_recalled", 0);
            values.putNull("edited_text");
            db.update(TABLE_NAME, values, "is_edited = 1 OR is_recalled = 1", null);
        }
        if (oldVersion < 4) {
            addColumnIfMissing(db, TABLE_NAME, "media_type", "TEXT");
            addColumnIfMissing(db, TABLE_NAME, "media_id", "TEXT");
        }
        if (oldVersion < 5) {
            addColumnIfMissing(db, TABLE_NAME, "cached_media_path", "TEXT");
        }
        if (oldVersion < 6) {
            createEditHistoryTable(db);
        }
        if (oldVersion < 7) {
            addColumnIfMissing(db, TABLE_NAME, "raw_message_blob", "BLOB");
            addColumnIfMissing(db, EDIT_HISTORY_TABLE_NAME, "raw_message_blob", "BLOB");
            if (oldVersion < 6) {
                migrateExistingEditedRows(db);
            }
        }
        if (oldVersion < 8) {
            migrateV7ToV8(db);
        }
        createPolicyTables(db);
    }

    @Override
    public void insertMessage(MessageCache.CachedMessage message) {
        insertMessage(0, message);
    }

    @Override
    public void insertMessage(int accountId, MessageCache.CachedMessage message) {
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict(TABLE_NAME, null, messageValues(message, accountId),
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override
    public void insertOrReplaceFresh(MessageCache.CachedMessage message) {
        insertOrReplaceFresh(0, message);
    }

    @Override
    public void insertOrReplaceFresh(int accountId, MessageCache.CachedMessage message) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = messageValues(message, accountId);
        values.put("is_recalled", 0);
        values.put("is_edited", 0);
        values.putNull("edited_text");
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override
    public void insertEditHistory(MessageCache.CachedMessage message) {
        insertEditHistory(0, message);
    }

    @Override
    public void insertEditHistory(int accountId, MessageCache.CachedMessage message) {
        getWritableDatabase().insert(EDIT_HISTORY_TABLE_NAME, null,
                messageValues(message, accountId));
    }

    @Override
    public void updateMessage(MessageCache.CachedMessage message) {
        updateMessage(0, message);
    }

    @Override
    public void updateMessage(int accountId, MessageCache.CachedMessage message) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = messageValues(message, accountId);
        values.remove("account_id");
        values.remove("dialog_id");
        values.remove("message_id");
        db.update(TABLE_NAME, values, "account_id = ? AND dialog_id = ? AND message_id = ?",
                new String[]{String.valueOf(accountId), String.valueOf(message.dialogId),
                        String.valueOf(message.messageId)});
    }

    @Override
    public void deleteMessage(long dialogId, long messageId) {
        deleteMessage(0, dialogId, messageId);
    }

    @Override
    public void deleteMessage(int accountId, long dialogId, long messageId) {
        SQLiteDatabase db = getWritableDatabase();
        String[] args = {String.valueOf(accountId), String.valueOf(dialogId), String.valueOf(messageId)};
        db.delete(TABLE_NAME, "account_id = ? AND dialog_id = ? AND message_id = ?", args);
        db.delete(EDIT_HISTORY_TABLE_NAME, "account_id = ? AND dialog_id = ? AND message_id = ?", args);
    }

    @Override
    public void deleteDialog(long dialogId) {
        deleteDialog(0, dialogId);
    }

    @Override
    public void deleteDialog(int accountId, long dialogId) {
        SQLiteDatabase db = getWritableDatabase();
        String[] args = {String.valueOf(accountId), String.valueOf(dialogId)};
        db.delete(TABLE_NAME, "account_id = ? AND dialog_id = ?", args);
        db.delete(EDIT_HISTORY_TABLE_NAME, "account_id = ? AND dialog_id = ?", args);
    }

    @Override
    public MessageCache.CachedMessage getMessage(long dialogId, long messageId) {
        return getMessage(0, dialogId, messageId);
    }

    @Override
    public MessageCache.CachedMessage getMessage(int accountId, long dialogId, long messageId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null,
                "account_id = ? AND dialog_id = ? AND message_id = ?",
                new String[]{String.valueOf(accountId), String.valueOf(dialogId), String.valueOf(messageId)},
                null, null, null);
        try {
            return cursor.moveToFirst() ? cursorToMessage(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    @Override
    public List<MessageCache.CachedMessage> getRecalledMessages(long dialogId) {
        return getRecalledMessages(0, dialogId);
    }

    @Override
    public List<MessageCache.CachedMessage> getRecalledMessages(int accountId, long dialogId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null,
                "account_id = ? AND dialog_id = ? AND is_recalled = 1",
                new String[]{String.valueOf(accountId), String.valueOf(dialogId)},
                null, null, "timestamp DESC");
        return readMessages(cursor);
    }

    @Override
    public List<MessageCache.CachedMessage> getEditedMessages(long dialogId) {
        return getEditedMessages(0, dialogId);
    }

    @Override
    public List<MessageCache.CachedMessage> getEditedMessages(int accountId, long dialogId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(EDIT_HISTORY_TABLE_NAME, null,
                "account_id = ? AND dialog_id = ?",
                new String[]{String.valueOf(accountId), String.valueOf(dialogId)},
                null, null, "timestamp DESC, _id DESC");
        return readMessages(cursor);
    }

    @Override
    public List<MessageCache.CachedMessage> getEditHistory(long dialogId, long messageId) {
        return getEditHistory(0, dialogId, messageId);
    }

    @Override
    public List<MessageCache.CachedMessage> getEditHistory(int accountId, long dialogId, long messageId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(EDIT_HISTORY_TABLE_NAME, null,
                "account_id = ? AND dialog_id = ? AND message_id = ?",
                new String[]{String.valueOf(accountId), String.valueOf(dialogId), String.valueOf(messageId)},
                null, null, "timestamp DESC, _id DESC");
        return readMessages(cursor);
    }

    private List<MessageCache.CachedMessage> readMessages(Cursor cursor) {
        List<MessageCache.CachedMessage> messages = new ArrayList<>();
        try {
            while (cursor.moveToNext()) {
                messages.add(cursorToMessage(cursor));
            }
            return messages;
        } finally {
            cursor.close();
        }
    }

    private void createCachedMessagesTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("
                + "account_id INTEGER NOT NULL DEFAULT 0, "
                + "dialog_id INTEGER NOT NULL, "
                + "message_id INTEGER NOT NULL, "
                + "sender_id INTEGER, "
                + "text TEXT, "
                + "caption TEXT, "
                + "timestamp INTEGER, "
                + "media_type TEXT, "
                + "media_id TEXT, "
                + "cached_media_path TEXT, "
                + "raw_message_blob BLOB, "
                + "is_recalled INTEGER DEFAULT 0, "
                + "is_edited INTEGER DEFAULT 0, "
                + "edited_text TEXT, "
                + "PRIMARY KEY (account_id, dialog_id, message_id))");
    }

    private void createCachedMessageIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_recalled ON " + TABLE_NAME
                + " (account_id, dialog_id, is_recalled)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edited ON " + TABLE_NAME
                + " (account_id, dialog_id, is_edited)");
    }

    private void createEditHistoryTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + EDIT_HISTORY_TABLE_NAME + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "account_id INTEGER NOT NULL DEFAULT 0, "
                + "dialog_id INTEGER NOT NULL, "
                + "message_id INTEGER NOT NULL, "
                + "sender_id INTEGER, "
                + "text TEXT, "
                + "caption TEXT, "
                + "timestamp INTEGER, "
                + "media_type TEXT, "
                + "media_id TEXT, "
                + "cached_media_path TEXT, "
                + "raw_message_blob BLOB, "
                + "is_recalled INTEGER DEFAULT 0, "
                + "is_edited INTEGER DEFAULT 1, "
                + "edited_text TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edit_history_message ON "
                + EDIT_HISTORY_TABLE_NAME + " (account_id, dialog_id, message_id, timestamp)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edit_history_dialog ON "
                + EDIT_HISTORY_TABLE_NAME + " (account_id, dialog_id, timestamp)");
    }

    private void createPolicyTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS edit_history_settings ("
                + "account_id INTEGER PRIMARY KEY, "
                + "enabled INTEGER NOT NULL, "
                + "mode TEXT NOT NULL, "
                + "initialized INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS edit_history_rules ("
                + "account_id INTEGER NOT NULL, "
                + "dialog_id INTEGER NOT NULL, "
                + "record INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL, "
                + "PRIMARY KEY(account_id, dialog_id))");
    }

    private void migrateV7ToV8(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            db.execSQL("DROP INDEX IF EXISTS idx_recalled");
            db.execSQL("DROP INDEX IF EXISTS idx_edited");
            db.execSQL("DROP INDEX IF EXISTS idx_edit_history_message");
            db.execSQL("DROP INDEX IF EXISTS idx_edit_history_dialog");

            db.execSQL("ALTER TABLE " + TABLE_NAME + " RENAME TO " + TABLE_NAME + "_v7");
            createCachedMessagesTable(db);
            db.execSQL("INSERT INTO " + TABLE_NAME + " ("
                    + "account_id, dialog_id, message_id, sender_id, text, caption, timestamp, "
                    + "media_type, media_id, cached_media_path, raw_message_blob, is_recalled, "
                    + "is_edited, edited_text) SELECT 0, dialog_id, message_id, sender_id, text, "
                    + "caption, timestamp, media_type, media_id, cached_media_path, raw_message_blob, "
                    + "is_recalled, is_edited, edited_text FROM " + TABLE_NAME + "_v7");
            db.execSQL("DROP TABLE " + TABLE_NAME + "_v7");

            db.execSQL("ALTER TABLE " + EDIT_HISTORY_TABLE_NAME + " RENAME TO "
                    + EDIT_HISTORY_TABLE_NAME + "_v7");
            createEditHistoryTable(db);
            db.execSQL("INSERT INTO " + EDIT_HISTORY_TABLE_NAME + " ("
                    + "_id, account_id, dialog_id, message_id, sender_id, text, caption, timestamp, "
                    + "media_type, media_id, cached_media_path, raw_message_blob, is_recalled, "
                    + "is_edited, edited_text) SELECT _id, 0, dialog_id, message_id, sender_id, text, "
                    + "caption, timestamp, media_type, media_id, cached_media_path, raw_message_blob, "
                    + "is_recalled, is_edited, edited_text FROM " + EDIT_HISTORY_TABLE_NAME + "_v7");
            db.execSQL("DROP TABLE " + EDIT_HISTORY_TABLE_NAME + "_v7");
            createCachedMessageIndexes(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void migrateExistingEditedRows(SQLiteDatabase db) {
        db.execSQL("INSERT INTO " + EDIT_HISTORY_TABLE_NAME + " ("
                + "account_id, dialog_id, message_id, sender_id, text, caption, timestamp, "
                + "media_type, media_id, cached_media_path, raw_message_blob, is_recalled, "
                + "is_edited, edited_text) SELECT 0, dialog_id, message_id, sender_id, text, caption, "
                + "timestamp, media_type, media_id, cached_media_path, raw_message_blob, is_recalled, "
                + "is_edited, edited_text FROM " + TABLE_NAME + " WHERE is_edited = 1");
    }

    private ContentValues messageValues(MessageCache.CachedMessage message, int accountId) {
        ContentValues values = new ContentValues();
        values.put("account_id", accountId);
        values.put("dialog_id", message.dialogId);
        values.put("message_id", message.messageId);
        values.put("sender_id", message.senderId);
        values.put("text", message.text);
        values.put("caption", message.caption);
        values.put("timestamp", message.timestamp);
        values.put("media_type", message.mediaType);
        values.put("media_id", message.mediaId);
        values.put("cached_media_path", message.cachedMediaPath);
        putRawMessageBlob(values, message.rawMessageBlob);
        values.put("is_recalled", message.isRecalled ? 1 : 0);
        values.put("is_edited", message.isEdited ? 1 : 0);
        values.put("edited_text", message.editedText);
        return values;
    }

    private MessageCache.CachedMessage cursorToMessage(Cursor cursor) {
        int accountIdIdx = cursor.getColumnIndex("account_id");
        int mediaTypeIdx = cursor.getColumnIndex("media_type");
        int mediaIdIdx = cursor.getColumnIndex("media_id");
        int cachedMediaPathIdx = cursor.getColumnIndex("cached_media_path");
        int rawMessageBlobIdx = cursor.getColumnIndex("raw_message_blob");
        return new MessageCache.CachedMessage(
                accountIdIdx >= 0 ? cursor.getInt(accountIdIdx) : 0,
                cursor.getLong(cursor.getColumnIndexOrThrow("dialog_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("message_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("sender_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("text")),
                cursor.getString(cursor.getColumnIndexOrThrow("caption")),
                cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                mediaTypeIdx >= 0 ? cursor.getString(mediaTypeIdx) : null,
                mediaIdIdx >= 0 ? cursor.getString(mediaIdIdx) : null,
                cachedMediaPathIdx >= 0 ? cursor.getString(cachedMediaPathIdx) : null,
                cursor.getInt(cursor.getColumnIndexOrThrow("is_recalled")) != 0,
                cursor.getInt(cursor.getColumnIndexOrThrow("is_edited")) != 0,
                cursor.getString(cursor.getColumnIndexOrThrow("edited_text")),
                rawMessageBlobIdx >= 0 && !cursor.isNull(rawMessageBlobIdx)
                        ? cursor.getBlob(rawMessageBlobIdx) : null
        );
    }

    private static void addColumnIfMissing(SQLiteDatabase db, String table, String column, String definition) {
        if (!hasColumn(db, table, column)) {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private static boolean hasColumn(SQLiteDatabase db, String table, String column) {
        Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        try {
            int nameIndex = cursor.getColumnIndex("name");
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && column.equals(cursor.getString(nameIndex))) {
                    return true;
                }
            }
            return false;
        } finally {
            cursor.close();
        }
    }

    private static void putRawMessageBlob(ContentValues values, byte[] rawMessageBlob) {
        if (rawMessageBlob == null) {
            values.putNull("raw_message_blob");
        } else {
            values.put("raw_message_blob", rawMessageBlob.clone());
        }
    }
}
