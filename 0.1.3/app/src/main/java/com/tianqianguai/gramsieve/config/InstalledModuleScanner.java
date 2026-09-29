package com.tianqianguai.gramsieve.config;

import android.content.Context;
import android.content.pm.PackageManager;

import com.tianqianguai.gramsieve.core.ModuleConflictDetector;

import java.util.HashSet;
import java.util.Set;

/** Finds known module application packages visible to the GramSieve app. */
public final class InstalledModuleScanner {
    private InstalledModuleScanner() {
    }

    public static Set<ModuleConflictDetector.KnownModule> scan(Context context) {
        if (context == null) {
            return ModuleConflictDetector.identifyInstalledModules(null);
        }
        Set<String> installedPackages = new HashSet<>();
        PackageManager packageManager = context.getPackageManager();
        for (ModuleConflictDetector.KnownModule module : ModuleConflictDetector.KnownModule.values()) {
            for (String packageName : module.packageNames) {
                if (isInstalled(packageManager, packageName)) {
                    installedPackages.add(packageName);
                }
            }
        }
        return ModuleConflictDetector.identifyInstalledModules(installedPackages);
    }

    @SuppressWarnings("deprecation")
    private static boolean isInstalled(PackageManager packageManager, String packageName) {
        try {
            packageManager.getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException | SecurityException ignored) {
            return false;
        }
    }
}
