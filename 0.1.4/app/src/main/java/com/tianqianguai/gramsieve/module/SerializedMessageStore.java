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
        MessageCache.CachedMessage snapshot = snapshot(message);
        enqueueWrite(() -> delegate.insertMessage(snapshot));
    }

    @Override
    public void insertOrReplaceFresh(MessageCache.CachedMessage message) {
        MessageCache.CachedMessage snapshot = snapshot(message);
        enqueueWrite(() -> delegate.insertOrReplaceFresh(snapshot));
    }

    @Override
    public void insertEditHistory(MessageCache.CachedMessage message) {
        MessageCache.CachedMessage snapshot = snapshot(message);
        enqueueWrite(() -> delegate.insertEditHistory(snapshot));
    }

    @Override
    public void updateMessage(MessageCache.CachedMessage message) {
        MessageCache.CachedMessage snapshot = snapshot(message);
        enqueueWrite(() -> delegate.updateMessage(snapshot));
    }

    @Override
    public void deleteMessage(long dialogId, long messageId) {
        enqueueWrite(() -> delegate.deleteMessage(dialogId, messageId));
    }

    @Override
    public void deleteDialog(long dialogId) {
        enqueueWrite(() -> delegate.deleteDialog(dialogId));
    }

    @Override
    public MessageCache.CachedMessage getMessage(long dialogId, long messageId) {
        return submitRead(() -> delegate.getMessage(dialogId, messageId), null);
    }

    @Override
    public List<MessageCache.CachedMessage> getRecalledMessages(long dialogId) {
        return submitRead(() -> delegate.getRecalledMessages(dialogId), Collections.emptyList());
    }

    @Override
    public List<MessageCache.CachedMessage> getEditedMessages(long dialogId) {
        return submitRead(() -> delegate.getEditedMessages(dialogId), Collections.emptyList());
    }

    @Override
    public List<MessageCache.CachedMessage> getEditHistory(long dialogId, long messageId) {
        return submitRead(() -> delegate.getEditHistory(dialogId, messageId), Collections.emptyList());
    }

    @Override
    public void close() {
        executor.shutdown();
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
                message.editedText
        );
    }

    private static void logFailure(String message, Throwable throwable) {
        ModuleLogger.error(ModuleLogger.CAT_ERROR, TAG, message, throwable);
    }

    private interface WriteTask {
        void run() throws Exception;
    }
}
