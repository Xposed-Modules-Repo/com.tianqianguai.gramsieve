package com.tianqianguai.gramsieve.module;

import java.lang.reflect.Executable;

/** Stable API-102 hook ids allow a new module generation to replace old hooks atomically. */
final class HookIdentity {
    private static final String PREFIX = "gramsieve/";
    private static final String PROJECT_PACKAGE = "com.tianqianguai.gramsieve.";

    private HookIdentity() {
    }

    static String forCaller(String component, Executable executable) {
        if (component == null || component.isBlank() || executable == null) {
            throw new IllegalArgumentException("component and executable are required");
        }
        String caller = "unknown";
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String className = frame.getClassName();
            if (!className.startsWith(PROJECT_PACKAGE)
                    || className.equals(HookIdentity.class.getName())
                    || "hook".equals(frame.getMethodName())) {
                continue;
            }
            caller = className + "#" + frame.getMethodName();
            break;
        }
        StringBuilder signature = new StringBuilder()
                .append(executable.getDeclaringClass().getName())
                .append('#')
                .append(executable.getName())
                .append('(');
        Class<?>[] parameterTypes = executable.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                signature.append(',');
            }
            signature.append(parameterTypes[i].getName());
        }
        return PREFIX + component + '/' + caller + '/' + signature.append(')');
    }
}
