package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class LogProbeBudgetTest {
    @Test
    public void emitsSamplesThenOneSuppressionNotice() {
        LogProbeBudget budget = new LogProbeBudget(2);

        assertEquals(LogProbeBudget.Permit.SAMPLE, budget.acquire());
        assertEquals(LogProbeBudget.Permit.SAMPLE, budget.acquire());
        assertEquals(LogProbeBudget.Permit.SUPPRESSION_NOTICE, budget.acquire());
        assertEquals(LogProbeBudget.Permit.DROP, budget.acquire());
        assertEquals(LogProbeBudget.Permit.DROP, budget.acquire());
    }

    @Test
    public void resetRestoresTheBudget() {
        LogProbeBudget budget = new LogProbeBudget(1);
        assertEquals(LogProbeBudget.Permit.SAMPLE, budget.acquire());
        assertEquals(LogProbeBudget.Permit.SUPPRESSION_NOTICE, budget.acquire());

        budget.reset();

        assertEquals(LogProbeBudget.Permit.SAMPLE, budget.acquire());
    }
}
