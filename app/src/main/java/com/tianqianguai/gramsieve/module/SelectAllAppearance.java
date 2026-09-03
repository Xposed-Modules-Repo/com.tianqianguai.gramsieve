package com.tianqianguai.gramsieve.module;

/** Pure selection helpers for the injected Select All action. */
final class SelectAllAppearance {
    private SelectAllAppearance() {
    }

    static String label(CharSequence telegramLabel, boolean chineseFallback) {
        String localized = telegramLabel == null ? "" : telegramLabel.toString().trim();
        if (!localized.isEmpty()) {
            return localized;
        }
        return chineseFallback ? "全选" : "Select All";
    }

    static int foregroundColor(
            Integer peerColor,
            Integer telegramThemeColor,
            Integer platformTextColor,
            boolean nightMode
    ) {
        if (visible(peerColor)) {
            return peerColor;
        }
        if (visible(telegramThemeColor)) {
            return telegramThemeColor;
        }
        if (visible(platformTextColor)) {
            return platformTextColor;
        }
        return nightMode ? 0xFFFFFFFF : 0xFF000000;
    }

    private static boolean visible(Integer color) {
        return color != null && ((color >>> 24) & 0xFF) != 0;
    }
}
