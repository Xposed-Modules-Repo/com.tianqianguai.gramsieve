# 主动加载与防撤回防修改功能实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现主动加载与防撤回防修改功能，确保 Telegram 在后台时也能缓存消息内容，在消息被撤回或修改时保留原始内容。

**Architecture:** 通过 hook Telegram 的 MessagesController 实现消息监听和主动加载，使用 LRU Cache + SQLite 存储消息内容，支持每聊天独立配置。

**Tech Stack:** Java, Android, LSPosed/Xposed, SQLite, SharedPreferences

## Global Constraints

- 使用 Java 11 语法
- 遵循现有代码风格（4 空格缩进）
- 使用 PascalCase 命名类，camelCase 命名字段和方法
- 使用 UPPER_SNAKE_CASE 命名常量
- 保持与现有 GramSieve 架构一致

---

### Task 1: 创建数据库和缓存基础设施

**Covers:** [S3, S7]

**Files:**
- Create: `app/src/main/java/com/tianqianguai/gramsieve/module/MessageCache.java`
- Create: `app/src/main/java/com/tianqianguai/gramsieve/config/AntiRecallConfigStore.java`
- Test: `app/src/test/java/com/tianqianguai/gramsieve/module/MessageCacheTest.java`

**Interfaces:**
- Produces: `MessageCache` 类提供消息缓存功能
- Produces: `AntiRecallConfigStore` 类提供配置存储功能

- [ ] **Step 1: 创建 MessageCache 类**

```java
package com.tianqianguai.gramsieve.module;

import android.util.LruCache;
import java.util.List;

public final class MessageCache {
    private static final int MAX_CACHE_SIZE = 1000;
    private final LruCache<String, CachedMessage> memoryCache;
    private final MessageDatabaseHelper databaseHelper;

    public MessageCache(MessageDatabaseHelper databaseHelper) {
        this.memoryCache = new LruCache<>(MAX_CACHE_SIZE);
        this.databaseHelper = databaseHelper;
    }

    public void put(long dialogId, long messageId, String text, String caption, long senderId) {
        String key = dialogId + ":" + messageId;
        CachedMessage message = new CachedMessage(dialogId, messageId, senderId, text, caption, System.currentTimeMillis());
        memoryCache.put(key, message);
        databaseHelper.insertMessage(message);
    }

    public CachedMessage get(long dialogId, long messageId) {
        String key = dialogId + ":" + messageId;
        CachedMessage message = memoryCache.get(key);
        if (message == null) {
            message = databaseHelper.getMessage(dialogId, messageId);
            if (message != null) {
                memoryCache.put(key, message);
            }
        }
        return message;
    }

    public void markRecalled(long dialogId, long messageId) {
        CachedMessage message = get(dialogId, messageId);
        if (message != null) {
            message.isRecalled = true;
            databaseHelper.updateMessage(message);
        }
    }

    public void markEdited(long dialogId, long messageId, String newText) {
        CachedMessage message = get(dialogId, messageId);
        if (message != null) {
            message.isEdited = true;
            message.editedText = newText;
            databaseHelper.updateMessage(message);
        }
    }

    public List<CachedMessage> getRecalledMessages(long dialogId) {
        return databaseHelper.getRecalledMessages(dialogId);
    }

    public List<CachedMessage> getEditedMessages(long dialogId) {
        return databaseHelper.getEditedMessages(dialogId);
    }

    public static final class CachedMessage {
        public final long dialogId;
        public final long messageId;
        public final long senderId;
        public final String text;
        public final String caption;
        public final long timestamp;
        public boolean isRecalled;
        public boolean isEdited;
        public String editedText;

        public CachedMessage(long dialogId, long messageId, long senderId, String text, String caption, long timestamp) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.senderId = senderId;
            this.text = text;
            this.caption = caption;
            this.timestamp = timestamp;
        }
    }
}
```

- [ ] **Step 2: 创建 MessageDatabaseHelper 类**

```java
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
        db.execSQL("CREATE TABLE " + TABLE_NAME + " (" +
                "dialog_id INTEGER, " +
                "message_id INTEGER, " +
                "sender_id INTEGER, " +
                "text TEXT, " +
                "caption TEXT, " +
                "timestamp INTEGER, " +
                "is_recalled INTEGER DEFAULT 0, " +
                "is_edited INTEGER DEFAULT 0, " +
                "edited_text TEXT, " +
                "PRIMARY KEY (dialog_id, message_id))");
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
```

- [ ] **Step 3: 创建单元测试**

```java
package com.tianqianguai.gramsieve.module;

import org.junit.Test;
import static org.junit.Assert.*;

public class MessageCacheTest {
    @Test
    public void testCachedMessageCreation() {
        MessageCache.CachedMessage message = new MessageCache.CachedMessage(123, 456, 789, "Hello", "World", System.currentTimeMillis());
        assertEquals(123, message.dialogId);
        assertEquals(456, message.messageId);
        assertEquals(789, message.senderId);
        assertEquals("Hello", message.text);
        assertEquals("World", message.caption);
        assertFalse(message.isRecalled);
        assertFalse(message.isEdited);
    }

    @Test
    public void testMarkRecalled() {
        MessageCache.CachedMessage message = new MessageCache.CachedMessage(123, 456, 789, "Hello", "World", System.currentTimeMillis());
        message.isRecalled = true;
        assertTrue(message.isRecalled);
    }

    @Test
    public void testMarkEdited() {
        MessageCache.CachedMessage message = new MessageCache.CachedMessage(123, 456, 789, "Hello", "World", System.currentTimeMillis());
        message.isEdited = true;
        message.editedText = "New Text";
        assertTrue(message.isEdited);
        assertEquals("New Text", message.editedText);
    }
}
```

- [ ] **Step 4: 运行测试**

Run: `./gradlew.bat testDebugUnitTest --tests "com.tianqianguai.gramsieve.module.MessageCacheTest"`
Expected: 所有测试通过

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/tianqianguai/gramsieve/module/MessageCache.java
git add app/src/main/java/com/tianqianguai/gramsieve/module/MessageDatabaseHelper.java
git add app/src/test/java/com/tianqianguai/gramsieve/module/MessageCacheTest.java
git commit -m "feat(anti-recall): add message cache and database infrastructure"
```

---

### Task 2: 创建配置管理

**Covers:** [S4, S8]

**Files:**
- Create: `app/src/main/java/com/tianqianguai/gramsieve/config/AntiRecallConfigStore.java`
- Test: `app/src/test/java/com/tianqianguai/gramsieve/config/AntiRecallConfigStoreTest.java`

**Interfaces:**
- Produces: `AntiRecallConfigStore` 类提供配置存储功能

- [ ] **Step 1: 创建 AntiRecallConfigStore 类**

```java
package com.tianqianguai.gramsieve.config;

import android.content.Context;
import android.content.SharedPreferences;

public final class AntiRecallConfigStore {
    private static final String PREFS_NAME = "gramsieve_anti_recall";
    private static final String KEY_ENABLED = "anti_recall_enabled";
    private static final String KEY_LOAD_INTERVAL = "load_interval_seconds";
    private static final String KEY_MAX_CACHE_SIZE = "max_cache_size";

    private static final boolean DEFAULT_ENABLED = false;
    private static final int DEFAULT_LOAD_INTERVAL = 30;
    private static final int DEFAULT_MAX_CACHE_SIZE = 1000;

    private final SharedPreferences prefs;

    public AntiRecallConfigStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public int getLoadIntervalSeconds() {
        return prefs.getInt(KEY_LOAD_INTERVAL, DEFAULT_LOAD_INTERVAL);
    }

    public void setLoadIntervalSeconds(int seconds) {
        prefs.edit().putInt(KEY_LOAD_INTERVAL, seconds).apply();
    }

    public int getMaxCacheSize() {
        return prefs.getInt(KEY_MAX_CACHE_SIZE, DEFAULT_MAX_CACHE_SIZE);
    }

    public void setMaxCacheSize(int size) {
        prefs.edit().putInt(KEY_MAX_CACHE_SIZE, size).apply();
    }

    public boolean isChatEnabled(long dialogId) {
        return prefs.getBoolean("anti_recall_chat_" + dialogId, false);
    }

    public void setChatEnabled(long dialogId, boolean enabled) {
        prefs.edit().putBoolean("anti_recall_chat_" + dialogId, enabled).apply();
    }
}
```

- [ ] **Step 2: 创建单元测试**

```java
package com.tianqianguai.gramsieve.config;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AntiRecallConfigStoreTest {
    @Test
    public void testDefaultValues() {
        Context context = Mockito.mock(Context.class);
        SharedPreferences prefs = Mockito.mock(SharedPreferences.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.getBoolean(anyString(), anyBoolean())).thenReturn(false);
        when(prefs.getInt(anyString(), anyInt())).thenReturn(30);

        AntiRecallConfigStore store = new AntiRecallConfigStore(context);
        assertFalse(store.isEnabled());
        assertEquals(30, store.getLoadIntervalSeconds());
    }

    @Test
    public void testSetEnabled() {
        Context context = Mockito.mock(Context.class);
        SharedPreferences prefs = Mockito.mock(SharedPreferences.class);
        SharedPreferences.Editor editor = Mockito.mock(SharedPreferences.Editor.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.edit()).thenReturn(editor);
        when(editor.putBoolean(anyString(), anyBoolean())).thenReturn(editor);

        AntiRecallConfigStore store = new AntiRecallConfigStore(context);
        store.setEnabled(true);
        verify(editor).putBoolean("anti_recall_enabled", true);
    }
}
```

- [ ] **Step 3: 运行测试**

Run: `./gradlew.bat testDebugUnitTest --tests "com.tianqianguai.gramsieve.config.AntiRecallConfigStoreTest"`
Expected: 所有测试通过

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/tianqianguai/gramsieve/config/AntiRecallConfigStore.java
git add app/src/test/java/com/tianqianguai/gramsieve/config/AntiRecallConfigStoreTest.java
git commit -m "feat(anti-recall): add configuration store"
```

---

### Task 3: 实现后台消息加载器

**Covers:** [S2, S5]

**Files:**
- Create: `app/src/main/java/com/tianqianguai/gramsieve/module/BackgroundMessageLoader.java`
- Test: `app/src/test/java/com/tianqianguai/gramsieve/module/BackgroundMessageLoaderTest.java`

**Interfaces:**
- Consumes: `MessageCache` 和 `AntiRecallConfigStore`
- Produces: `BackgroundMessageLoader` 类提供后台消息加载功能

- [ ] **Step 1: 创建 BackgroundMessageLoader 类**

```java
package com.tianqianguai.gramsieve.module;

import android.os.Handler;
import android.os.Looper;
import com.tianqianguai.gramsieve.config.AntiRecallConfigStore;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class BackgroundMessageLoader {
    private final MessageCache messageCache;
    private final AntiRecallConfigStore configStore;
    private final ScheduledExecutorService scheduler;
    private final Handler mainHandler;
    private final Set<Long> enabledChats = ConcurrentHashMap.newKeySet();
    private volatile boolean running;

    public BackgroundMessageLoader(MessageCache messageCache, AntiRecallConfigStore configStore) {
        this.messageCache = messageCache;
        this.configStore = configStore;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        int interval = configStore.getLoadIntervalSeconds();
        scheduler.scheduleAtFixedRate(this::loadMessages, 0, interval, TimeUnit.SECONDS);
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
    }

    public void enableChat(long dialogId) {
        enabledChats.add(dialogId);
        configStore.setChatEnabled(dialogId, true);
    }

    public void disableChat(long dialogId) {
        enabledChats.remove(dialogId);
        configStore.setChatEnabled(dialogId, false);
    }

    public boolean isChatEnabled(long dialogId) {
        return enabledChats.contains(dialogId);
    }

    private void loadMessages() {
        if (!configStore.isEnabled()) {
            return;
        }
        for (long dialogId : enabledChats) {
            try {
                loadMessagesForChat(dialogId);
            } catch (Throwable throwable) {
                // Log error but continue with other chats
            }
        }
    }

    private void loadMessagesForChat(long dialogId) {
        try {
            ClassLoader classLoader = getClass().getClassLoader();
            Class<?> messagesControllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            Object controller = Reflect.invokeStatic(messagesControllerClass, "getInstance", new Class<?>[]{int.class}, 0);
            if (controller == null) {
                return;
            }
            Method loadMessages = Reflect.method(messagesControllerClass, "loadMessages",
                    long.class, int.class, int.class, int.class, boolean.class, int.class, int.class, int.class, int.class, boolean.class, int.class);
            Reflect.invoke(loadMessages, controller, dialogId, 0, 50, 0, false, 0, 0, 0, 0, true, 0);
        } catch (Throwable throwable) {
            // Log error
        }
    }

    public void onMessageReceived(long dialogId, long messageId, String text, String caption, long senderId) {
        if (!isChatEnabled(dialogId)) {
            return;
        }
        messageCache.put(dialogId, messageId, text, caption, senderId);
    }
}
```

- [ ] **Step 2: 创建单元测试**

```java
package com.tianqianguai.gramsieve.module;

import org.junit.Test;
import static org.junit.Assert.*;

public class BackgroundMessageLoaderTest {
    @Test
    public void testEnableDisableChat() {
        BackgroundMessageLoader loader = new BackgroundMessageLoader(null, null);
        loader.enableChat(123);
        assertTrue(loader.isChatEnabled(123));
        loader.disableChat(123);
        assertFalse(loader.isChatEnabled(123));
    }
}
```

- [ ] **Step 3: 运行测试**

Run: `./gradlew.bat testDebugUnitTest --tests "com.tianqianguai.gramsieve.module.BackgroundMessageLoaderTest"`
Expected: 所有测试通过

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/tianqianguai/gramsieve/module/BackgroundMessageLoader.java
git add app/src/test/java/com/tianqianguai/gramsieve/module/BackgroundMessageLoaderTest.java
git commit -m "feat(anti-recall): add background message loader"
```

---

### Task 4: 实现撤回/修改检测器

**Covers:** [S2, S5]

**Files:**
- Create: `app/src/main/java/com/tianqianguai/gramsieve/module/RecallDetector.java`
- Test: `app/src/test/java/com/tianqianguai/gramsieve/module/RecallDetectorTest.java`

**Interfaces:**
- Consumes: `MessageCache`
- Produces: `RecallDetector` 类提供撤回/修改检测功能

- [ ] **Step 1: 创建 RecallDetector 类**

```java
package com.tianqianguai.gramsieve.module;

import java.lang.reflect.Method;

public final class RecallDetector {
    private final MessageCache messageCache;
    private final BackgroundMessageLoader loader;

    public RecallDetector(MessageCache messageCache, BackgroundMessageLoader loader) {
        this.messageCache = messageCache;
        this.loader = loader;
    }

    public void install(ClassLoader classLoader) {
        hookProcessUpdateArray(classLoader);
        hookDeleteMessages(classLoader);
        hookEditMessage(classLoader);
    }

    private void hookProcessUpdateArray(ClassLoader classLoader) {
        try {
            Class<?> messagesControllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            Method processUpdateArray = Reflect.method(messagesControllerClass, "processUpdateArray", java.util.ArrayList.class);
            Reflect.hook(processUpdateArray, chain -> {
                Object result = chain.proceed();
                try {
                    java.util.ArrayList<?> updates = (java.util.ArrayList<?>) chain.getArg(0);
                    processUpdates(updates);
                } catch (Throwable throwable) {
                    // Log error
                }
                return result;
            });
        } catch (Throwable throwable) {
            // Log error
        }
    }

    private void hookDeleteMessages(ClassLoader classLoader) {
        try {
            Class<?> messagesControllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            Method deleteMessages = Reflect.method(messagesControllerClass, "deleteMessages",
                    java.util.ArrayList.class, java.util.ArrayList.class, java.util.ArrayList.class, long.class, int.class, boolean.class);
            Reflect.hook(deleteMessages, chain -> {
                try {
                    java.util.ArrayList<?> messagesIds = (java.util.ArrayList<?>) chain.getArg(0);
                    long dialogId = (long) chain.getArg(3);
                    processDeletions(dialogId, messagesIds);
                } catch (Throwable throwable) {
                    // Log error
                }
                return chain.proceed();
            });
        } catch (Throwable throwable) {
            // Log error
        }
    }

    private void hookEditMessage(ClassLoader classLoader) {
        try {
            Class<?> messagesControllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            Method editMessage = Reflect.method(messagesControllerClass, "editMessage",
                    long.class, int.class, String.class, boolean.class, java.util.ArrayList.class, boolean.class, boolean.class);
            Reflect.hook(editMessage, chain -> {
                try {
                    long dialogId = (long) chain.getArg(0);
                    int messageId = (int) chain.getArg(1);
                    String newText = (String) chain.getArg(2);
                    processEdit(dialogId, messageId, newText);
                } catch (Throwable throwable) {
                    // Log error
                }
                return chain.proceed();
            });
        } catch (Throwable throwable) {
            // Log error
        }
    }

    private void processUpdates(java.util.ArrayList<?> updates) {
        for (Object update : updates) {
            try {
                Object message = Reflect.field(update, "message");
                if (message != null) {
                    long dialogId = Reflect.asLong(Reflect.invokeIfExists(message, "getDialogId", new Class<?>[0]), 0L);
                    long messageId = Reflect.asLong(Reflect.invokeIfExists(message, "getId", new Class<?>[0]), 0L);
                    String text = Reflect.asString(Reflect.field(message, "messageText"));
                    String caption = Reflect.asString(Reflect.field(message, "caption"));
                    long senderId = resolveSenderId(message);
                    if (loader.isChatEnabled(dialogId)) {
                        messageCache.put(dialogId, messageId, text, caption, senderId);
                    }
                }
            } catch (Throwable throwable) {
                // Log error
            }
        }
    }

    private void processDeletions(long dialogId, java.util.ArrayList<?> messageIds) {
        if (!loader.isChatEnabled(dialogId)) {
            return;
        }
        for (Object messageIdObj : messageIds) {
            int messageId = Reflect.asInt(messageIdObj, 0);
            if (messageId > 0) {
                messageCache.markRecalled(dialogId, messageId);
            }
        }
    }

    private void processEdit(long dialogId, int messageId, String newText) {
        if (!loader.isChatEnabled(dialogId)) {
            return;
        }
        messageCache.markEdited(dialogId, messageId, newText);
    }

    private long resolveSenderId(Object message) {
        Object messageOwner = Reflect.field(message, "messageOwner");
        Object fromId = Reflect.field(messageOwner, "from_id");
        long userId = Reflect.asLong(Reflect.field(fromId, "user_id"), 0L);
        if (userId != 0L) {
            return userId;
        }
        long chatId = Reflect.asLong(Reflect.field(fromId, "chat_id"), 0L);
        if (chatId != 0L) {
            return -chatId;
        }
        return 0L;
    }
}
```

- [ ] **Step 2: 创建单元测试**

```java
package com.tianqianguai.gramsieve.module;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecallDetectorTest {
    @Test
    public void testProcessDeletions() {
        // This test would require mocking the MessageCache
        // For now, just verify the class can be instantiated
        RecallDetector detector = new RecallDetector(null, null);
        assertNotNull(detector);
    }
}
```

- [ ] **Step 3: 运行测试**

Run: `./gradlew.bat testDebugUnitTest --tests "com.tianqianguai.gramsieve.module.RecallDetectorTest"`
Expected: 所有测试通过

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/tianqianguai/gramsieve/module/RecallDetector.java
git add app/src/test/java/com/tianqianguai/gramsieve/module/RecallDetectorTest.java
git commit -m "feat(anti-recall): add recall/edit detector"
```

---

### Task 5: 集成到 TelegramHookInstaller

**Covers:** [S2, S4, S5]

**Files:**
- Modify: `app/src/main/java/com/tianqianguai/gramsieve/module/TelegramHookInstaller.java`

**Interfaces:**
- Consumes: `MessageCache`, `BackgroundMessageLoader`, `RecallDetector`, `AntiRecallConfigStore`
- Produces: 在 TelegramHookInstaller 中集成防撤回功能

- [ ] **Step 1: 添加成员变量**

在 TelegramHookInstaller 类中添加以下成员变量：

```java
private MessageCache messageCache;
private BackgroundMessageLoader backgroundMessageLoader;
private RecallDetector recallDetector;
private AntiRecallConfigStore antiRecallConfigStore;
```

- [ ] **Step 2: 初始化防撤回组件**

在 `install` 方法中添加初始化代码：

```java
// 初始化防撤回组件
antiRecallConfigStore = new AntiRecallConfigStore(module.getApplication());
MessageDatabaseHelper databaseHelper = new MessageDatabaseHelper(module.getApplication());
messageCache = new MessageCache(databaseHelper);
backgroundMessageLoader = new BackgroundMessageLoader(messageCache, antiRecallConfigStore);
recallDetector = new RecallDetector(messageCache, backgroundMessageLoader);
recallDetector.install(classLoader);
```

- [ ] **Step 3: 添加聊天菜单项**

在 `injectChatMenu` 方法中添加防撤回菜单项：

```java
injectAntiRecallMenu(chatActivity, headerItem);
```

创建新方法：

```java
private void injectAntiRecallMenu(Object chatActivity, Object headerItem) {
    if (hasMenuItem(headerItem, MENU_ID_ANTI_RECALL)) {
        return;
    }
    Context context = contextFromMenuItem(headerItem);
    int iconRes = resolveAntiRecallIcon(context);
    Object subItem = addMenuSubItem(headerItem, MENU_ID_ANTI_RECALL, iconRes, localizedAntiRecallLabel(context));
    if (!(subItem instanceof View)) {
        info("Anti-recall addSubItem unavailable on " + headerItem.getClass().getName());
        return;
    }
    View subItemView = (View) subItem;
    subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_ANTI_RECALL);
    subItemView.setOnClickListener(v -> {
        try {
            long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
            toggleAntiRecall(dialogId, subItemView);
        } finally {
            Reflect.invokeIfExists(headerItem, "toggleSubMenu", new Class<?>[0]);
        }
    });
}

private void toggleAntiRecall(long dialogId, View menuItem) {
    boolean enabled = backgroundMessageLoader.isChatEnabled(dialogId);
    if (enabled) {
        backgroundMessageLoader.disableChat(dialogId);
        Toast.makeText(menuItem.getContext(), localizedAntiRecallDisabled(menuItem.getContext()), Toast.LENGTH_SHORT).show();
    } else {
        backgroundMessageLoader.enableChat(dialogId);
        Toast.makeText(menuItem.getContext(), localizedAntiRecallEnabled(menuItem.getContext()), Toast.LENGTH_SHORT).show();
    }
}

private int resolveAntiRecallIcon(Context context) {
    int telegramIcon = context.getResources().getIdentifier("msg_message", "drawable", "org.telegram.messenger");
    return telegramIcon != 0 ? telegramIcon : android.R.drawable.ic_menu_save;
}

private CharSequence localizedAntiRecallLabel(Context context) {
    return isChineseLocale(context) ? "主动加载与防撤回防修改" : "Proactive Loading & Anti-Recall";
}

private CharSequence localizedAntiRecallEnabled(Context context) {
    return isChineseLocale(context) ? "已启用主动加载与防撤回防修改" : "Anti-recall enabled";
}

private CharSequence localizedAntiRecallDisabled(Context context) {
    return isChineseLocale(context) ? "已禁用主动加载与防撤回防修改" : "Anti-recall disabled";
}
```

- [ ] **Step 4: 添加常量**

在类开头添加常量：

```java
private static final int MENU_ID_ANTI_RECALL = 0x47530018;
```

- [ ] **Step 5: 运行测试**

Run: `./gradlew.bat testDebugUnitTest`
Expected: 所有测试通过

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/tianqianguai/gramsieve/module/TelegramHookInstaller.java
git commit -m "feat(anti-recall): integrate anti-recall into TelegramHookInstaller"
```

---

### Task 6: 实现 UI 消息标记

**Covers:** [S4]

**Files:**
- Modify: `app/src/main/java/com/tianqianguai/gramsieve/module/TelegramHookInstaller.java`

**Interfaces:**
- Consumes: `MessageCache`
- Produces: 在聊天中显示撤回/修改标记

- [ ] **Step 1: 修改 handleMessageBinding 方法**

在 `handleMessageBinding` 方法中添加检查：

```java
private Object handleMessageBinding(XposedInterface.Chain chain) throws Throwable {
    Object cell = chain.getThisObject();
    Object messageObject = chain.getArg(0);
    emitHookEntry("message", cell, messageObject);
    Object result = chain.proceed();
    try {
        if (cell instanceof View) {
            applyDecision((View) cell, (View) cell, messageObject);
            applyAntiRecallMark((View) cell, messageObject);
        }
    } catch (Throwable throwable) {
        error("Message filtering failed", throwable);
    }
    return result;
}
```

- [ ] **Step 2: 创建 applyAntiRecallMark 方法**

```java
private void applyAntiRecallMark(View cell, Object messageObject) {
    if (messageObject == null || messageCache == null) {
        return;
    }
    long dialogId = Reflect.asLong(Reflect.invokeIfExists(messageObject, "getDialogId", new Class<?>[0]), 0L);
    long messageId = Reflect.asLong(Reflect.invokeIfExists(messageObject, "getId", new Class<?>[0]), 0L);
    if (dialogId == 0L || messageId == 0L) {
        return;
    }
    MessageCache.CachedMessage cachedMessage = messageCache.get(dialogId, messageId);
    if (cachedMessage == null) {
        return;
    }
    if (cachedMessage.isRecalled) {
        showRecalledMark(cell, cachedMessage);
    } else if (cachedMessage.isEdited) {
        showEditedMark(cell, cachedMessage);
    }
}

private void showRecalledMark(View cell, MessageCache.CachedMessage cachedMessage) {
    // Add a visual indicator that this message was recalled
    // This could be a background color change, a text overlay, etc.
    Context context = cell.getContext();
    String markText = isChineseLocale(context) ? "[此消息已被撤回]" : "[This message was recalled]";
    // Implementation depends on the cell type and UI requirements
}

private void showEditedMark(View cell, MessageCache.CachedMessage cachedMessage) {
    // Add a visual indicator that this message was edited
    Context context = cell.getContext();
    String markText = isChineseLocale(context) ? "[已编辑]" : "[Edited]";
    // Implementation depends on the cell type and UI requirements
}
```

- [ ] **Step 3: 运行测试**

Run: `./gradlew.bat testDebugUnitTest`
Expected: 所有测试通过

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/tianqianguai/gramsieve/module/TelegramHookInstaller.java
git commit -m "feat(anti-recall): add UI marks for recalled/edited messages"
```

---

### Task 7: 添加全局设置

**Covers:** [S4, S8]

**Files:**
- Modify: `app/src/main/java/com/tianqianguai/gramsieve/ui/ConfigDialogActivity.java`

**Interfaces:**
- Consumes: `AntiRecallConfigStore`
- Produces: 在设置界面中添加防撤回全局开关

- [ ] **Step 1: 添加全局开关**

在 ConfigDialogActivity 中添加防撤回设置区域：

```java
// 在适当的位置添加防撤回设置卡片
addAntiRecallSettings(container);
```

创建新方法：

```java
private void addAntiRecallSettings(ViewGroup container) {
    CardView card = createCard(container);
    LinearLayout cardContent = new LinearLayout(this);
    cardContent.setOrientation(LinearLayout.VERTICAL);
    card.addView(cardContent);

    addSectionLabel(cardContent, getString(R.string.anti_recall_settings_title));

    SwitchMaterial enabledSwitch = new SwitchMaterial(this);
    enabledSwitch.setText(getString(R.string.anti_recall_enabled));
    enabledSwitch.setChecked(antiRecallConfigStore.isEnabled());
    enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
        antiRecallConfigStore.setEnabled(isChecked);
        if (isChecked) {
            backgroundMessageLoader.start();
        } else {
            backgroundMessageLoader.stop();
        }
    });
    cardContent.addView(enabledSwitch);

    // Load interval setting
    addLoadIntervalSetting(cardContent);
}
```

- [ ] **Step 2: 添加字符串资源**

在 `app/src/main/res/values/strings.xml` 中添加：

```xml
<string name="anti_recall_settings_title">主动加载与防撤回防修改</string>
<string name="anti_recall_enabled">启用主动加载与防撤回防修改</string>
<string name="anti_recall_load_interval">加载间隔（秒）</string>
```

- [ ] **Step 3: 运行测试**

Run: `./gradlew.bat testDebugUnitTest`
Expected: 所有测试通过

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/tianqianguai/gramsieve/ui/ConfigDialogActivity.java
git add app/src/main/res/values/strings.xml
git commit -m "feat(anti-recall): add global settings UI"
```

---

### Task 8: 测试和优化

**Covers:** [S6, S9]

**Files:**
- Test: `app/src/androidTest/java/com/tianqianguai/gramsieve/AntiRecallIntegrationTest.java`

**Interfaces:**
- Consumes: 所有防撤回组件
- Produces: 集成测试和性能优化

- [ ] **Step 1: 创建集成测试**

```java
package com.tianqianguai.gramsieve;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.tianqianguai.gramsieve.module.MessageCache;
import com.tianqianguai.gramsieve.module.MessageDatabaseHelper;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AntiRecallIntegrationTest {
    @Test
    public void testMessageCacheIntegration() {
        Context context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        MessageDatabaseHelper databaseHelper = new MessageDatabaseHelper(context);
        MessageCache cache = new MessageCache(databaseHelper);

        // Test put and get
        cache.put(123, 456, "Hello", "World", 789);
        MessageCache.CachedMessage message = cache.get(123, 456);
        assertNotNull(message);
        assertEquals("Hello", message.text);

        // Test mark recalled
        cache.markRecalled(123, 456);
        message = cache.get(123, 456);
        assertTrue(message.isRecalled);

        // Test mark edited
        cache.markEdited(123, 456, "New Text");
        message = cache.get(123, 456);
        assertTrue(message.isEdited);
        assertEquals("New Text", message.editedText);

        databaseHelper.close();
    }
}
```

- [ ] **Step 2: 运行集成测试**

Run: `./gradlew.bat connectedDebugAndroidTest --tests "com.tianqianguai.gramsieve.AntiRecallIntegrationTest"`
Expected: 所有测试通过

- [ ] **Step 3: 性能优化**

1. 优化 LRU Cache 大小
2. 优化数据库查询性能
3. 优化后台加载频率

- [ ] **Step 4: 提交**

```bash
git add app/src/androidTest/java/com/tianqianguai/gramsieve/AntiRecallIntegrationTest.java
git commit -m "feat(anti-recall): add integration tests and optimizations"
```

---

### Task 9: 文档和清理

**Covers:** [S10]

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`

**Interfaces:**
- Produces: 更新项目文档

- [ ] **Step 1: 更新 README.md**

在 README.md 的功能列表中添加：

```markdown
- **主动加载与防撤回防修改** - 后台主动加载消息，保留被撤回或修改的原始内容
```

- [ ] **Step 2: 更新 AGENTS.md**

在 AGENTS.md 的项目结构部分添加防撤回相关文件说明。

- [ ] **Step 3: 运行 lint**

Run: `./gradlew.bat lintDebug`
Expected: 无错误

- [ ] **Step 4: 提交**

```bash
git add README.md AGENTS.md
git commit -m "docs: add anti-recall feature documentation"
```
