package com.tianqianguai.gramsieve.config;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.tianqianguai.gramsieve.core.ModuleConflictDetector;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runtime-probe fallback that reads LSPosed's module registry without requesting root. */
public final class LSPosedModuleStateReader {
    private static final String DATABASE_PATH = "/data/adb/lspd/config/modules_config.db";

    private LSPosedModuleStateReader() {
    }

    public static Set<ModuleConflictDetector.KnownModule> scanRegisteredWithoutRoot() {
        File databaseFile = new File(DATABASE_PATH);
        if (!databaseFile.isFile() || !databaseFile.canRead()) {
            return Collections.emptySet();
        }
        List<String> knownPackages = knownPackageNames();
        String placeholders = String.join(",", Collections.nCopies(knownPackages.size(), "?"));
        SQLiteDatabase.OpenParams openParams = new SQLiteDatabase.OpenParams.Builder()
                .addOpenFlags(SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                .build();
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(databaseFile, openParams);
             Cursor cursor = database.rawQuery(
                     "SELECT module_pkg_name FROM modules WHERE module_pkg_name IN ("
                             + placeholders + ")",
                     knownPackages.toArray(new String[0])
             )) {
            Set<String> packages = new HashSet<>();
            int packageIndex = cursor.getColumnIndexOrThrow("module_pkg_name");
            while (cursor.moveToNext()) {
                packages.add(cursor.getString(packageIndex));
            }
            return ModuleConflictDetector.identifyInstalledModules(packages);
        } catch (RuntimeException exception) {
            return Collections.emptySet();
        }
    }

    private static List<String> knownPackageNames() {
        List<String> packages = new ArrayList<>();
        for (ModuleConflictDetector.KnownModule module : ModuleConflictDetector.KnownModule.values()) {
            packages.addAll(module.packageNames);
        }
        return packages;
    }
}
