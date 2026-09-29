package com.tianqianguai.gramsieve.config;

import android.app.LocaleManager;
import android.content.Context;
import android.os.LocaleList;

import com.tianqianguai.gramsieve.core.FilterConfig;

public final class AppLocaleManager {
    private AppLocaleManager() {
    }

    public static void apply(Context context, String appLanguageTag) {
        if (context == null) {
            return;
        }
        LocaleManager localeManager = context.getSystemService(LocaleManager.class);
        if (localeManager == null) {
            return;
        }
        String normalizedTag = FilterConfig.normalizeAppLanguageTag(appLanguageTag);
        LocaleList targetLocales = normalizedTag.isEmpty()
                ? LocaleList.getEmptyLocaleList()
                : LocaleList.forLanguageTags(normalizedTag);
        LocaleList currentLocales = localeManager.getApplicationLocales();
        if (currentLocales != null && currentLocales.toLanguageTags().equals(targetLocales.toLanguageTags())) {
            return;
        }
        localeManager.setApplicationLocales(targetLocales);
    }
}
