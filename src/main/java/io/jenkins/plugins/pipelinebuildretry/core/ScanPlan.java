package io.jenkins.plugins.pipelinebuildretry.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Which log pattern groups a decision needs scanned, with their excludes and the regexp scope. */
public final class ScanPlan {

    public static final String REGEXP = "regexp";
    public static final String AGENT_LOSS = "agentLoss";
    private static final String NEVER = "neverRetry";

    /** The group name of never-retry pattern {@code index}, counted from 1. */
    public static String never(int index) {
        return NEVER + index;
    }

    /** Whether {@code group} is a never-retry group. */
    public static boolean isNever(String group) {
        return group != null && group.startsWith(NEVER);
    }

    private final Map<String, List<String>> groups = new LinkedHashMap<>();
    private final Map<String, List<String>> excludes = new LinkedHashMap<>();
    private final String regexpScope;

    public ScanPlan(String regexpScope) {
        this.regexpScope = regexpScope == null ? "build" : regexpScope;
    }

    public Map<String, List<String>> getGroups() {
        return groups;
    }

    public Map<String, List<String>> getExcludes() {
        return excludes;
    }

    public String getRegexpScope() {
        return regexpScope;
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }
}
