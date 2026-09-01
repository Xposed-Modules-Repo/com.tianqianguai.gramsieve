package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.FilterDecision;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class GroupedDecisionSelectorTest {
    @Test
    public void exclusionWinsOverEarlierOrLaterMatch() {
        GroupedDecisionSelector.Selection selection = GroupedDecisionSelector.select(Arrays.asList(
                FilterDecision.matched(FilterConfig.Action.HIDE, "match", "matched"),
                FilterDecision.excluded("keep", "excluded")
        ));

        assertTrue(selection.decision.excluded);
        assertFalse(selection.decision.matched);
        assertEquals(1, selection.index);
    }

    @Test
    public void firstMatchWinsWhenThereIsNoExclusion() {
        GroupedDecisionSelector.Selection selection = GroupedDecisionSelector.select(Arrays.asList(
                FilterDecision.allow(),
                FilterDecision.matched(FilterConfig.Action.COLLAPSE, "first", "matched"),
                FilterDecision.matched(FilterConfig.Action.HIDE, "second", "matched")
        ));

        assertTrue(selection.decision.matched);
        assertEquals(FilterConfig.Action.COLLAPSE, selection.decision.action);
        assertEquals(1, selection.index);
    }

    @Test
    public void emptyGroupAllowsWithoutAnIndex() {
        GroupedDecisionSelector.Selection selection = GroupedDecisionSelector.select(Collections.emptyList());

        assertFalse(selection.decision.matched);
        assertFalse(selection.decision.excluded);
        assertEquals(-1, selection.index);
    }
}
