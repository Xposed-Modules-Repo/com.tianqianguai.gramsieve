package com.tianqianguai.gramsieve.module;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class MessageDatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "gramsieve_messages.db";
    private static final int DATABASE_VERSION = 1;
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
                + "is_recalled INTEGER DEFAULT 0, "
                + "is_edited INTEGER DEFAULT 0, "
                + "edited_text TEXT, "
                + "PRIMARY KEY (dialog_id, message_id))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
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
        values.put("is_recalled", message.isRecalled ? 1 : 0);
        values.put("is_edited", message.isEdited ? 1 : 0);
        values.put("edited_text", message.editedText);
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
        if (cursor != null && cursor.moveToFirst()) {
            MessageCache.CachedMessage message = cursorToMessage(cursor);
            cursor.close();
            return message;
        }
        return null;
    }

    public List<MessageCache.CachedMessage> getRecalledMessages(long dialogId) {
        List<MessageCache.CachedMessage> messages = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null, "dialog_id = ? AND is_recalled = 1",
                new String[]{String.valueOf(dialogId)}, null, null, "timestamp DESC");
        if (cursor != null) {
            while (cursor.moveToNext()) {
                messages.add(cursorToMessage(cursor));
            }
            cursor.close();
        }
        return messages;
    }

    public List<MessageCache.CachedMessage> getEditedMessages(long dialogId) {
        List<MessageCache.CachedMessage> messages = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_NAME, null, "dialog_id = ? AND is_edited = 1",
                new String[]{String.valueOf(dialogId)}, null, null, "timestamp DESC");
        if (cursor != null) {
            while (cursor.moveToNext()) {
                messages.add(cursorToMessage(cursor));
            }
            cursor.close();
        }
        return messages;
    }

    private MessageCache.CachedMessage cursorToMessage(Cursor cursor) {
        return new MessageCache.CachedMessage(
                cursor.getLong(cursor.getColumnIndex("dialog_id")),
                cursor.getLong(cursor.getColumnIndex("message_id")),
                cursor.getLong(cursor.getColumnIndex("sender_id")),
                cursor.getString(cursor.getColumnIndex("text")),
                cursor.getString(cursor.getColumnIndex("caption")),
                cursor.getLong(cursor.getColumnIndex("timestamp"))
        );
    }
}
