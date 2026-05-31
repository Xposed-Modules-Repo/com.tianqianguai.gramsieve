package com.tianqianguai.gramsieve;

import android.app.Application;

import com.tianqianguai.gramsieve.config.AppLocaleManager;
import com.tianqianguai.gramsieve.config.ModuleConfigStore;
import com.tianqianguai.gramsieve.config.ModuleLogger;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class GramSieveApplication extends Application implements XposedServiceHelper.OnServiceListener {
    private static volatile XposedService xposedService;

    @Override
    public void onCreate() {
        super.onCreate();
        ModuleLogger.init(this);
        AppLocaleManager.apply(this, ModuleConfigStore.load(getSharedPreferences(
                ModuleConfigStore.PREFS_NAME,
                MODE_PRIVATE
        )).appLanguageTag);
        XposedServiceHelper.registerListener(this);
    }

    public static XposedService getXposedService() {
        return xposedService;
    }

    @Override
    public void onServiceBind(XposedService service) {
        xposedService = service;
        ModuleConfigStore.syncToRemote(this, service);
    }

    @Override
    public void onServiceDied(XposedService service) {
        if (xposedService == service) {
            xposedService = null;
        }
    }
}
