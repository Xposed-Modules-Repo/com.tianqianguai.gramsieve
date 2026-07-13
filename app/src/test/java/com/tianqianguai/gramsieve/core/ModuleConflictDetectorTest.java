package com.tianqianguai.gramsieve.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public final class ModuleConflictDetectorTest {
    @Test
    public void emptyInstallation_hasNoFindings() {
        ModuleConflictDetector.Report report = ModuleConflictDetector.detect(
                Collections.emptySet(),
                true
        );

        assertTrue(report.installedModules.isEmpty());
        assertTrue(report.findings.isEmpty());
        assertEquals(ModuleConflictDetector.Severity.NONE, report.highestSeverity);
    }

    @Test
    public void competingAntiRecall_isHighRiskWhenGramSieveAntiRecallIsEnabled() {
        ModuleConflictDetector.Report report = ModuleConflictDetector.detect(
                EnumSet.of(ModuleConflictDetector.KnownModule.TELEGAMI),
                true
        );

        assertTrue(hasFinding(report, ModuleConflictDetector.ConflictKind.ANTI_RECALL));
        assertEquals(ModuleConflictDetector.Severity.HIGH, report.highestSeverity);
    }

    @Test
    public void competingAntiRecall_isNotReportedWhenGramSieveAntiRecallIsDisabled() {
        ModuleConflictDetector.Report report = ModuleConflictDetector.detect(
                EnumSet.of(ModuleConflictDetector.KnownModule.RE_TELEGRAM),
                false
        );

        assertFalse(hasFinding(report, ModuleConflictDetector.ConflictKind.ANTI_RECALL));
    }

    @Test
    public void thirdPartyAntiRecallOwners_conflictWithoutGramSieveAntiRecall() {
        ModuleConflictDetector.Report report = ModuleConflictDetector.detect(
                EnumSet.of(
                        ModuleConflictDetector.KnownModule.TELEGAMI,
                        ModuleConflictDetector.KnownModule.RE_TELEGRAM
                ),
                false
        );

        assertTrue(hasFinding(report, ModuleConflictDetector.ConflictKind.ANTI_RECALL));
        assertEquals(ModuleConflictDetector.Severity.HIGH, severityOf(
                report,
                ModuleConflictDetector.ConflictKind.ANTI_RECALL
        ));
    }

    @Test
    public void multipleDownloadAccelerators_areReportedOnce() {
        ModuleConflictDetector.Report report = ModuleConflictDetector.detect(
                EnumSet.of(
                        ModuleConflictDetector.KnownModule.TELEGAMI,
                        ModuleConflictDetector.KnownModule.TELEVIP,
                        ModuleConflictDetector.KnownModule.RE_TELEGRAM,
                        ModuleConflictDetector.KnownModule.TELEGRAM_SPEED_HOOK
                ),
                false
        );

        assertEquals(1, countFindings(report, ModuleConflictDetector.ConflictKind.DOWNLOAD_ACCELERATION));
        assertEquals(ModuleConflictDetector.Severity.HIGH, severityOf(
                report,
                ModuleConflictDetector.ConflictKind.DOWNLOAD_ACCELERATION
        ));
    }

    @Test
    public void telegamiAndTeleVip_reportSecretMediaCollision() {
        ModuleConflictDetector.Report report = ModuleConflictDetector.detect(
                EnumSet.of(
                        ModuleConflictDetector.KnownModule.TELEGAMI,
                        ModuleConflictDetector.KnownModule.TELEVIP
                ),
                false
        );

        assertTrue(hasFinding(report, ModuleConflictDetector.ConflictKind.SECRET_MEDIA));
        assertTrue(hasFinding(report, ModuleConflictDetector.ConflictKind.PRIVACY));
        assertFalse(hasFinding(report, ModuleConflictDetector.ConflictKind.UI_INJECTION));
    }

    @Test
    public void activeGramSieveAndUiModule_reportUiCollision() {
        ModuleConflictDetector.Report report = ModuleConflictDetector.detect(
                EnumSet.of(ModuleConflictDetector.KnownModule.TELEGAMI),
                true
        );

        assertTrue(hasFinding(report, ModuleConflictDetector.ConflictKind.UI_INJECTION));
    }

    @Test
    public void singleSpeedHook_isDetectedButNotReportedAsConflict() {
        ModuleConflictDetector.Report report = ModuleConflictDetector.detect(
                EnumSet.of(ModuleConflictDetector.KnownModule.TELEGRAM_SPEED_HOOK),
                false
        );

        assertEquals(1, report.installedModules.size());
        assertTrue(report.findings.isEmpty());
    }

    @Test
    public void telegramTweaksAliases_resolveToOneModule() {
        Set<String> packages = new HashSet<>();
        packages.add("ru.mike.telegramtweaks");
        packages.add("ru.mike.sidestories");

        Set<ModuleConflictDetector.KnownModule> installed =
                ModuleConflictDetector.identifyInstalledModules(packages);

        assertEquals(1, installed.size());
        assertTrue(installed.contains(ModuleConflictDetector.KnownModule.TELEGRAM_TWEAKS));
    }

    @Test
    public void findings_areOrderedByDescendingSeverity() {
        ModuleConflictDetector.Report report = ModuleConflictDetector.detect(
                EnumSet.of(
                        ModuleConflictDetector.KnownModule.TELEGAMI,
                        ModuleConflictDetector.KnownModule.TELEVIP,
                        ModuleConflictDetector.KnownModule.RE_TELEGRAM,
                        ModuleConflictDetector.KnownModule.KILLERGRAM
                ),
                true
        );

        for (int i = 1; i < report.findings.size(); i++) {
            assertTrue(report.findings.get(i - 1).severity.ordinal()
                    >= report.findings.get(i).severity.ordinal());
        }
    }

    private static boolean hasFinding(
            ModuleConflictDetector.Report report,
            ModuleConflictDetector.ConflictKind kind
    ) {
        return countFindings(report, kind) > 0;
    }

    private static int countFindings(
            ModuleConflictDetector.Report report,
            ModuleConflictDetector.ConflictKind kind
    ) {
        int count = 0;
        for (ModuleConflictDetector.Finding finding : report.findings) {
            if (finding.kind == kind) {
                count++;
            }
        }
        return count;
    }

    private static ModuleConflictDetector.Severity severityOf(
            ModuleConflictDetector.Report report,
            ModuleConflictDetector.ConflictKind kind
    ) {
        for (ModuleConflictDetector.Finding finding : report.findings) {
            if (finding.kind == kind) {
                return finding.severity;
            }
        }
        return ModuleConflictDetector.Severity.NONE;
    }
}
