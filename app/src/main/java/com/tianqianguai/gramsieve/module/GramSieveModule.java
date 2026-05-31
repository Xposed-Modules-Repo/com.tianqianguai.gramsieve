package com.tianqianguai.gramsieve.module;

import com.tianqianguai.gramsieve.config.ModuleLogger;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class GramSieveModule extends XposedModule {
    private static final String TELEGRAM_PACKAGE = "org.telegram.messenger";
    private static final String TAG = "GramSieve";

    private final TelegramHookInstaller hookInstaller = new TelegramHookInstaller(this);

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        ModuleLogger.setHookProcessMode(this);
        ModuleLogger.lifecycle(TAG, "Module loaded by " + getFrameworkName() + " " + getFrameworkVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TELEGRAM_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        ModuleLogger.lifecycle(TAG, "Telegram package loaded; waiting for app class loader");
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TELEGRAM_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        try {
            hookInstaller.install(param.getClassLoader(), param.getApplicationInfo());
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_LIFECYCLE, TAG, "Failed to install Telegram hooks", throwable);
        }
    }
}
