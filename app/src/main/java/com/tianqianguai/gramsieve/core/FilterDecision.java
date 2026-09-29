package com.tianqianguai.gramsieve.core;

public final class FilterDecision {
    public final boolean matched;
    public final boolean excluded;
    public final FilterConfig.Action action;
    public final String ruleId;
    public final String reason;

    private FilterDecision(boolean matched, boolean excluded, FilterConfig.Action action, String ruleId, String reason) {
        this.matched = matched;
        this.excluded = excluded;
        this.action = action;
        this.ruleId = ruleId;
        this.reason = reason;
    }

    public static FilterDecision allow() {
        return new FilterDecision(false, false, FilterConfig.Action.HIDE, "", "");
    }

    public static FilterDecision excluded(String ruleId, String reason) {
        return new FilterDecision(false, true, FilterConfig.Action.HIDE, ruleId, reason);
    }

    public static FilterDecision matched(FilterConfig.Action action, String ruleId, String reason) {
        return new FilterDecision(true, false, action, ruleId, reason);
    }
}
