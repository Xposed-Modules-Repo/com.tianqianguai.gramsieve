package com.tianqianguai.gramsieve.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EnhancementConfigTest {
    @Test
    public void everyEnhancementIsDisabledByDefault() {
        EnhancementConfig config = new EnhancementConfig();

        for (EnhancementConfig.Feature feature : EnhancementConfig.Feature.values()) {
            assertFalse(feature.key, config.isEnabled(feature));
        }
    }

    @Test
    public void sanitizeClampsTransferSettingsAndDropsUnknownKeys() {
        EnhancementConfig config = new EnhancementConfig();
        config.downloadParallelism = 99;
        config.uploadParallelism = -1;
        config.enabled.put("unknown", true);
        config.setEnabled(EnhancementConfig.Feature.DOWNLOAD_BOOST, true);

        config.sanitize();

        assertEquals(32, config.downloadParallelism);
        assertEquals(1, config.uploadParallelism);
        assertFalse(config.enabled.containsKey("unknown"));
        assertTrue(config.isEnabled(EnhancementConfig.Feature.DOWNLOAD_BOOST));
    }

    @Test
    public void disabledFeaturesAreNotPersistedAsFalseEntries() {
        EnhancementConfig config = new EnhancementConfig();
        config.setEnabled(EnhancementConfig.Feature.SHOW_MESSAGE_ID, true);
        config.setEnabled(EnhancementConfig.Feature.SHOW_MESSAGE_ID, false);

        assertFalse(config.enabled.containsKey(EnhancementConfig.Feature.SHOW_MESSAGE_ID.key));
    }

    @Test
    public void keepDownloadButtonVisibleIsAvailableAndSurvivesSanitization() {
        EnhancementConfig config = new EnhancementConfig();
        config.setEnabled(EnhancementConfig.Feature.KEEP_DOWNLOAD_BUTTON_VISIBLE, true);

        config.sanitize();

        assertTrue(EnhancementConfig.Feature.KEEP_DOWNLOAD_BUTTON_VISIBLE.isAvailableInCurrentBuild());
        assertTrue(config.isEnabledForGramSieve(
                EnhancementConfig.Feature.KEEP_DOWNLOAD_BUTTON_VISIBLE));
    }

    @Test
    public void unavailableFeatureCannotSurviveSanitization() {
        EnhancementConfig config = new EnhancementConfig();
        config.enabled.put(EnhancementConfig.Feature.LOCAL_GROUP_MEMBER_LIST.key, true);

        config.sanitize();

        assertFalse(config.isEnabled(EnhancementConfig.Feature.LOCAL_GROUP_MEMBER_LIST));
    }

    @Test
    public void moduleFallbacksAreDisabledByDefaultAndSanitizedByKnownModule() {
        EnhancementConfig config = new EnhancementConfig();
        config.moduleFallbacks.put("unknown", true);
        config.setModuleFallbackEnabled(ModuleConflictDetector.KnownModule.TELEGAMI, true);

        config.sanitize();

        assertTrue(config.isModuleFallbackEnabled(ModuleConflictDetector.KnownModule.TELEGAMI));
        assertFalse(config.moduleFallbacks.containsKey("unknown"));
        assertFalse(config.isModuleFallbackEnabled(ModuleConflictDetector.KnownModule.TELEVIP));
    }

    @Test
    public void enabledModuleFallbackYieldsOnlyDeclaredCapabilities() {
        EnhancementConfig config = new EnhancementConfig();
        config.setModuleFallbackEnabled(ModuleConflictDetector.KnownModule.TELEGAMI, true);

        assertTrue(config.yieldsToModule(ModuleConflictDetector.ConflictKind.ANTI_RECALL));
        assertTrue(config.yieldsToModule(ModuleConflictDetector.ConflictKind.DOWNLOAD_ACCELERATION));
        assertFalse(config.yieldsToModule(ModuleConflictDetector.ConflictKind.EDIT_HISTORY));
        assertFalse(config.yieldsToModule(ModuleConflictDetector.ConflictKind.STORIES));
    }

    @Test
    public void featureFallbackPreservesSwitchButYieldsRuntimeOwnership() {
        EnhancementConfig config = new EnhancementConfig();
        config.setEnabled(EnhancementConfig.Feature.DOWNLOAD_BOOST, true);
        config.setEnabled(EnhancementConfig.Feature.UPLOAD_BOOST, true);
        config.setModuleFallbackEnabled(
                ModuleConflictDetector.KnownModule.TELEGRAM_SPEED_HOOK,
                true
        );

        assertTrue(config.isEnabled(EnhancementConfig.Feature.DOWNLOAD_BOOST));
        assertFalse(config.isEnabledForGramSieve(EnhancementConfig.Feature.DOWNLOAD_BOOST));
        assertTrue(config.isEnabledForGramSieve(EnhancementConfig.Feature.UPLOAD_BOOST));
    }
}
