package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class MessageDatabaseMigrationTest {
    private Context context;
    private MessageDatabaseHelper helper;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(MessageDatabaseHelper.DATABASE_NAME);
    }

    @After
    public void tearDown() {
        if (helper != null) {
            helper.close();
        }
        context.deleteDatabase(MessageDatabaseHelper.DATABASE_NAME);
    }

    @Test
    public void v7RowsMigrateToAccountZeroWithoutLosingRawBlob() {
        byte[] raw = new byte[]{7, 8, 9};
        SQLiteDatabase old = context.openOrCreateDatabase(
                MessageDatabaseHelper.DATABASE_NAME, Context.MODE_PRIVATE, null);
        old.execSQL("CREATE TABLE cached_messages (dialog_id INTEGER NOT NULL, "
                + "message_id INTEGER NOT NULL, sender_id INTEGER, text TEXT, caption TEXT, "
                + "timestamp INTEGER, media_type TEXT, media_id TEXT, cached_media_path TEXT, "
                + "raw_message_blob BLOB, is_recalled INTEGER DEFAULT 0, "
                + "is_edited INTEGER DEFAULT 0, edited_text TEXT, "
                + "PRIMARY KEY(dialog_id, message_id))");
        old.execSQL("CREATE TABLE edit_history (_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "dialog_id INTEGER NOT NULL, message_id INTEGER NOT NULL, sender_id INTEGER, "
                + "text TEXT, caption TEXT, timestamp INTEGER, media_type TEXT, media_id TEXT, "
                + "cached_media_path TEXT, raw_message_blob BLOB, is_recalled INTEGER DEFAULT 0, "
                + "is_edited INTEGER DEFAULT 1, edited_text TEXT)");
        ContentValues values = new ContentValues();
        values.put("dialog_id", 100L);
        values.put("message_id", 5L);
        values.put("text", "old");
        values.put("timestamp", 123L);
        values.put("raw_message_blob", raw);
        old.insertOrThrow("cached_messages", null, values);
        old.insertOrThrow("edit_history", null, values);
        old.setVersion(7);
        old.close();

        helper = new MessageDatabaseHelper(context);
        SQLiteDatabase upgraded = helper.getWritableDatabase();

        assertEquals(8, upgraded.getVersion());
        assertMigratedRow(upgraded, "cached_messages", raw);
        assertMigratedRow(upgraded, "edit_history", raw);
        assertTrue(hasTable(upgraded, "edit_history_settings"));
        assertTrue(hasTable(upgraded, "edit_history_rules"));
    }

    @Test
    public void policyIsIndependentPerAccount() {
        helper = new MessageDatabaseHelper(context);
        helper.getWritableDatabase();
        try (EditHistoryPolicyStore policy = new EditHistoryPolicyStore(context, null)) {
            policy.setEnabled(0, true);
            policy.setMode(0, EditHistoryPolicyStore.Mode.WHITELIST);
            policy.setDialogRecorded(0, 44L, true);

            policy.setEnabled(1, true);
            policy.setMode(1, EditHistoryPolicyStore.Mode.WHITELIST);

            assertTrue(policy.shouldRecord(0, 44L));
            assertFalse(policy.shouldRecord(1, 44L));
        }
    }

    private static void assertMigratedRow(SQLiteDatabase database, String table, byte[] raw) {
        try (Cursor cursor = database.query(table,
                new String[]{"account_id", "raw_message_blob"},
                "dialog_id = ? AND message_id = ?", new String[]{"100", "5"},
                null, null, null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(0, cursor.getInt(0));
            assertArrayEquals(raw, cursor.getBlob(1));
        }
    }

    private static boolean hasTable(SQLiteDatabase database, String table) {
        try (Cursor cursor = database.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{table})) {
            return cursor.moveToFirst();
        }
    }
}
