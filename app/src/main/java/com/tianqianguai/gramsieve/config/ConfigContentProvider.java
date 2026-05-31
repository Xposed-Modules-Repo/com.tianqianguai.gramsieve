package com.tianqianguai.gramsieve.config;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tianqianguai.gramsieve.core.FilterConfig;

public final class ConfigContentProvider extends ContentProvider {
    public static final String AUTHORITY = "com.tianqianguai.gramsieve.config";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    public static final String METHOD_GET_CONFIG = "getConfig";
    public static final String METHOD_SAVE_CONFIG = "saveConfig";
    public static final String METHOD_GET_DIAGNOSTICS = "getDiagnostics";
    public static final String METHOD_APPEND_DIAGNOSTIC = "appendDiagnostic";
    public static final String METHOD_CLEAR_DIAGNOSTICS = "clearDiagnostics";
    public static final String METHOD_GET_LOGS = "getLogs";
    public static final String METHOD_APPEND_LOG = "appendLog";
    public static final String METHOD_CLEAR_LOGS = "clearLogs";
    public static final String KEY_CONFIG_JSON = "config_json";
    public static final String KEY_UPDATED_AT_EPOCH_MS = "updated_at_epoch_ms";
    public static final String KEY_DIAGNOSTICS_JSON = "diagnostics_json";
    public static final String KEY_DIAGNOSTIC_COUNT = "diagnostic_count";
    public static final String KEY_DIAGNOSTIC_ENTRY_JSON = "diagnostic_entry_json";
    public static final String KEY_LOGS_JSON = "logs_json";
    public static final String KEY_LOG_COUNT = "log_count";
    public static final String KEY_LOG_ENTRY_JSON = "log_entry_json";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (METHOD_GET_CONFIG.equals(method)) {
            return handleGetConfig();
        }
        if (METHOD_SAVE_CONFIG.equals(method)) {
            return handleSaveConfig(extras);
        }
        if (METHOD_GET_DIAGNOSTICS.equals(method)) {
            return handleGetDiagnostics();
        }
        if (METHOD_APPEND_DIAGNOSTIC.equals(method)) {
            return handleAppendDiagnostic(extras);
        }
        if (METHOD_CLEAR_DIAGNOSTICS.equals(method)) {
            return handleClearDiagnostics();
        }
        if (METHOD_GET_LOGS.equals(method)) {
            return handleGetLogs();
        }
        if (METHOD_APPEND_LOG.equals(method)) {
            return handleAppendLog(extras);
        }
        if (METHOD_CLEAR_LOGS.equals(method)) {
            return handleClearLogs();
        }
        return super.call(method, arg, extras);
    }

    @NonNull
    private Bundle handleGetConfig() {
        Bundle bundle = new Bundle();
        if (getContext() == null) {
            bundle.putString(KEY_CONFIG_JSON, ModuleConfigStore.toJson(FilterConfig.createDefault()));
            bundle.putLong(KEY_UPDATED_AT_EPOCH_MS, 0L);
            return bundle;
        }
        FilterConfig config = ModuleConfigStore.load(getContext());
        bundle.putString(KEY_CONFIG_JSON, ModuleConfigStore.toJson(config));
        bundle.putLong(KEY_UPDATED_AT_EPOCH_MS, config.updatedAtEpochMs);
        return bundle;
    }

    @NonNull
    private Bundle handleSaveConfig(@Nullable Bundle extras) {
        Bundle bundle = new Bundle();
        if (getContext() == null) {
            bundle.putLong(KEY_UPDATED_AT_EPOCH_MS, 0L);
            return bundle;
        }
        String json = extras == null ? null : extras.getString(KEY_CONFIG_JSON, null);
        FilterConfig config = ModuleConfigStore.fromJson(json);
        ModuleConfigStore.save(getContext(), config);
        FilterConfig saved = ModuleConfigStore.load(getContext());
        bundle.putString(KEY_CONFIG_JSON, ModuleConfigStore.toJson(saved));
        bundle.putLong(KEY_UPDATED_AT_EPOCH_MS, saved.updatedAtEpochMs);
        return bundle;
    }

    @NonNull
    private Bundle handleGetDiagnostics() {
        Bundle bundle = new Bundle();
        if (getContext() == null) {
            bundle.putString(KEY_DIAGNOSTICS_JSON, DiagnosticLogStore.toJson(new DiagnosticLogStore.DiagnosticSnapshot()));
            bundle.putInt(KEY_DIAGNOSTIC_COUNT, 0);
            return bundle;
        }
        DiagnosticLogStore.DiagnosticSnapshot snapshot = DiagnosticLogStore.load(getContext());
        bundle.putString(KEY_DIAGNOSTICS_JSON, DiagnosticLogStore.toJson(snapshot));
        bundle.putInt(KEY_DIAGNOSTIC_COUNT, snapshot.entries.size());
        return bundle;
    }

    @NonNull
    private Bundle handleAppendDiagnostic(@Nullable Bundle extras) {
        Bundle bundle = new Bundle();
        if (getContext() == null) {
            bundle.putInt(KEY_DIAGNOSTIC_COUNT, 0);
            return bundle;
        }
        String entryJson = extras == null ? null : extras.getString(KEY_DIAGNOSTIC_ENTRY_JSON, null);
        DiagnosticLogStore.append(getContext(), DiagnosticLogStore.entryFromJson(entryJson));
        DiagnosticLogStore.DiagnosticSnapshot snapshot = DiagnosticLogStore.load(getContext());
        bundle.putInt(KEY_DIAGNOSTIC_COUNT, snapshot.entries.size());
        return bundle;
    }

    @NonNull
    private Bundle handleClearDiagnostics() {
        Bundle bundle = new Bundle();
        if (getContext() != null) {
            DiagnosticLogStore.clear(getContext());
        }
        bundle.putInt(KEY_DIAGNOSTIC_COUNT, 0);
        return bundle;
    }

    @NonNull
    private Bundle handleGetLogs() {
        Bundle bundle = new Bundle();
        if (getContext() == null) {
            bundle.putString(KEY_LOGS_JSON, PersistentLogStore.toJson(new PersistentLogStore.LogSnapshot()));
            bundle.putInt(KEY_LOG_COUNT, 0);
            return bundle;
        }
        PersistentLogStore.LogSnapshot snapshot = PersistentLogStore.load(getContext());
        bundle.putString(KEY_LOGS_JSON, PersistentLogStore.toJson(snapshot));
        bundle.putInt(KEY_LOG_COUNT, snapshot.entries.size());
        return bundle;
    }

    @NonNull
    private Bundle handleAppendLog(@Nullable Bundle extras) {
        Bundle bundle = new Bundle();
        if (getContext() == null) {
            bundle.putInt(KEY_LOG_COUNT, 0);
            return bundle;
        }
        String entryJson = extras == null ? null : extras.getString(KEY_LOG_ENTRY_JSON, null);
        PersistentLogStore.append(getContext(), PersistentLogStore.entryFromJson(entryJson));
        PersistentLogStore.LogSnapshot snapshot = PersistentLogStore.load(getContext());
        bundle.putInt(KEY_LOG_COUNT, snapshot.entries.size());
        return bundle;
    }

    @NonNull
    private Bundle handleClearLogs() {
        Bundle bundle = new Bundle();
        if (getContext() != null) {
            PersistentLogStore.clear(getContext());
        }
        bundle.putInt(KEY_LOG_COUNT, 0);
        return bundle;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }
}
