package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class LocalDialogDeleteStoreTest {
    private SharedPreferences prefs;
    private SharedPreferences.Editor editor;
    private LocalDialogDeleteStore store;

    @Before
    public void setUp() {
        Context context = Mockito.mock(Context.class);
        prefs = Mockito.mock(SharedPreferences.class);
        editor = Mockito.mock(SharedPreferences.Editor.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.edit()).thenReturn(editor);
        when(editor.putBoolean(anyString(), anyBoolean())).thenReturn(editor);
        when(editor.remove(anyString())).thenReturn(editor);
        store = new LocalDialogDeleteStore(context);
    }

    @Test
    public void hiddenDialogIsScopedToTelegramAccount() {
        when(prefs.getBoolean(LocalDialogDeleteStore.preferenceKey(0, 42L), false)).thenReturn(true);

        assertTrue(store.isHidden(42L, 0));
        assertFalse(store.isHidden(42L, 1));
    }

    @Test
    public void hidePersistsAccountAwareKey() {
        store.hide(42L, 1);

        verify(editor).putBoolean(LocalDialogDeleteStore.preferenceKey(1, 42L), true);
        verify(editor).apply();
    }

    @Test
    public void legacyEntryMigratesOnlyToRecordedAccount() {
        Map<String, Object> entries = new HashMap<>();
        entries.put("hidden_dialog_42", true);
        entries.put("hidden_dialog_account_42", 1);
        Mockito.doReturn(entries).when(prefs).getAll();

        Set<LocalDialogDeleteStore.HiddenDialog> hiddenDialogs = store.hiddenDialogs(0);

        assertTrue(hiddenDialogs.contains(new LocalDialogDeleteStore.HiddenDialog(1, 42L)));
        assertFalse(hiddenDialogs.contains(new LocalDialogDeleteStore.HiddenDialog(0, 42L)));
        verify(editor).putBoolean(LocalDialogDeleteStore.preferenceKey(1, 42L), true);
        verify(editor).remove("hidden_dialog_42");
        verify(editor).remove("hidden_dialog_account_42");
        verify(editor).apply();
    }

    @Test
    public void legacyEntryWithoutAccountUsesSelectedFallback() {
        Map<String, Object> entries = new HashMap<>();
        entries.put("hidden_dialog_-42", true);
        Mockito.doReturn(entries).when(prefs).getAll();

        Set<LocalDialogDeleteStore.HiddenDialog> hiddenDialogs = store.hiddenDialogs(2);

        assertTrue(hiddenDialogs.contains(new LocalDialogDeleteStore.HiddenDialog(2, -42L)));
        assertFalse(hiddenDialogs.contains(new LocalDialogDeleteStore.HiddenDialog(0, -42L)));
    }
}
