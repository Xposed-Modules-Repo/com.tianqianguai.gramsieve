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
    public void unavailableFeatureCannotSurviveSanitization() {
        EnhancementConfig config = new EnhancementConfig();
        config.enabled.put(EnhancementConfig.Feature.LOCAL_GROUP_MEMBER_LIST.key, true);

        config.sanitize();

        assertFalse(config.isEnabled(EnhancementConfig.Feature.LOCAL_GROUP_MEMBER_LIST));
    }
}
