package com.tianqianguai.gramsieve.module;

import com.tianqianguai.gramsieve.core.FilterDecision;

import java.util.List;

/** Pure precedence rule for a Telegram media group. */
final class GroupedDecisionSelector {
    private GroupedDecisionSelector() {
    }

    static Selection select(List<FilterDecision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return Selection.allow(-1);
        }
        int firstMatchedIndex = -1;
        FilterDecision firstMatched = null;
        for (int i = 0; i < decisions.size(); i++) {
            FilterDecision decision = decisions.get(i);
            if (decision == null) {
                continue;
            }
            // Exclusions are a group-level allow. Check them before a matched member so a
            // keep-rule can never be defeated by another media member's filter match.
            if (decision.excluded) {
                return new Selection(decision, i);
            }
            if (decision.matched && firstMatched == null) {
                firstMatched = decision;
                firstMatchedIndex = i;
            }
        }
        return firstMatched == null
                ? Selection.allow(-1)
                : new Selection(firstMatched, firstMatchedIndex);
    }

    static final class Selection {
        final FilterDecision decision;
        final int index;

        private Selection(FilterDecision decision, int index) {
            this.decision = decision == null ? FilterDecision.allow() : decision;
            this.index = index;
        }

        static Selection allow(int index) {
            return new Selection(FilterDecision.allow(), index);
        }
    }
}
