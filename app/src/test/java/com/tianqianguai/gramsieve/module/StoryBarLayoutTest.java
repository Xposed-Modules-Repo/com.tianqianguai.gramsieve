package com.tianqianguai.gramsieve.module;

import com.tianqianguai.gramsieve.core.EnhancementConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class StoryBarLayoutTest {
    @Test
    public void privacyToggleDoesNotHideBar() {
        EnhancementConfig config = new EnhancementConfig();
        config.setEnabled(EnhancementConfig.Feature.HIDE_STORY_VIEW_STATUS, true);
        assertFalse(EnhancementHookInstaller.shouldHideStoryBar(config));
        config.setEnabled(EnhancementConfig.Feature.HIDE_STORY_BAR, true);
        assertTrue(EnhancementHookInstaller.shouldHideStoryBar(config));
        config.setEnabled(EnhancementConfig.Feature.HIDE_STORY_BAR, false);
        assertFalse(EnhancementHookInstaller.shouldHideStoryBar(config));
        assertTrue(config.isEnabled(EnhancementConfig.Feature.HIDE_STORY_VIEW_STATUS));
    }

    @Test
    public void barToggleDoesNotEnablePrivacyToggle() {
        EnhancementConfig config = new EnhancementConfig();
        config.setEnabled(EnhancementConfig.Feature.HIDE_STORY_BAR, true);
        assertTrue(EnhancementHookInstaller.shouldHideStoryBar(config));
        assertFalse(config.isEnabled(EnhancementConfig.Feature.HIDE_STORY_VIEW_STATUS));
    }

    @Test
    public void collapseClearsParentSpacingWithoutTouchingSearchOrStoryData() {
        FakeDialogs dialogs = new FakeDialogs();
        Object stories = dialogs.storyData;
        EnhancementHookInstaller.collapseStoryBarLayoutState(dialogs);
        assertFalse(dialogs.dialogStoriesCellVisible);
        assertFalse(dialogs.hasStories);
        assertFalse(dialogs.hasOnlySlefStories);
        assertFalse(dialogs.animateToHasStories);
        assertEquals(0f, dialogs.progressToDialogStoriesCell, 0f);
        assertEquals(0f, dialogs.progressToShowStories, 0f);
        assertEquals(0f, dialogs.scrollAdditionalOffset, 0f);
        assertEquals(0f, dialogs.scrollYOffset, 0f);
        assertTrue(dialogs.searchIsShowed);
        assertSame(stories, dialogs.storyData);
    }

    private static class FakeDialogs {
        boolean dialogStoriesCellVisible = true;
        boolean hasStories = true;
        boolean hasOnlySlefStories = true;
        boolean animateToHasStories = true;
        float progressToDialogStoriesCell = 1f;
        float progressToShowStories = 1f;
        float scrollAdditionalOffset = -81f;
        float scrollYOffset = -129f;
        boolean searchIsShowed = true;
        Object storyData = new Object();

        private void setScrollY(float value) {
            scrollYOffset = value;
        }
    }
}
