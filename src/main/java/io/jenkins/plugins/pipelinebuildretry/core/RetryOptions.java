package io.jenkins.plugins.pipelinebuildretry.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Retry options with freestyle-parity defaults, plus their validation; a copy is recorded with a preview. */
public final class RetryOptions implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int DEFAULT_REGEXP_TIMEOUT_MS = 30000;

    public static final List<String> DEFAULT_AGENT_LOSS_PATTERNS = List.of(
            "Agent .+ was deleted; cancelling node body",
            "seems to be removed or offline .*; will wait for",
            "has been removed or offline for .*; assuming it is not coming back",
            "Pod .+ was just deleted",
            "Pod just failed\\.",
            "Pod was evicted by the Kubernetes Eviction API",
            "hudson\\.remoting\\.ChannelClosedException",
            "Containers? \\S+ (was|were) terminated\\.");

    // Same reasons the kubernetes plugin's own agent error condition refuses to retry on.
    public static final List<String> K8S_IGNORED_TERMINATION_REASONS =
            List.of("OOMKilled", "Completed", "DeadlineExceeded");

    // The pod reason printed on a Reaper line; an eviction on the same line still counts.
    public static final List<String> AGENT_LOSS_EXCLUDES =
            List.of("Reason: (OOMKilled|Completed|DeadlineExceeded)\\b(?!.*Pod was evicted)");

    // Passing steps whose output is file and report bookkeeping; a failing one is still scanned.
    public static final List<String> HOUSEKEEPING_STEPS = List.of(
            "archiveArtifacts",
            "archive",
            "stash",
            "unstash",
            "cleanWs",
            "deleteDir",
            "junit",
            "fingerprint",
            "copyArtifacts",
            "publishHTML");

    // Java stack frames; "Caused by:" lines are kept, because they name the real error.
    public static final List<String> STACK_FRAME_EXCLUDES =
            List.of("^\\s*at [\\w$.<>/-]+\\(.*\\)\\s*$", "^\\s*\\.\\.\\. \\d+ more\\s*$");

    public static final int MAX_LINE_CHARS = 65536;
    public static final int LINE_OVERLAP_CHARS = 1024;
    public static final long HEAP_MIN_HEADROOM_BYTES = 256L << 20;
    public static final int HEAP_MIN_HEADROOM_PERCENT = 10;
    public static final long HEAP_WAIT_MAX_MS = 5000L;
    public static final long HEAP_POLL_MS = 250L;

    public static final List<String> ABORT_POLICIES = List.of("never", "infra", "timeout", "infraOrTimeout", "always");
    public static final List<String> REGEXP_SCOPES = List.of("build", "failedStages");
    public static final List<String> GERRIT_CAUSE_MODES = List.of("drop", "keep");
    public static final int MAX_NEVER_RETRY_PATTERNS = 20;

    private boolean rerunIfUnstable = false;
    private DelaySpec delay = DelaySpec.defaultDelay();
    private int maxSchedule = 0;
    private boolean checkRegexp = false;
    private String regexpForRerun = "";
    private Integer regexpTimeoutMs = null;
    private String regexpScope = "build";
    private boolean badge = true;
    private String rerunIfAborted = "never";
    private boolean rerunOnAgentLoss = false;
    private ArrayList<String> agentLossPatterns = new ArrayList<>(DEFAULT_AGENT_LOSS_PATTERNS);
    private ArrayList<String> agentLossPatternsExtra = new ArrayList<>();
    private ArrayList<String> neverRetryPatterns = new ArrayList<>();
    private boolean skipReplays = true;
    private String gerritCause = "drop";
    private ArrayList<String> dropCauses = new ArrayList<>();
    private LinkedHashMap<String, String> parameterOverrides = new LinkedHashMap<>();
    private boolean dryRun = false;
    private boolean verbose = false;

    /** Errors stop every retry ("invalid-config"); warnings are printed and change nothing. */
    public record Validation(List<String> errors, List<String> warnings) {}

    /** Returns a deep copy, safe to record on a run. */
    public RetryOptions copy() {
        RetryOptions c = new RetryOptions();
        c.rerunIfUnstable = rerunIfUnstable;
        c.delay = delay;
        c.maxSchedule = maxSchedule;
        c.checkRegexp = checkRegexp;
        c.regexpForRerun = regexpForRerun;
        c.regexpTimeoutMs = regexpTimeoutMs;
        c.regexpScope = regexpScope;
        c.badge = badge;
        c.rerunIfAborted = rerunIfAborted;
        c.rerunOnAgentLoss = rerunOnAgentLoss;
        c.agentLossPatterns = new ArrayList<>(agentLossPatterns);
        c.agentLossPatternsExtra = new ArrayList<>(agentLossPatternsExtra);
        c.neverRetryPatterns = new ArrayList<>(getNeverRetryPatterns());
        c.skipReplays = skipReplays;
        c.gerritCause = gerritCause;
        c.dropCauses = new ArrayList<>(dropCauses);
        c.parameterOverrides = new LinkedHashMap<>(parameterOverrides);
        c.dryRun = dryRun;
        c.verbose = verbose;
        return c;
    }

    /** Every problem with these options; never throws and never echoes a pattern. */
    public Validation validate() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!ABORT_POLICIES.contains(rerunIfAborted)) {
            errors.add("rerunIfAborted must be one of " + quoted(ABORT_POLICIES));
        }
        if (!REGEXP_SCOPES.contains(regexpScope)) {
            errors.add("regexpScope must be one of " + quoted(REGEXP_SCOPES));
        }
        if (!GERRIT_CAUSE_MODES.contains(gerritCause)) {
            errors.add("gerritCause must be one of " + quoted(GERRIT_CAUSE_MODES));
        }
        if (regexpTimeoutMs != null && regexpTimeoutMs < 1) {
            errors.add("regexpTimeoutMs must be at least 1");
        }
        if (delay == null) {
            errors.add("delay must be set");
        } else {
            errors.addAll(delay.errors());
        }
        if (!regexpForRerun.isEmpty()) {
            checkPattern("regexpForRerun", regexpForRerun, errors);
        }
        List<String> loss = new ArrayList<>(agentLossPatterns);
        loss.addAll(agentLossPatternsExtra);
        for (int i = 0; i < loss.size(); i++) {
            checkPattern("agent-loss pattern #" + (i + 1), loss.get(i), errors);
        }
        List<String> never = getNeverRetryPatterns();
        if (never.size() > MAX_NEVER_RETRY_PATTERNS) {
            errors.add(tooManyNeverRetryPatterns(never.size()));
        }
        for (int i = 0; i < never.size(); i++) {
            checkPattern("never-retry pattern #" + (i + 1), never.get(i), errors);
        }
        for (String name : parameterOverrides.keySet()) {
            if (name == null || name.isBlank()) {
                errors.add("parameterOverrides has a blank parameter name");
            }
        }
        if (maxSchedule <= 0) {
            warnings.add("maxSchedule=" + maxSchedule + " means unlimited retries; set maxSchedule to cap them");
        }
        if (checkRegexp && regexpForRerun.trim().isEmpty()) {
            warnings.add("checkRegexp is on but regexpForRerun is blank, so every failure retries");
        }
        if (!checkRegexp && !regexpForRerun.trim().isEmpty()) {
            warnings.add("regexpForRerun is set but checkRegexp is false, so it is ignored");
        }
        return new Validation(errors, warnings);
    }

    /** Explicit option first, then the global setting, then 30000 ms. */
    public long effectiveRegexpTimeoutMs(Number globalValue) {
        if (regexpTimeoutMs != null) {
            return regexpTimeoutMs.longValue();
        }
        if (globalValue != null && globalValue.longValue() > 0) {
            return globalValue.longValue();
        }
        return DEFAULT_REGEXP_TIMEOUT_MS;
    }

    /** True when the regexp gate is on and has a pattern. */
    public boolean regexpActive() {
        return checkRegexp && !regexpForRerun.trim().isEmpty();
    }

    /** The configured agent-loss patterns followed by the extra ones. */
    public List<String> allAgentLossPatterns() {
        List<String> all = new ArrayList<>(agentLossPatterns);
        all.addAll(agentLossPatternsExtra);
        return all;
    }

    /** The error for a never-retry list over the cap. */
    public static String tooManyNeverRetryPatterns(int count) {
        return "neverRetryPatterns has " + count + " patterns; at most " + MAX_NEVER_RETRY_PATTERNS + " are allowed";
    }

    // The pattern may hold a secret, so the error names the option and never the text.
    private static void checkPattern(String label, String pattern, List<String> errors) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            errors.add(label + " is not a valid regular expression (" + e.getDescription() + " near index "
                    + e.getIndex() + ")");
        }
    }

    private static String quoted(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            sb.append(sb.length() == 0 ? "" : ", ").append('\'').append(v).append('\'');
        }
        return sb.toString();
    }

    public boolean isRerunIfUnstable() {
        return rerunIfUnstable;
    }

    public RetryOptions rerunIfUnstable(boolean v) {
        rerunIfUnstable = v;
        return this;
    }

    public DelaySpec getDelay() {
        return delay;
    }

    public RetryOptions delay(DelaySpec v) {
        delay = v;
        return this;
    }

    public int getMaxSchedule() {
        return maxSchedule;
    }

    public RetryOptions maxSchedule(int v) {
        maxSchedule = v;
        return this;
    }

    public boolean isCheckRegexp() {
        return checkRegexp;
    }

    public RetryOptions checkRegexp(boolean v) {
        checkRegexp = v;
        return this;
    }

    public String getRegexpForRerun() {
        return regexpForRerun;
    }

    public RetryOptions regexpForRerun(String v) {
        regexpForRerun = v == null ? "" : v;
        return this;
    }

    public Integer getRegexpTimeoutMs() {
        return regexpTimeoutMs;
    }

    public RetryOptions regexpTimeoutMs(Integer v) {
        regexpTimeoutMs = v;
        return this;
    }

    public String getRegexpScope() {
        return regexpScope;
    }

    public RetryOptions regexpScope(String v) {
        regexpScope = v == null ? "build" : v;
        return this;
    }

    public boolean isBadge() {
        return badge;
    }

    public RetryOptions badge(boolean v) {
        badge = v;
        return this;
    }

    public String getRerunIfAborted() {
        return rerunIfAborted;
    }

    public RetryOptions rerunIfAborted(String v) {
        rerunIfAborted = v == null ? "never" : v;
        return this;
    }

    public boolean isRerunOnAgentLoss() {
        return rerunOnAgentLoss;
    }

    public RetryOptions rerunOnAgentLoss(boolean v) {
        rerunOnAgentLoss = v;
        return this;
    }

    public List<String> getAgentLossPatterns() {
        return Collections.unmodifiableList(agentLossPatterns);
    }

    public RetryOptions agentLossPatterns(List<String> v) {
        agentLossPatterns = v == null ? new ArrayList<>(DEFAULT_AGENT_LOSS_PATTERNS) : new ArrayList<>(v);
        return this;
    }

    public List<String> getAgentLossPatternsExtra() {
        return Collections.unmodifiableList(agentLossPatternsExtra);
    }

    public RetryOptions agentLossPatternsExtra(List<String> v) {
        agentLossPatternsExtra = v == null ? new ArrayList<>() : new ArrayList<>(v);
        return this;
    }

    public List<String> getNeverRetryPatterns() {
        return neverRetryPatterns == null ? List.of() : Collections.unmodifiableList(neverRetryPatterns);
    }

    public RetryOptions neverRetryPatterns(List<String> v) {
        neverRetryPatterns = v == null ? new ArrayList<>() : new ArrayList<>(v);
        return this;
    }

    public boolean isSkipReplays() {
        return skipReplays;
    }

    public RetryOptions skipReplays(boolean v) {
        skipReplays = v;
        return this;
    }

    public String getGerritCause() {
        return gerritCause;
    }

    public RetryOptions gerritCause(String v) {
        gerritCause = v == null ? "drop" : v;
        return this;
    }

    public List<String> getDropCauses() {
        return Collections.unmodifiableList(dropCauses);
    }

    public RetryOptions dropCauses(List<String> v) {
        dropCauses = v == null ? new ArrayList<>() : new ArrayList<>(v);
        return this;
    }

    public Map<String, String> getParameterOverrides() {
        return Collections.unmodifiableMap(parameterOverrides);
    }

    public RetryOptions parameterOverrides(Map<String, String> v) {
        parameterOverrides = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
        return this;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public RetryOptions dryRun(boolean v) {
        dryRun = v;
        return this;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public RetryOptions verbose(boolean v) {
        verbose = v;
        return this;
    }
}
