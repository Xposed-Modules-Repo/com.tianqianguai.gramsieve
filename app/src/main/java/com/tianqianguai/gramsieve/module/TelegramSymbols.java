package com.tianqianguai.gramsieve.module;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** Names recovered from archived Play APKs; unmatched hosts keep their native names. */
final class TelegramSymbols {
    private static final String APK_SHA256 =
            "b923544110654a2c0ae4e0d5829c1606b9f8807f76088fb4142622a93e408f8e";
    private static volatile TelegramSymbols active;
    private final Map<String, String> classes = new HashMap<>();
    private final Map<String, String> originalClasses = new HashMap<>();
    private final Map<String, String> fields = new HashMap<>();
    private final Map<String, String> originalFields = new HashMap<>();
    private final Map<String, String> originalMethods = new HashMap<>();

    TelegramSymbols(InputStream input) throws java.io.IOException {
        if (input == null) throw new java.io.IOException("Telegram symbol map missing");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] part = line.split("\t");
                if (part[0].equals("C") && part.length == 3) {
                    classes.put(part[1], part[2]);
                    originalClasses.put(part[2], part[1]);
                } else if (part[0].equals("F") && part.length == 4) {
                    fields.put(part[1] + "#" + part[2], part[3]);
                    originalFields.put(part[1] + "#" + part[3], part[2]);
                } else if (part[0].equals("M") && part.length == 5) {
                    originalMethods.put(part[1] + "#" + part[3] + part[4], part[2]);
                } else {
                    throw new java.io.IOException("Invalid Telegram symbol map row");
                }
            }
        }
    }

    static String initialize(String apkPath) throws Exception {
        active = null;
        if (apkPath == null) return "native";
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(apkPath)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        String build = buildForHash(hex.toString());
        if (build == null) return "native";
        active = new TelegramSymbols(TelegramSymbols.class.getResourceAsStream("/telegram-" + build + ".tsv"));
        return "Play-" + build;
    }

    static String buildForHash(String hash) {
        if (APK_SHA256.equals(hash)) return "70862";
        if ("6b3565f20af6681172b49cf84915818fb575ace8295cd1de08c4b9a2b3985f5e".equals(hash)) return "70892";
        return null;
    }

    static Class<?> loadClass(ClassLoader loader, String name) throws ClassNotFoundException {
        TelegramSymbols symbols = active;
        return loader.loadClass(symbols == null ? name : symbols.classes.getOrDefault(name, name));
    }

    static Class<?> forName(String name, boolean initialize, ClassLoader loader) throws ClassNotFoundException {
        TelegramSymbols symbols = active;
        return Class.forName(symbols == null ? name : symbols.classes.getOrDefault(name, name), initialize, loader);
    }

    static String name(Class<?> type) {
        TelegramSymbols symbols = active;
        return symbols == null ? type.getName() : symbols.originalClasses.getOrDefault(type.getName(), type.getName());
    }

    static String simpleName(Class<?> type) {
        String name = name(type);
        if (name.equals(type.getName())) return type.getSimpleName();
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }

    static String name(Member member) {
        TelegramSymbols symbols = active;
        if (symbols == null) return member.getName();
        if (member instanceof Field) return symbols.originalFields.getOrDefault(
                member.getDeclaringClass().getName() + "#" + member.getName(), member.getName());
        if (!(member instanceof Method)) return member.getName();
        Method method = (Method) member;
        return symbols.methodName(method);
    }

    String methodName(Method method) {
        Class<?> type = method.getDeclaringClass();
        String suffix = "#" + method.getName() + descriptor(method);
        while (type != null) {
            String original = originalMethods.get(type.getName() + suffix);
            if (original != null) return original;
            type = type.getSuperclass();
        }
        return method.getName();
    }

    static Field declaredField(Class<?> type, String name) throws NoSuchFieldException {
        TelegramSymbols symbols = active;
        return type.getDeclaredField(symbols == null ? name
                : symbols.fields.getOrDefault(type.getName() + "#" + name, name));
    }

    static Method declaredMethod(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        TelegramSymbols symbols = active;
        if (symbols != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (symbols.methodName(method).equals(name)
                        && java.util.Arrays.equals(method.getParameterTypes(), parameters)) return method;
            }
        }
        return type.getDeclaredMethod(name, parameters);
    }

    static Method uniqueMethod(Class<?> type, String name) throws NoSuchMethodException {
        Method found = null;
        for (Method method : type.getDeclaredMethods()) {
            if (!name(method).equals(name)) continue;
            if (found != null) throw new NoSuchMethodException("Ambiguous " + name);
            found = method;
        }
        if (found == null) throw new NoSuchMethodException(type.getName() + "." + name);
        found.setAccessible(true);
        return found;
    }

    private static String descriptor(Method method) {
        StringBuilder result = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) result.append(descriptor(parameter));
        return result.append(')').append(descriptor(method.getReturnType())).toString();
    }

    private static String descriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        return "D";
    }
}
