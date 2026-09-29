package com.tianqianguai.gramsieve.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.tianqianguai.gramsieve.core.ModuleConflictDetector;

import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;

public final class LSPosedModuleStateReaderTest {
    @Test
    public void successfulOutput_distinguishesEnabledAndTelegramScope() {
        LSPosedModuleStateReader.Result result = LSPosedModuleStateReader.parseProcessOutput(
                0,
                Arrays.asList(
                        "GRAMSIEVE_LSPOSED_DB_V1",
                        "MODULE\tcom.aoya.telegami\t1\t1",
                        "MODULE\tcom.my.televip\t1\t0",
                        "MODULE\tcom.shatyuka.killergram\t0\t1",
                        "OK"
                )
        );

        assertEquals(LSPosedModuleStateReader.Status.SUCCESS, result.status);
        assertTrue(result.stateFor(ModuleConflictDetector.KnownModule.TELEGAMI).activeForTelegram);
        assertFalse(result.stateFor(ModuleConflictDetector.KnownModule.TELEVIP).activeForTelegram);
        assertFalse(result.stateFor(ModuleConflictDetector.KnownModule.KILLERGRAM).activeForTelegram);
        assertEquals(
                EnumSet.of(ModuleConflictDetector.KnownModule.TELEGAMI),
                result.activeKnownModules(EnumSet.allOf(ModuleConflictDetector.KnownModule.class))
        );
    }

    @Test
    public void aliases_areCombinedWithoutCombiningPartialActivation() {
        LSPosedModuleStateReader.Result result = LSPosedModuleStateReader.parseProcessOutput(
                0,
                Arrays.asList(
                        "GRAMSIEVE_LSPOSED_DB_V1",
                        "MODULE\tru.mike.telegramtweaks\t1\t0",
                        "MODULE\tru.mike.sidestories\t0\t1",
                        "OK"
                )
        );

        LSPosedModuleStateReader.ModuleState state =
                result.stateFor(ModuleConflictDetector.KnownModule.TELEGRAM_TWEAKS);
        assertTrue(state.registered);
        assertTrue(state.enabled);
        assertTrue(state.telegramScoped);
        assertFalse(state.activeForTelegram);
    }

    @Test
    public void reportedDatabaseFailure_isPreserved() {
        LSPosedModuleStateReader.Result result = LSPosedModuleStateReader.parseProcessOutput(
                0,
                Arrays.asList(
                        "GRAMSIEVE_LSPOSED_DB_V1",
                        "ERROR\tDATABASE_UNAVAILABLE\tdatabase not found"
                )
        );

        assertEquals(LSPosedModuleStateReader.Status.DATABASE_UNAVAILABLE, result.status);
        assertFalse(result.isSuccessful());
    }
}
