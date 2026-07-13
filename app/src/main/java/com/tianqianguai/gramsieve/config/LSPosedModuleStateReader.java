package com.tianqianguai.gramsieve.config;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Process;

import com.tianqianguai.gramsieve.core.ModuleConflictDetector;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Reads LSPosed module enablement and scope from its private database without modifying it. */
public final class LSPosedModuleStateReader {
    public static final String GRAMSIEVE_PACKAGE = "com.tianqianguai.gramsieve";
    public static final String TELEGRAM_PACKAGE = "org.telegram.messenger";
    private static final String DATABASE_PATH = "/data/adb/lspd/config/modules_config.db";
    private static final String PROTOCOL_HEADER = "GRAMSIEVE_LSPOSED_DB_V1";
    private static final long READ_TIMEOUT_SECONDS = 15;

    private LSPosedModuleStateReader() {
    }

    public enum Status {
        SUCCESS,
        ROOT_UNAVAILABLE,
        DATABASE_UNAVAILABLE,
        QUERY_FAILED,
        TIMED_OUT
    }

    public static final class ModuleState {
        public final boolean registered;
        public final boolean enabled;
        public final boolean telegramScoped;
        public final boolean activeForTelegram;

        private ModuleState(
                boolean registered,
                boolean enabled,
                boolean telegramScoped,
                boolean activeForTelegram
        ) {
            this.registered = registered;
            this.enabled = enabled;
            this.telegramScoped = telegramScoped;
            this.activeForTelegram = activeForTelegram;
        }

        private static ModuleState fromRow(boolean enabled, boolean telegramScoped) {
            return new ModuleState(true, enabled, telegramScoped, enabled && telegramScoped);
        }

        private static ModuleState absent() {
            return new ModuleState(false, false, false, false);
        }
    }

    public static final class Result {
        public final Status status;
        public final String detail;
        private final Map<String, ModuleState> packageStates;

        private Result(Status status, String detail, Map<String, ModuleState> packageStates) {
            this.status = status;
            this.detail = detail == null ? "" : detail;
            this.packageStates = Collections.unmodifiableMap(new HashMap<>(packageStates));
        }

        public boolean isSuccessful() {
            return status == Status.SUCCESS;
        }

        public ModuleState stateForPackage(String packageName) {
            ModuleState state = packageStates.get(packageName);
            return state == null ? ModuleState.absent() : state;
        }

        public ModuleState stateFor(ModuleConflictDetector.KnownModule module) {
            boolean registered = false;
            boolean enabled = false;
            boolean telegramScoped = false;
            boolean activeForTelegram = false;
            for (String packageName : module.packageNames) {
                ModuleState state = stateForPackage(packageName);
                registered |= state.registered;
                enabled |= state.enabled;
                telegramScoped |= state.telegramScoped;
                activeForTelegram |= state.activeForTelegram;
            }
            return new ModuleState(registered, enabled, telegramScoped, activeForTelegram);
        }

        public Set<ModuleConflictDetector.KnownModule> activeKnownModules(
                Set<ModuleConflictDetector.KnownModule> installedModules
        ) {
            EnumSet<ModuleConflictDetector.KnownModule> active =
                    EnumSet.noneOf(ModuleConflictDetector.KnownModule.class);
            if (installedModules == null) {
                return active;
            }
            for (ModuleConflictDetector.KnownModule module : installedModules) {
                if (stateFor(module).activeForTelegram) {
                    active.add(module);
                }
            }
            return active;
        }
    }

    public static Result read(Context context) {
        if (context == null) {
            return failure(Status.QUERY_FAILED, "context unavailable");
        }
        String apkPath = context.getApplicationInfo().sourceDir;
        int userId = Process.myUid() / 100000;
        String command = "CLASSPATH=" + shellQuote(apkPath)
                + " /system/bin/app_process /system/bin "
                + LSPosedModuleStateReader.class.getName()
                + " " + userId;
        java.lang.Process process;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return failure(Status.ROOT_UNAVAILABLE, e.getClass().getSimpleName());
        }

        try {
            if (!process.waitFor(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return failure(Status.TIMED_OUT, "root query timed out");
            }
            List<String> output = readLines(process);
            return parseProcessOutput(process.exitValue(), output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return failure(Status.TIMED_OUT, "root query interrupted");
        } catch (IOException e) {
            return failure(Status.QUERY_FAILED, e.getClass().getSimpleName());
        }
    }

    static Result parseProcessOutput(int exitCode, List<String> output) {
        Map<String, ModuleState> states = new HashMap<>();
        boolean protocolSeen = false;
        boolean complete = false;
        Status reportedFailure = null;
        String detail = "";
        for (String line : output) {
            if (PROTOCOL_HEADER.equals(line)) {
                protocolSeen = true;
                continue;
            }
            if ("OK".equals(line)) {
                complete = true;
                continue;
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length == 4 && "MODULE".equals(fields[0])) {
                states.put(fields[1], ModuleState.fromRow(
                        "1".equals(fields[2]),
                        "1".equals(fields[3])
                ));
            } else if (fields.length >= 2 && "ERROR".equals(fields[0])) {
                reportedFailure = parseStatus(fields[1]);
                detail = fields.length >= 3 ? fields[2] : fields[1];
            }
        }
        if (reportedFailure != null) {
            return failure(reportedFailure, detail);
        }
        if (exitCode == 0 && protocolSeen && complete) {
            return new Result(Status.SUCCESS, "", states);
        }
        String joined = String.join(" ", output).toLowerCase();
        if (!protocolSeen && (joined.contains("denied") || joined.contains("su: not found"))) {
            return failure(Status.ROOT_UNAVAILABLE, "root permission unavailable");
        }
        return failure(Status.QUERY_FAILED, "invalid helper response");
    }

    private static Status parseStatus(String value) {
        try {
            return Status.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return Status.QUERY_FAILED;
        }
    }

    private static Result failure(Status status, String detail) {
        return new Result(status, detail, Collections.emptyMap());
    }

    private static List<String> readLines(java.lang.Process process) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Entry point executed by app_process under read-only root access. */
    public static void main(String[] args) {
        System.out.println(PROTOCOL_HEADER);
        int userId;
        try {
            userId = args.length >= 1 ? Integer.parseInt(args[0]) : 0;
        } catch (NumberFormatException e) {
            printError(Status.QUERY_FAILED, "invalid user id");
            return;
        }

        File databaseFile = new File(DATABASE_PATH);
        if (!databaseFile.isFile()) {
            printError(Status.DATABASE_UNAVAILABLE, "database not found");
            return;
        }

        SQLiteDatabase.OpenParams openParams = new SQLiteDatabase.OpenParams.Builder()
                .addOpenFlags(SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                .build();
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(databaseFile, openParams)) {
            boolean perUserModuleState = hasTable(database, "modules_state");
            List<String> knownPackages = knownPackageNames();
            String packagePlaceholders = String.join(",", Collections.nCopies(
                    knownPackages.size(),
                    "?"
            ));
            String sql;
            List<String> selectionArgs = new ArrayList<>();
            if (perUserModuleState) {
                sql = "SELECT m.module_pkg_name, COALESCE(ms.enabled, 0) AS enabled, "
                        + "EXISTS (SELECT 1 FROM scope s "
                        + "WHERE s.module_pkg_name = m.module_pkg_name "
                        + "AND s.app_pkg_name = ? AND s.user_id = ?) AS telegram_scoped "
                        + "FROM modules m LEFT JOIN modules_state ms "
                        + "ON ms.module_pkg_name = m.module_pkg_name AND ms.user_id = ? "
                        + "WHERE m.module_pkg_name IN (" + packagePlaceholders + ")";
                selectionArgs.add(TELEGRAM_PACKAGE);
                selectionArgs.add(Integer.toString(userId));
                selectionArgs.add(Integer.toString(userId));
            } else {
                sql = "SELECT m.module_pkg_name, m.enabled AS enabled, "
                        + "EXISTS (SELECT 1 FROM scope s WHERE s.mid = m.mid "
                        + "AND s.app_pkg_name = ? AND s.user_id = ?) AS telegram_scoped "
                        + "FROM modules m WHERE m.module_pkg_name IN ("
                        + packagePlaceholders + ")";
                selectionArgs.add(TELEGRAM_PACKAGE);
                selectionArgs.add(Integer.toString(userId));
            }
            selectionArgs.addAll(knownPackages);

            try (Cursor cursor = database.rawQuery(sql, selectionArgs.toArray(new String[0]))) {
                int packageIndex = cursor.getColumnIndexOrThrow("module_pkg_name");
                int enabledIndex = cursor.getColumnIndexOrThrow("enabled");
                int scopedIndex = cursor.getColumnIndexOrThrow("telegram_scoped");
                while (cursor.moveToNext()) {
                    String packageName = cursor.getString(packageIndex);
                    int enabled = cursor.getInt(enabledIndex) == 1 ? 1 : 0;
                    int scoped = cursor.getInt(scopedIndex) == 1 ? 1 : 0;
                    System.out.println(
                            "MODULE\t" + packageName + "\t" + enabled + "\t" + scoped
                    );
                }
            }
            System.out.println("OK");
        } catch (Throwable throwable) {
            String message = throwable.getMessage();
            printError(
                    Status.QUERY_FAILED,
                    throwable.getClass().getSimpleName()
                            + (message == null || message.isBlank() ? "" : ": " + message)
            );
        }
    }

    private static boolean hasTable(SQLiteDatabase database, String tableName) {
        try (Cursor cursor = database.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                new String[]{tableName}
        )) {
            return cursor.moveToFirst();
        }
    }

    private static List<String> knownPackageNames() {
        List<String> packages = new ArrayList<>();
        packages.add(GRAMSIEVE_PACKAGE);
        for (ModuleConflictDetector.KnownModule module :
                ModuleConflictDetector.KnownModule.values()) {
            packages.addAll(module.packageNames);
        }
        return packages;
    }

    private static void printError(Status status, String detail) {
        String safeDetail = detail.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        System.out.println("ERROR\t" + status.name() + "\t" + safeDetail);
    }
}
