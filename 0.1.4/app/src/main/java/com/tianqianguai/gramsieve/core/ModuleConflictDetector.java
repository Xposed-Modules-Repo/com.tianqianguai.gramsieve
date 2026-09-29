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

    private enum Capability {
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
                Capability.ANTI_RECALL,
                Capability.DOWNLOAD_ACCELERATION,
                Capability.SECRET_MEDIA,
                Capability.SAVE_RESTRICTION,
                Capability.ADS,
                Capability.PRIVACY,
                Capability.UI_INJECTION
        ),
        TELEVIP(
                "TeleVip",
                new String[]{"com.my.televip"},
                Capability.ANTI_RECALL,
                Capability.EDIT_HISTORY,
                Capability.DOWNLOAD_ACCELERATION,
                Capability.SECRET_MEDIA,
                Capability.SAVE_RESTRICTION,
                Capability.STORIES,
                Capability.PRIVACY,
                Capability.UI_INJECTION
        ),
        RE_TELEGRAM(
                "Re:Telegram",
                new String[]{"nep.timeline.re_telegram"},
                Capability.ANTI_RECALL,
                Capability.DOWNLOAD_ACCELERATION,
                Capability.SAVE_RESTRICTION,
                Capability.ADS,
                Capability.STORIES
        ),
        KILLERGRAM(
                "Killergram",
                new String[]{"com.shatyuka.killergram"},
                Capability.SAVE_RESTRICTION,
                Capability.ADS
        ),
        TELEGRAM_SPEED_HOOK(
                "Telegram Speed Hook",
                new String[]{"Telegram.Speed.Hook"},
                Capability.DOWNLOAD_ACCELERATION
        ),
        TELEGRAM_TWEAKS(
                "Telegram Tweaks",
                new String[]{"ru.mike.telegramtweaks", "ru.mike.sidestories"},
                Capability.STORIES
        );

        public final String displayName;
        public final List<String> packageNames;
        private final Set<Capability> capabilities;

        KnownModule(String displayName, String[] packageNames, Capability... capabilities) {
            this.displayName = displayName;
            List<String> aliases = new ArrayList<>();
            Collections.addAll(aliases, packageNames);
            this.packageNames = Collections.unmodifiableList(aliases);
            EnumSet<Capability> values = EnumSet.noneOf(Capability.class);
            Collections.addAll(values, capabilities);
            this.capabilities = Collections.unmodifiableSet(values);
        }

        private boolean has(Capability capability) {
            return capabilities.contains(capability);
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

        EnumSet<KnownModule> antiRecallModules = matchingModules(installed, Capability.ANTI_RECALL);
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
            if (installed.contains(KnownModule.TELEVIP)) {
                findings.add(new Finding(
                        ConflictKind.EDIT_HISTORY,
                        Severity.MEDIUM,
                        true,
                        EnumSet.of(KnownModule.TELEVIP)
                ));
            }
        }

        addWhenPresent(findings, installed, Capability.DOWNLOAD_ACCELERATION, 2,
                ConflictKind.DOWNLOAD_ACCELERATION, Severity.HIGH, false);
        addWhenPresent(findings, installed, Capability.SECRET_MEDIA, 2,
                ConflictKind.SECRET_MEDIA, Severity.HIGH, false);
        addWhenPresent(findings, installed, Capability.SAVE_RESTRICTION, 2,
                ConflictKind.SAVE_RESTRICTION, Severity.LOW, false);
        addWhenPresent(findings, installed, Capability.ADS, 2,
                ConflictKind.ADS, Severity.LOW, false);
        addWhenPresent(findings, installed, Capability.STORIES, 2,
                ConflictKind.STORIES, Severity.MEDIUM, false);
        addWhenPresent(findings, installed, Capability.PRIVACY, 2,
                ConflictKind.PRIVACY, Severity.MEDIUM, false);
        if (gramSieveActiveForTelegram) {
            addWhenPresent(findings, installed, Capability.UI_INJECTION, 1,
                    ConflictKind.UI_INJECTION, Severity.MEDIUM, true);
        }

        return new Report(installed, findings);
    }

    private static void addWhenPresent(
            List<Finding> findings,
            Set<KnownModule> installed,
            Capability capability,
            int minimumCount,
            ConflictKind kind,
            Severity severity,
            boolean includesGramSieve
    ) {
        EnumSet<KnownModule> matches = matchingModules(installed, capability);
        if (matches.size() >= minimumCount) {
            findings.add(new Finding(kind, severity, includesGramSieve, matches));
        }
    }

    private static EnumSet<KnownModule> matchingModules(
            Set<KnownModule> installed,
            Capability capability
    ) {
        EnumSet<KnownModule> matches = EnumSet.noneOf(KnownModule.class);
        for (KnownModule module : installed) {
            if (module.has(capability)) {
                matches.add(module);
            }
        }
        return matches;
    }
}
