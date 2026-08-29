package com.tianqianguai.gramsieve.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Evaluates potential conflicts with known Telegram modules.
 *
 * <p>This class evaluates the module set supplied by the caller. The caller may conservatively
 * pass installed packages or narrow them using confirmed LSPosed enablement and scope state.</p>
 */
public final class ModuleConflictDetector {
    private ModuleConflictDetector() {
    }

    public enum Severity {
        NONE,
        LOW,
        MEDIUM,
        HIGH
    }

    public enum ConflictKind {
        ANTI_RECALL,
        EDIT_HISTORY,
        DOWNLOAD_ACCELERATION,
        SECRET_MEDIA,
        SAVE_RESTRICTION,
        ADS,
        STORIES,
        PRIVACY,
        UI_INJECTION
    }

    public enum KnownModule {
        TELEGAMI(
                "Telegami",
                new String[]{"com.aoya.telegami"},
                ConflictKind.ANTI_RECALL,
                ConflictKind.DOWNLOAD_ACCELERATION,
                ConflictKind.SECRET_MEDIA,
                ConflictKind.SAVE_RESTRICTION,
                ConflictKind.ADS,
                ConflictKind.PRIVACY,
                ConflictKind.UI_INJECTION
        ),
        TELEVIP(
                "TeleVip",
                new String[]{"com.my.televip"},
                ConflictKind.ANTI_RECALL,
                ConflictKind.EDIT_HISTORY,
                ConflictKind.DOWNLOAD_ACCELERATION,
                ConflictKind.SECRET_MEDIA,
                ConflictKind.SAVE_RESTRICTION,
                ConflictKind.STORIES,
                ConflictKind.PRIVACY,
                ConflictKind.UI_INJECTION
        ),
        RE_TELEGRAM(
                "Re:Telegram",
                new String[]{"nep.timeline.re_telegram"},
                ConflictKind.ANTI_RECALL,
                ConflictKind.DOWNLOAD_ACCELERATION,
                ConflictKind.SAVE_RESTRICTION,
                ConflictKind.ADS,
                ConflictKind.STORIES
        ),
        KILLERGRAM(
                "Killergram",
                new String[]{"com.shatyuka.killergram"},
                ConflictKind.SAVE_RESTRICTION,
                ConflictKind.ADS
        ),
        TELEGRAM_SPEED_HOOK(
                "Telegram Speed Hook",
                new String[]{"Telegram.Speed.Hook"},
                ConflictKind.DOWNLOAD_ACCELERATION
        ),
        TELEGRAM_TWEAKS(
                "Telegram Tweaks",
                new String[]{"ru.mike.telegramtweaks", "ru.mike.sidestories"},
                ConflictKind.STORIES
        ),
        TAUXILIARY(
                "TAuxiliary",
                new String[]{"org.telegram.auxiliary"},
                ConflictKind.ANTI_RECALL,
                ConflictKind.DOWNLOAD_ACCELERATION,
                ConflictKind.UI_INJECTION
        );

        public final String displayName;
        public final List<String> packageNames;
        private final Set<ConflictKind> conflictKinds;

        KnownModule(String displayName, String[] packageNames, ConflictKind... conflictKinds) {
            this.displayName = displayName;
            List<String> aliases = new ArrayList<>();
            Collections.addAll(aliases, packageNames);
            this.packageNames = Collections.unmodifiableList(aliases);
            EnumSet<ConflictKind> values = EnumSet.noneOf(ConflictKind.class);
            Collections.addAll(values, conflictKinds);
            this.conflictKinds = Collections.unmodifiableSet(values);
        }

        public boolean has(ConflictKind kind) {
            return kind != null && conflictKinds.contains(kind);
        }

        public Set<ConflictKind> conflictKinds() {
            return conflictKinds;
        }
    }

    public static final class Finding {
        public final ConflictKind kind;
        public final Severity severity;
        public final boolean includesGramSieve;
        public final Set<KnownModule> modules;

        private Finding(
                ConflictKind kind,
                Severity severity,
                boolean includesGramSieve,
                Set<KnownModule> modules
        ) {
            this.kind = kind;
            this.severity = severity;
            this.includesGramSieve = includesGramSieve;
            this.modules = Collections.unmodifiableSet(EnumSet.copyOf(modules));
        }
    }

    public static final class Report {
        public final Set<KnownModule> installedModules;
        public final List<Finding> findings;
        public final Severity highestSeverity;

        private Report(Set<KnownModule> installedModules, List<Finding> findings) {
            this.installedModules = installedModules.isEmpty()
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(EnumSet.copyOf(installedModules));
            List<Finding> orderedFindings = new ArrayList<>(findings);
            orderedFindings.sort((left, right) -> {
                int severityOrder = Integer.compare(
                        right.severity.ordinal(),
                        left.severity.ordinal()
                );
                return severityOrder != 0
                        ? severityOrder
                        : Integer.compare(left.kind.ordinal(), right.kind.ordinal());
            });
            this.findings = Collections.unmodifiableList(orderedFindings);
            Severity highest = Severity.NONE;
            for (Finding finding : findings) {
                if (finding.severity.ordinal() > highest.ordinal()) {
                    highest = finding.severity;
                }
            }
            this.highestSeverity = highest;
        }
    }

    public static Set<KnownModule> identifyInstalledModules(Set<String> installedPackageNames) {
        if (installedPackageNames == null || installedPackageNames.isEmpty()) {
            return Collections.emptySet();
        }
        EnumSet<KnownModule> installedModules = EnumSet.noneOf(KnownModule.class);
        for (KnownModule module : KnownModule.values()) {
            for (String packageName : module.packageNames) {
                if (installedPackageNames.contains(packageName)) {
                    installedModules.add(module);
                    break;
                }
            }
        }
        return Collections.unmodifiableSet(installedModules);
    }

    public static Report detect(Set<KnownModule> installedModules, boolean gramSieveActiveForTelegram) {
        EnumSet<KnownModule> installed = installedModules == null || installedModules.isEmpty()
                ? EnumSet.noneOf(KnownModule.class)
                : EnumSet.copyOf(installedModules);
        List<Finding> findings = new ArrayList<>();

        EnumSet<KnownModule> antiRecallModules = matchingModules(installed, ConflictKind.ANTI_RECALL);
        int antiRecallOwners = antiRecallModules.size() + (gramSieveActiveForTelegram ? 1 : 0);
        if (antiRecallOwners >= 2) {
            findings.add(new Finding(
                    ConflictKind.ANTI_RECALL,
                    Severity.HIGH,
                    gramSieveActiveForTelegram,
                    antiRecallModules
            ));
        }
        if (gramSieveActiveForTelegram) {
            EnumSet<KnownModule> editHistoryModules = EnumSet.noneOf(KnownModule.class);
            if (installed.contains(KnownModule.TELEVIP)) {
                editHistoryModules.add(KnownModule.TELEVIP);
            }
            if (!editHistoryModules.isEmpty()) {
                findings.add(new Finding(
                        ConflictKind.EDIT_HISTORY,
                        Severity.MEDIUM,
                        true,
                        editHistoryModules
                ));
            }
        }

        addWhenPresent(findings, installed, ConflictKind.DOWNLOAD_ACCELERATION,
                gramSieveActiveForTelegram ? 1 : 2,
                ConflictKind.DOWNLOAD_ACCELERATION, Severity.HIGH, gramSieveActiveForTelegram);
        addWhenPresent(findings, installed, ConflictKind.SECRET_MEDIA,
                gramSieveActiveForTelegram ? 1 : 2,
                ConflictKind.SECRET_MEDIA, Severity.HIGH, gramSieveActiveForTelegram);
        addWhenPresent(findings, installed, ConflictKind.SAVE_RESTRICTION,
                gramSieveActiveForTelegram ? 1 : 2,
                ConflictKind.SAVE_RESTRICTION, Severity.LOW, gramSieveActiveForTelegram);
        addWhenPresent(findings, installed, ConflictKind.ADS,
                gramSieveActiveForTelegram ? 1 : 2,
                ConflictKind.ADS, Severity.LOW, gramSieveActiveForTelegram);
        addWhenPresent(findings, installed, ConflictKind.STORIES,
                gramSieveActiveForTelegram ? 1 : 2,
                ConflictKind.STORIES, Severity.MEDIUM, gramSieveActiveForTelegram);
        addWhenPresent(findings, installed, ConflictKind.PRIVACY,
                gramSieveActiveForTelegram ? 1 : 2,
                ConflictKind.PRIVACY, Severity.MEDIUM, gramSieveActiveForTelegram);
        if (gramSieveActiveForTelegram) {
            addWhenPresent(findings, installed, ConflictKind.UI_INJECTION, 1,
                    ConflictKind.UI_INJECTION, Severity.MEDIUM, true);
        }

        return new Report(installed, findings);
    }

    private static void addWhenPresent(
            List<Finding> findings,
            Set<KnownModule> installed,
            ConflictKind capabilityKind,
            int minimumCount,
            ConflictKind findingKind,
            Severity severity,
            boolean includesGramSieve
    ) {
        EnumSet<KnownModule> matches = matchingModules(installed, capabilityKind);
        if (matches.size() >= minimumCount) {
            findings.add(new Finding(findingKind, severity, includesGramSieve, matches));
        }
    }

    private static EnumSet<KnownModule> matchingModules(
            Set<KnownModule> installed,
            ConflictKind kind
    ) {
        EnumSet<KnownModule> matches = EnumSet.noneOf(KnownModule.class);
        for (KnownModule module : installed) {
            if (module.has(kind)) {
                matches.add(module);
            }
        }
        return matches;
    }
}
