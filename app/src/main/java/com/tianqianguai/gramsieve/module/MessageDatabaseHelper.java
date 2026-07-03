package com.tianqianguai.gramsieve.module;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class MessageDatabaseHelper extends SQLiteOpenHelper implements MessageStore {
    private static final String DATABASE_NAME = "gramsieve_messages.db";
    private static final int DATABASE_VERSION = 5;
    private static final String TABLE_NAME = "cached_messages";

    public MessageDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_NAME + " ("
                + "dialog_id INTEGER, "
                + "message_id INTEGER, "
                + "sender_id INTEGER, "
                + "text TEXT, "
                + "caption TEXT, "
                + "timestamp INTEGER, "
                + "media_type TEXT, "
                + "media_id TEXT, "
                + "cached_media_path TEXT, "
                + "is_recalled INTEGER DEFAULT 0, "
                + "is_edited INTEGER DEFAULT 0, "
                + "edited_text TEXT, "
                + "PRIMARY KEY (dialog_id, message_id))");
        db.execSQL("CREATE INDEX idx_recalled ON " + TABLE_NAME + " (dialog_id, is_recalled)");
        db.execSQL("CREATE INDEX idx_edited ON " + TABLE_NAME + " (dialog_id, is_edited)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_recalled ON " + TABLE_NAME + " (dialog_id, is_recalled)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_edited ON " + TABLE_NAME + " (dialog_id, is_edited)");
        }
        if (oldVersion < 3) {
            // Reset stale isEdited/isRecalled flags from prior test sessions
            ContentValues values = new ContentValues();
            values.put("is_edited", 0);
            values.put("is_recalled", 0);
            values.putNull("edited_text");
            db.update(TABLE_NAME, values, "is_edited = 1 OR is_recalled = 1", null);
        }
        if (oldVersion < 4) {
            // Add media_type and media_id columns
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN media_type TEXT");
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN media_id TEXT");
        }
        if (oldVersion < 5) {
            // Add cached_media_path column
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN cached_media_path TEXT");
        }
    }

    public void insertMessage(MessageCache.CachedMessage message) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("dialog_id", message.dialogId);
        values.put("message_id", message.messageId);
        values.put("sender_id", message.senderId);
        values.put("text", message.text);
        values.put("caption", message.caption);
        values.put("timestamp", message.timestamp);
        values.put("media_type", message.mediaType);
        values.put("media_id", message.mediaId);
        values.put("cached_media_path", message.cachedMediaPath);
        values.put("is_recalled", message.isRecalled ? 1 : 0);
        values.put("is_edited", message.isEdited ? 1 : 0);
        values.put("edited_text", message.editedText);
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * Insert a fresh message, explicitly resetting is_edited and is_recalled to 0.
     * This is the ground truth from Telegram — the message is NOT edited/recalled.
     */
    public void insertOrReplaceFresh(MessageCache.CachedMessage message) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("dialog_id", message.dialogId);
        values.put("message_id", message.messageId);
        values.put("sender_id", message.senderId);
        values.put("text", message.text);
        values.put("caption", message.caption);
        values.put("timestamp", message.timestamp);
        values.put("media_type", message.mediaType);
        values.put("media_id", message.mediaId);
        values.put("cached_media_path", message.cachedMediaPath);
        values.put("is_recalled", 0);
        values.put("is_edited", 0);
        values.put("edited_text", (String) null);
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void updateMessage(MessageCache.CachedMessage message) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("is_recalled", message.isRecalled ? 1 : 0);
        values.put("is_edited", message.isEdited ? 1 : 0);
        values.put("edited_text", message.editedText);
        db.update(TABLE_NAME, values, "dialog_id = ? AND message_id = ?",
                new String[]{String.valueOf(message.dialogId), String.valueOf(message.messageId)});
    }

    public MessageCache.CachedMessage getMessage(long dialogId, long messageId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null, "dialog_id = ? AND message_id = ?",
                new String[]{String.valueOf(dialogId), String.valueOf(messageId)}, null, null, null);
        if (cursor == null) {
            return null;
        }
        try {
            if (cursor.moveToFirst()) {
                return cursorToMessage(cursor);
            }
            return null;
        } finally {
            cursor.close();
        }
    }

    public List<MessageCache.CachedMessage> getRecalledMessages(long dialogId) {
        List<MessageCache.CachedMessage> messages = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null, "dialog_id = ? AND is_recalled = 1",
                new String[]{String.valueOf(dialogId)}, null, null, "timestamp DESC");
        if (cursor == null) {
            return messages;
        }
        try {
            while (cursor.moveToNext()) {
                messages.add(cursorToMessage(cursor));
            }
        } finally {
            cursor.close();
        }
        return messages;
    }

    public List<MessageCache.CachedMessage> getEditedMessages(long dialogId) {
        List<MessageCache.CachedMessage> messages = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null, "dialog_id = ? AND is_edited = 1",
                new String[]{String.valueOf(dialogId)}, null, null, "timestamp DESC");
        if (cursor == null) {
            return messages;
        }
        try {
            while (cursor.moveToNext()) {
                messages.add(cursorToMessage(cursor));
            }
        } finally {
            cursor.close();
        }
        return messages;
    }

    private MessageCache.CachedMessage cursorToMessage(Cursor cursor) {
        int mediaTypeIdx = cursor.getColumnIndex("media_type");
        int mediaIdIdx = cursor.getColumnIndex("media_id");
        int cachedMediaPathIdx = cursor.getColumnIndex("cached_media_path");
        return new MessageCache.CachedMessage(
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
                cursor.getString(cursor.getColumnIndexOrThrow("edited_text"))
        );
    }
}
