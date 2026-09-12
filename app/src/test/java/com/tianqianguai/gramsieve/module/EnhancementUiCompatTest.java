package com.tianqianguai.gramsieve.module;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnhancementUiCompatTest {
    @Test
    public void phoneRowRemovalReindexesFollowingRowsAndPreservesStartBoundary() {
        Profile profile = new Profile();
        assertTrue(EnhancementUiCompat.removePhoneRow(profile));
        assertEquals(-1, profile.phoneRow);
        assertEquals(5, profile.rowCount);
        assertEquals(2, profile.infoStartRow);
        assertEquals(2, profile.usernameRow);
        assertEquals(4, profile.infoEndRow);
        assertEquals(1, profile.headerRow);
        assertEquals(100, profile.userId);
        assertFalse(EnhancementUiCompat.removePhoneRow(profile));
        assertEquals(5, profile.rowCount);
    }

    @Test
    public void inheritedProfileMetadataIsHandledWithoutChangingUnrelatedFields() {
        ChildProfile profile = new ChildProfile();
        assertTrue(EnhancementUiCompat.removePhoneRow(profile));
        assertEquals(-1, profile.phoneRow);
        assertEquals(9, profile.decoration);
    }

    @Test
    public void missingPhoneRowLeavesExistingLayoutUnchanged() {
        Profile profile = new Profile();
        profile.phoneRow = -1;
        assertFalse(EnhancementUiCompat.removePhoneRow(profile));
        assertEquals(6, profile.rowCount);
    }

    @Test
    public void profileArraysKeepBothNonNullInstancesAndSupportLegacySingleView() {
        Object a = new Object();
        Object b = new Object();
        assertEquals(2, EnhancementUiCompat.elements(new Object[]{a, null, b}).size());
        assertSame(a, EnhancementUiCompat.elements(a).get(0));
        assertTrue(EnhancementUiCompat.elements(null).isEmpty());
    }

    @Test
    public void cameraToggleRestoresNativeModeWithoutWritingTelegramPreferences() {
        EnhancementUiCompat compat = new EnhancementUiCompat();
        EnterView enter = new EnterView();
        compat.cameraMode(enter, true);
        assertFalse(enter.isInVideoMode);
        compat.cameraMode(enter, false);
        assertTrue(enter.isInVideoMode);
        assertFalse(enter.persisted);
        enter.recordingAudioVideo = true;
        compat.cameraMode(enter, true);
        assertTrue(enter.isInVideoMode);
    }

    @Test
    public void constructorRequestedVideoModeSurvivesSuppressedSetter() {
        EnhancementUiCompat compat = new EnhancementUiCompat();
        EnterView enter = new EnterView();
        compat.rememberVideoMode(enter, true);
        enter.isInVideoMode = false;
        compat.cameraMode(enter, false);
        assertTrue(enter.isInVideoMode);
    }

    static class EnterView {
        boolean isInVideoMode = true;
        boolean recordingAudioVideo;
        boolean persisted;
        private void setRecordVideoButtonVisible(boolean value, boolean persist) {
            isInVideoMode = value;
            persisted |= persist;
        }
    }

    static class Profile {
        int phoneRow = 2;
        int rowCount = 6;
        int infoStartRow = 2;
        int usernameRow = 3;
        int infoEndRow = 5;
        int headerRow = 1;
        int userId = 100;
    }
    static class ChildProfile extends Profile { int decoration = 9; }
}
