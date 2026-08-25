package com.tianqianguai.gramsieve.module;

/** Best-effort TL serialization for the optional raw_message_blob column. */
final class TelegramMessageSerializer {
    private TelegramMessageSerializer() {
    }

    static byte[] serialize(Object messageLike) {
        if (messageLike == null) {
            return null;
        }
        Object owner = Reflect.field(messageLike, "messageOwner");
        Object message = owner != null ? owner : messageLike;
        Object serializedData = null;
        try {
            ClassLoader classLoader = message.getClass().getClassLoader();
            if (classLoader == null) {
                return null;
            }
            Class<?> serializedDataClass = classLoader.loadClass("org.telegram.tgnet.SerializedData");
            java.lang.reflect.Constructor<?> constructor = serializedDataClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            serializedData = constructor.newInstance();
            Reflect.invokeIfExists(message, "serializeToStream", null, serializedData);
            Object value = Reflect.invokeIfExists(serializedData, "toByteArray", new Class<?>[0]);
            if (!(value instanceof byte[]) || ((byte[]) value).length == 0) {
                return null;
            }
            return ((byte[]) value).clone();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (serializedData != null) {
                Reflect.invokeIfExists(serializedData, "cleanup", new Class<?>[0]);
            }
        }
    }
}
