package com.tianqianguai.gramsieve.module;

import com.tianqianguai.gramsieve.config.ModuleLogger;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

final class SerializedMessageStore implements MessageStore, AutoCloseable {
    private static final String TAG = "SerializedMessageStore";

    private final MessageStore delegate;
    private final ExecutorService executor;
    private volatile Thread storageThread;

    SerializedMessageStore(MessageStore delegate) {
        this(delegate, "GramSieve-MessageStore");
    }

    SerializedMessageStore(MessageStore delegate, String threadName) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate == null");
        }
        this.delegate = delegate;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(() -> {
                storageThread = Thread.currentThread();
                runnable.run();
            }, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void insertMessage(MessageCache.CachedMessage message) {
        insertMessage(message.accountId, message);
    }

    @Override
    public void insertMessage(int accountId, MessageCache.CachedMessage message) {
        MessageCache.CachedMessage snapshot = snapshot(message);
        enqueueWrite(() -> delegate.insertMessage(accountId, snapshot));
    }

    @Override
    public void insertOrReplaceFresh(MessageCache.CachedMessage message) {
        insertOrReplaceFresh(message.accountId, message);
    }

    @Override
    public void insertOrReplaceFresh(int accountId, MessageCache.CachedMessage message) {
        MessageCache.CachedMessage snapshot = snapshot(message);
        enqueueWrite(() -> delegate.insertOrReplaceFresh(accountId, snapshot));
    }

    @Override
    public void insertEditHistory(MessageCache.CachedMessage message) {
        insertEditHistory(message.accountId, message);
    }

    @Override
    public void insertEditHistory(int accountId, MessageCache.CachedMessage message) {
        MessageCache.CachedMessage snapshot = snapshot(message);
        enqueueWrite(() -> delegate.insertEditHistory(accountId, snapshot));
    }

    @Override
    public void updateMessage(MessageCache.CachedMessage message) {
        updateMessage(message.accountId, message);
    }

    @Override
    public void updateMessage(int accountId, MessageCache.CachedMessage message) {
        MessageCache.CachedMessage snapshot = snapshot(message);
        enqueueWrite(() -> delegate.updateMessage(accountId, snapshot));
    }

    @Override
    public void deleteMessage(long dialogId, long messageId) {
        deleteMessage(0, dialogId, messageId);
    }

    @Override
    public void deleteMessage(int accountId, long dialogId, long messageId) {
        enqueueWrite(() -> delegate.deleteMessage(accountId, dialogId, messageId));
    }

    @Override
    public void deleteDialog(long dialogId) {
        deleteDialog(0, dialogId);
    }

    @Override
    public void deleteDialog(int accountId, long dialogId) {
        enqueueWrite(() -> delegate.deleteDialog(accountId, dialogId));
    }

    @Override
    public MessageCache.CachedMessage getMessage(long dialogId, long messageId) {
        return getMessage(0, dialogId, messageId);
    }

    @Override
    public MessageCache.CachedMessage getMessage(int accountId, long dialogId, long messageId) {
        return submitRead(() -> delegate.getMessage(accountId, dialogId, messageId), null);
    }

    @Override
    public List<MessageCache.CachedMessage> getRecalledMessages(long dialogId) {
        return getRecalledMessages(0, dialogId);
    }

    @Override
    public List<MessageCache.CachedMessage> getRecalledMessages(int accountId, long dialogId) {
        return submitRead(() -> delegate.getRecalledMessages(accountId, dialogId), Collections.emptyList());
    }

    @Override
    public List<MessageCache.CachedMessage> getEditedMessages(long dialogId) {
        return getEditedMessages(0, dialogId);
    }

    @Override
    public List<MessageCache.CachedMessage> getEditedMessages(int accountId, long dialogId) {
        return submitRead(() -> delegate.getEditedMessages(accountId, dialogId), Collections.emptyList());
    }

    @Override
    public List<MessageCache.CachedMessage> getEditHistory(long dialogId, long messageId) {
        return getEditHistory(0, dialogId, messageId);
    }

    @Override
    public List<MessageCache.CachedMessage> getEditHistory(int accountId, long dialogId, long messageId) {
        return submitRead(() -> delegate.getEditHistory(accountId, dialogId, messageId), Collections.emptyList());
    }

    @Override
    public void close() {
        prepareForHotReload();
    }

    boolean prepareForHotReload() {
        boolean stopped = ExecutorShutdown.gracefulThenNow(executor, 4_000L);
        if (delegate instanceof AutoCloseable) {
            try {
                ((AutoCloseable) delegate).close();
            } catch (Exception exception) {
                ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG,
                        "delegate close failed: " + exception.getClass().getSimpleName());
                stopped = false;
            }
        }
        storageThread = null;
        return stopped;
    }

    private void enqueueWrite(WriteTask task) {
        if (Thread.currentThread() == storageThread) {
            runWrite(task);
            return;
        }
        try {
            executor.execute(() -> runWrite(task));
        } catch (RejectedExecutionException exception) {
            logFailure("Message store write rejected", exception);
        }
    }

    private void runWrite(WriteTask task) {
        try {
            task.run();
        } catch (Throwable throwable) {
            logFailure("Message store write failed", throwable);
        }
    }

    private <T> T submitRead(Callable<T> task, T fallback) {
        if (Thread.currentThread() == storageThread) {
            return callRead(task, fallback);
        }
        Future<T> future;
        try {
            future = executor.submit(() -> callRead(task, fallback));
        } catch (RejectedExecutionException exception) {
            logFailure("Message store read rejected", exception);
            return fallback;
        }
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logFailure("Message store read interrupted", exception);
            return fallback;
        } catch (ExecutionException exception) {
            logFailure("Message store read failed", exception);
            return fallback;
        }
    }

    private <T> T callRead(Callable<T> task, T fallback) {
        try {
            return task.call();
        } catch (Throwable throwable) {
            logFailure("Message store read failed", throwable);
            return fallback;
        }
    }

    private static MessageCache.CachedMessage snapshot(MessageCache.CachedMessage message) {
        return new MessageCache.CachedMessage(
                message.accountId,
                message.dialogId,
                message.messageId,
                message.senderId,
                message.text,
                message.caption,
                message.timestamp,
                message.mediaType,
                message.mediaId,
                message.cachedMediaPath,
                message.isRecalled,
                message.isEdited,
                message.editedText,
                message.rawMessageBlob
        );
    }

    private static void logFailure(String message, Throwable throwable) {
        ModuleLogger.error(ModuleLogger.CAT_ERROR, TAG, message, throwable);
    }

    private interface WriteTask {
        void run() throws Exception;
    }
}
