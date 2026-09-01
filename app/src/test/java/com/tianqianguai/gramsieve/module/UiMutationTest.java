package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;

import com.tianqianguai.gramsieve.core.FilterConfig;

import org.junit.Test;

public class UiMutationTest {
    @Test
    public void untouchedAllowIsNoOp() {
        assertEquals(
                UiMutation.MutationTransition.NOOP,
                UiMutation.transitionFor(false, "", null, "message", FilterConfig.Action.HIDE, false)
        );
    }

    @Test
    public void sameGroupedKeyAndActionIsNoOp() {
        assertEquals(
                UiMutation.MutationTransition.NOOP,
                UiMutation.transitionFor(
                        true,
                        "group:42",
                        FilterConfig.Action.COLLAPSE,
                        "group:42",
                        FilterConfig.Action.COLLAPSE,
                        true
                )
        );
    }

    @Test
    public void newMessageSwitchesAnExistingMutation() {
        assertEquals(
                UiMutation.MutationTransition.SWITCHED,
                UiMutation.transitionFor(
                        true,
                        "group:42",
                        FilterConfig.Action.HIDE,
                        "group:43",
                        FilterConfig.Action.HIDE,
                        true
                )
        );
    }

    @Test
    public void allowRestoresOnlyAnExistingMutation() {
        assertEquals(
                UiMutation.MutationTransition.RESTORED,
                UiMutation.transitionFor(
                        true,
                        "message:1",
                        FilterConfig.Action.HIDE,
                        "message:1",
                        null,
                        false
                )
        );
    }
}
