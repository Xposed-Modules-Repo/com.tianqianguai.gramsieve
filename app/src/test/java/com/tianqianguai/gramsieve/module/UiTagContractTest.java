package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertNotEquals;

import com.tianqianguai.gramsieve.R;

import org.junit.Test;

/** Guards the tag namespace used by filtering and cached-media overlays. */
public class UiTagContractTest {
    @Test
    public void cachedMediaOverlayHasItsOwnResourceId() {
        assertNotEquals(R.id.gramsieve_view_state, R.id.gramsieve_cached_media_overlay);
    }
}
