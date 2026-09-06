package com.tianqianguai.gramsieve.module;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class Reflect {
    private static final ConcurrentMap<Class<?>, ConcurrentMap<String, FieldResolution>> DECLARED_FIELD_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, ConcurrentMap<MethodKey, MethodResolution>> METHOD_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, ConcurrentMap<MethodKey, StrictMethodResolution>> STRICT_METHOD_CACHE =
            new ConcurrentHashMap<>();
    private static final FieldResolution MISSING_FIELD = new FieldResolution(null);
    private static final Class<?>[] EMPTY_CLASS_TYPES = new Class<?>[0];
    private static final Object[] EMPTY_ARGUMENTS = new Object[0];

    private Reflect() {
    }

    static Method method(Class<?> type, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        if (type == null || parameterTypes == null) {
            Method method = type.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        }

        MethodKey key = new MethodKey(name, parameterTypes, EMPTY_ARGUMENTS);
        StrictMethodResolution resolution = strictMethodsFor(type).computeIfAbsent(
                key,
                ignored -> resolveStrictMethod(type, key)
        );
        if (resolution.method == null) {
            throw new NoSuchMethodException(resolution.errorMessage);
        }
        return resolution.method;
    }

    static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke " + method, e);
        }
    }

    static Object invokeIfExists(Object target, String name, Class<?>[] parameterTypes, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            Method method = findMethod(target.getClass(), name, parameterTypes, args);
            if (method == null) {
                return null;
            }
            return method.invoke(target, args);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    static Object invokeStatic(Class<?> type, String name, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = findMethod(type, name, parameterTypes, args);
            if (method == null) {
                return null;
            }
            return method.invoke(null, args);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    static Object field(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> current = target.getClass();
        while (current != null) {
            Field field = declaredField(current, name);
            if (field == null) {
                current = current.getSuperclass();
                continue;
            }
            try {
                return field.get(target);
            } catch (ReflectiveOperationException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    static void setField(Object target, String name, Object value) {
        if (target == null) {
            return;
        }
        Class<?> current = target.getClass();
        while (current != null) {
            Field field = declaredField(current, name);
            if (field == null) {
                current = current.getSuperclass();
                continue;
            }
            try {
                field.set(target, value);
                return;
            } catch (ReflectiveOperationException ignored) {
                current = current.getSuperclass();
            }
        }
    }

    static Object staticField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            Field field = declaredField(current, name);
            if (field == null) {
                current = current.getSuperclass();
                continue;
            }
            try {
                return field.get(null);
            } catch (ReflectiveOperationException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    static long asLong(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return fallback;
    }

    static int asInt(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return fallback;
    }

    static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] parameterTypes, Object[] args) {
        if (type == null) {
            return null;
        }

        Class<?>[] requestedTypes = parameterTypes == null
                ? EMPTY_CLASS_TYPES
                : parameterTypes.clone();
        Object[] lookupArgs = args == null ? EMPTY_ARGUMENTS : args.clone();
        MethodKey key = new MethodKey(name, requestedTypes, lookupArgs);
        MethodResolution resolution = methodsFor(type).computeIfAbsent(
                key,
                ignored -> resolveMethod(type, key, lookupArgs)
        );
        return resolution.method;
    }

    private static Field declaredField(Class<?> type, String name) {
        return fieldsFor(type).computeIfAbsent(name, ignored -> resolveDeclaredField(type, name)).field;
    }

    private static FieldResolution resolveDeclaredField(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return new FieldResolution(field);
        } catch (NoSuchFieldException ignored) {
            return MISSING_FIELD;
        }
    }

    private static MethodResolution resolveMethod(Class<?> type, MethodKey key, Object[] args) {
        Method exact = findDeclaredMethod(type, key.name, key.parameterTypes);
        if (exact != null) {
            return new MethodResolution(exact);
        }
        return new MethodResolution(findCompatibleMethod(type, key.name, args));
    }

    private static StrictMethodResolution resolveStrictMethod(Class<?> type, MethodKey key) {
        try {
            Method method = type.getDeclaredMethod(key.name, key.parameterTypes);
            method.setAccessible(true);
            return new StrictMethodResolution(method, null);
        } catch (NoSuchMethodException exception) {
            return new StrictMethodResolution(null, exception.getMessage());
        }
    }

    private static ConcurrentMap<String, FieldResolution> fieldsFor(Class<?> type) {
        return DECLARED_FIELD_CACHE.computeIfAbsent(type, ignored -> new ConcurrentHashMap<>());
    }

    private static ConcurrentMap<MethodKey, MethodResolution> methodsFor(Class<?> type) {
        return METHOD_CACHE.computeIfAbsent(type, ignored -> new ConcurrentHashMap<>());
    }

    private static ConcurrentMap<MethodKey, StrictMethodResolution> strictMethodsFor(Class<?> type) {
        return STRICT_METHOD_CACHE.computeIfAbsent(type, ignored -> new ConcurrentHashMap<>());
    }

    private static Method findDeclaredMethod(Class<?> type, String name, Class<?>[] parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Object[] args) {
        Class<?> current = type;
        while (current != null) {
            Method best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Method candidate : current.getDeclaredMethods()) {
                if (!candidate.getName().equals(name) || candidate.getParameterCount() != args.length) {
                    continue;
                }
                int score = compatibilityScore(candidate.getParameterTypes(), args);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (best != null && bestScore >= 0) {
                best.setAccessible(true);
                return best;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static int compatibilityScore(Class<?>[] parameterTypes, Object[] args) {
        int score = 0;
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = wrap(parameterTypes[i]);
            Object arg = args[i];
            if (arg == null) {
                if (parameterTypes[i].isPrimitive()) {
                    return -1;
                }
                continue;
            }
            Class<?> argType = wrap(arg.getClass());
            if (parameterType.equals(argType)) {
                score += 4;
                continue;
            }
            if (parameterType.isAssignableFrom(argType)) {
                score += 2;
                continue;
            }
            return -1;
        }
        return score;
    }

    private static Class<?> wrap(Class<?> type) {
        if (type == null || !type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        return Void.class;
    }

    private static final class FieldResolution {
        private final Field field;

        private FieldResolution(Field field) {
            this.field = field;
        }
    }

    private static final class MethodResolution {
        private final Method method;

        private MethodResolution(Method method) {
            this.method = method;
        }
    }

    private static final class StrictMethodResolution {
        private final Method method;
        private final String errorMessage;

        private StrictMethodResolution(Method method, String errorMessage) {
            this.method = method;
            this.errorMessage = errorMessage;
        }
    }

    private static final class MethodKey {
        private final String name;
        private final Class<?>[] parameterTypes;
        private final Class<?>[] argumentTypes;
        private final int hashCode;

        private MethodKey(String name, Class<?>[] parameterTypes, Object[] args) {
            this.name = name;
            this.parameterTypes = parameterTypes.clone();
            this.argumentTypes = argumentTypes(args);
            this.hashCode = calculateHashCode(name, this.parameterTypes, this.argumentTypes);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MethodKey)) {
                return false;
            }
            MethodKey that = (MethodKey) other;
            return sameTypes(parameterTypes, that.parameterTypes)
                    && sameTypes(argumentTypes, that.argumentTypes)
                    && (name == null ? that.name == null : name.equals(that.name));
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        private static Class<?>[] argumentTypes(Object[] args) {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] == null ? null : args[i].getClass();
            }
            return types;
        }

        private static boolean sameTypes(Class<?>[] left, Class<?>[] right) {
            if (left.length != right.length) {
                return false;
            }
            for (int i = 0; i < left.length; i++) {
                if (left[i] != right[i]) {
                    return false;
                }
            }
            return true;
        }

        private static int calculateHashCode(String name, Class<?>[] parameterTypes, Class<?>[] argumentTypes) {
            int result = name == null ? 0 : name.hashCode();
            result = 31 * result + typesHashCode(parameterTypes);
            return 31 * result + typesHashCode(argumentTypes);
        }

        private static int typesHashCode(Class<?>[] types) {
            int result = 1;
            for (Class<?> type : types) {
                result = 31 * result + System.identityHashCode(type);
            }
            return result;
        }
    }
}
