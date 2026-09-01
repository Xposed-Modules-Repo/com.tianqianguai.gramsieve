package com.tianqianguai.gramsieve.module;

/** Package-name contract for the supported official Telegram Android builds. */
final class TelegramPackages {
    static final String PLAY_PACKAGE = "org.telegram.messenger";
    static final String WEB_PACKAGE = "org.telegram.messenger.web";

    private TelegramPackages() {
    }

    static boolean isSupported(String packageName) {
        return PLAY_PACKAGE.equals(packageName) || WEB_PACKAGE.equals(packageName);
    }

    static boolean shouldHandle(String packageName, boolean firstPackage) {
        return firstPackage && isSupported(packageName);
    }

    static String resolveResourcePackage(String packageName) {
        return isSupported(packageName) ? packageName : PLAY_PACKAGE;
    }
}
