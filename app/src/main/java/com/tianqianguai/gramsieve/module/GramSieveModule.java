package com.tianqianguai.gramsieve.module;

import android.app.Application;
import android.content.pm.ApplicationInfo;

import com.tianqianguai.gramsieve.config.ModuleLogger;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class GramSieveModule extends XposedModule {
    private static final String TAG = "GramSieve";

    private final TelegramHookInstaller hookInstaller = new TelegramHookInstaller(this);

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        ModuleLogger.setHookProcessMode(this);
        ModuleLogger.lifecycle(TAG, "Module loaded by " + getFrameworkName() + " " + getFrameworkVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!shouldHandleTelegramPackage(param.getPackageName(), param.isFirstPackage())) {
            return;
        }
        ModuleLogger.lifecycle(TAG, "Telegram package loaded; waiting for app class loader");
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!shouldHandleTelegramPackage(param.getPackageName(), param.isFirstPackage())) {
            return;
        }
        try {
            hookInstaller.install(param.getClassLoader(), param.getApplicationInfo());
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_LIFECYCLE, TAG, "Failed to install Telegram hooks", throwable);
        }
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        String packageName = hookInstaller.targetPackageName();
        ModuleLogger.lifecycle(TAG, "Hot reload preparing package=" + packageName);
        if (!hookInstaller.prepareForHotReload()) {
            ModuleLogger.warn(ModuleLogger.CAT_LIFECYCLE, TAG,
                    "Hot reload rejected because the old generation did not quiesce");
            return false;
        }
        param.setSavedInstanceState(packageName);
        ModuleLogger.lifecycle(TAG, "Hot reload old generation quiesced package=" + packageName);
        return true;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        ModuleLogger.setHookProcessMode(this);
        List<XposedInterface.HookHandle> oldHandles = param.getOldHookHandles();
        Application application = currentApplication();
        ApplicationInfo applicationInfo = application == null ? null : application.getApplicationInfo();
        String packageName = TelegramPackages.resolveReloadPackage(
                applicationInfo == null ? null : applicationInfo.packageName,
                param.getSavedInstanceState(),
                param.getProcessName()
        );
        if (applicationInfo == null && TelegramPackages.isSupported(packageName)) {
            applicationInfo = new ApplicationInfo();
            applicationInfo.packageName = packageName;
        }
        ClassLoader classLoader = resolveReloadClassLoader(oldHandles, application);
        if (!TelegramPackages.isSupported(packageName) || classLoader == null) {
            int removed = unhookOldHandles(oldHandles);
            ModuleLogger.warn(ModuleLogger.CAT_LIFECYCLE, TAG,
                    "Hot reload could not resolve Telegram target package=" + packageName
                            + " process=" + param.getProcessName() + " removedOldHooks=" + removed);
            return;
        }

        ModuleLogger.lifecycle(TAG, "Hot reload installing new generation package=" + packageName
                + " process=" + param.getProcessName() + " oldHooks=" + oldHandles.size());
        try {
            hookInstaller.install(classLoader, applicationInfo);
            hookInstaller.rebindVisibleHostUi(classLoader);
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_LIFECYCLE, TAG,
                    "Hot reload new generation installation failed package=" + packageName,
                    throwable);
        } finally {
            int removed = unhookOldHandles(oldHandles);
            ModuleLogger.lifecycle(TAG, "Hot reload new generation active package=" + packageName
                    + " process=" + param.getProcessName() + " retiredOldHooks=" + removed
                    + " suppliedOldHooks=" + oldHandles.size());
        }
    }

    static boolean shouldHandleTelegramPackage(String packageName, boolean firstPackage) {
        return TelegramPackages.shouldHandle(packageName, firstPackage);
    }

    private static ClassLoader resolveReloadClassLoader(List<XposedInterface.HookHandle> oldHandles,
                                                         Application application) {
        if (oldHandles != null) {
            for (XposedInterface.HookHandle handle : oldHandles) {
                try {
                    ClassLoader classLoader = handle.getExecutable().getDeclaringClass().getClassLoader();
                    if (classLoader != null) {
                        return classLoader;
                    }
                } catch (RuntimeException ignored) {
                    // Try the live Application class loader below.
                }
            }
        }
        if (application != null) {
            return application.getClassLoader();
        }
        return Thread.currentThread().getContextClassLoader();
    }

    private static int unhookOldHandles(List<XposedInterface.HookHandle> oldHandles) {
        if (oldHandles == null) {
            return 0;
        }
        int removed = 0;
        for (XposedInterface.HookHandle handle : oldHandles) {
            try {
                handle.unhook();
                removed++;
            } catch (IllegalStateException ignored) {
                // A matching API-102 hook id already replaced this handle atomically.
            } catch (RuntimeException exception) {
                ModuleLogger.warn(ModuleLogger.CAT_LIFECYCLE, TAG,
                        "Failed to retire old hook " + handle.getExecutable() + ": "
                                + exception.getClass().getSimpleName());
            }
        }
        return removed;
    }

    private static Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method method = activityThread.getDeclaredMethod("currentApplication");
            Object application = method.invoke(null);
            return application instanceof Application ? (Application) application : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
