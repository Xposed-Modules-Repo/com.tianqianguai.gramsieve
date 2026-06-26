package com.tianqianguai.gramsieve.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.mockito.Mockito;

public class AntiRecallConfigStoreTest {
    @Test
    public void defaultValues() {
        Context context = Mockito.mock(Context.class);
        SharedPreferences prefs = Mockito.mock(SharedPreferences.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.getBoolean(anyString(), anyBoolean())).thenReturn(false);
        when(prefs.getInt(eq("load_interval_seconds"), anyInt())).thenReturn(30);
        when(prefs.getInt(eq("max_cache_size"), anyInt())).thenReturn(1000);

        AntiRecallConfigStore store = new AntiRecallConfigStore(context);

        assertFalse(store.isEnabled());
        assertEquals(30, store.getLoadIntervalSeconds());
        assertEquals(1000, store.getMaxCacheSize());
        assertFalse(store.isChatEnabled(123L));
    }

    @Test
    public void setEnabledPersistsValue() {
        Context context = Mockito.mock(Context.class);
        SharedPreferences prefs = Mockito.mock(SharedPreferences.class);
        SharedPreferences.Editor editor = Mockito.mock(SharedPreferences.Editor.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.edit()).thenReturn(editor);
        when(editor.putBoolean(anyString(), anyBoolean())).thenReturn(editor);

        AntiRecallConfigStore store = new AntiRecallConfigStore(context);
        store.setEnabled(true);

        verify(editor).putBoolean("anti_recall_enabled", true);
        verify(editor).apply();
    }

    @Test
    public void setLoadIntervalPersistsValue() {
        Context context = Mockito.mock(Context.class);
        SharedPreferences prefs = Mockito.mock(SharedPreferences.class);
        SharedPreferences.Editor editor = Mockito.mock(SharedPreferences.Editor.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.edit()).thenReturn(editor);
        when(editor.putInt(anyString(), anyInt())).thenReturn(editor);

        AntiRecallConfigStore store = new AntiRecallConfigStore(context);
        store.setLoadIntervalSeconds(60);

        verify(editor).putInt("load_interval_seconds", 60);
        verify(editor).apply();
    }

    @Test
    public void setMaxCacheSizePersistsValue() {
        Context context = Mockito.mock(Context.class);
        SharedPreferences prefs = Mockito.mock(SharedPreferences.class);
        SharedPreferences.Editor editor = Mockito.mock(SharedPreferences.Editor.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.edit()).thenReturn(editor);
        when(editor.putInt(anyString(), anyInt())).thenReturn(editor);

        AntiRecallConfigStore store = new AntiRecallConfigStore(context);
        store.setMaxCacheSize(500);

        verify(editor).putInt("max_cache_size", 500);
        verify(editor).apply();
    }

    @Test
    public void setChatEnabledPersistsPerDialogId() {
        Context context = Mockito.mock(Context.class);
        SharedPreferences prefs = Mockito.mock(SharedPreferences.class);
        SharedPreferences.Editor editor = Mockito.mock(SharedPreferences.Editor.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.edit()).thenReturn(editor);
        when(editor.putBoolean(anyString(), anyBoolean())).thenReturn(editor);

        AntiRecallConfigStore store = new AntiRecallConfigStore(context);
        store.setChatEnabled(42L, true);

        verify(editor).putBoolean("anti_recall_chat_42", true);
        verify(editor).apply();
    }
}
