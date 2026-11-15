package io.jenkins.plugins.pipelinebuildretry.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/** The retry decision: freestyle's result, regexp and limit gates plus the Pipeline-only ones. */
public final class RetryDecision {

    public static final String LOG_PREFIX = "[Build Retry] ";

    public static final String USER_INTERRUPTION = "jenkins.model.CauseOfInterruption$UserInterruption";
    public static final String TIMEOUT_INTERRUPTION =
            "org.jenkinsci.plugins.workflow.steps.TimeoutStepExecution$ExceededTimeout";
    public static final List<String> SUPERSEDED_INTERRUPTIONS = List.of(
            "com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.NewPatchSetInterruption",
            "com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.AbandonedPatchsetInterruption",
            "org.jenkinsci.plugins.workflow.support.steps.build.BuildTriggerCancelledCause");

    static final List<String> AGENT_LOSS_ERRORS =
            List.of("ClosedChannelException", "ChannelClosedException", "AgentOfflineException");

    private RetryDecision() {}

    /** Which log groups must be scanned before decide(); empty when an earlier gate already says no. */
    public static ScanPlan logScanPlan(RetryOptions options, RunSnapshot snapshot) {
        RetryOptions o = options == null ? new RetryOptions() : options;
        try {
            return planImpl(o, snapshot == null ? new RunSnapshot() : snapshot);
        } catch (RuntimeException e) {
            return new ScanPlan("build");
        }
    }

    /** Runs every gate in order; the first "no" wins. Never throws. */
    public static Decision decide(RetryOptions options, RunSnapshot snapshot) {
        RetryOptions o = options == null ? new RetryOptions() : options;
        RunSnapshot s = snapshot == null ? new RunSnapshot() : snapshot;
        try {
            return decideImpl(o, s);
        } catch (RuntimeException e) {
            return Decision.noRetry(
                    s.getResult(), "internal-error: " + e.getClass().getName());
        }
    }

    /** The one console line for a decision; never contains the regexp. */
    public static String summary(Decision d) {
        try {
            return summaryImpl(d);
        } catch (RuntimeException e) {
            return LOG_PREFIX + "no retry (summary failed: " + e.getClass().getName() + ")";
        }
    }

    /** True when an error-chain entry names a closed channel or an offline agent. */
    public static boolean isAgentLossError(String entry) {
        if (entry == null || entry.isEmpty()) {
            return false;
        }
        int open = entry.indexOf('[');
        String cls = open > 0 ? entry.substring(0, open) : entry;
        return AGENT_LOSS_ERRORS.contains(cls.substring(cls.lastIndexOf('.') + 1));
    }

    private static ScanPlan planImpl(RetryOptions o, RunSnapshot s) {
        ScanPlan plan = new ScanPlan(o.getRegexpScope());
        if (s.isAlreadyScheduled()
                || !o.validate().errors().isEmpty()
                || preGateReason(o, s) != null
                || limitReached(o, effectiveCount(s))) {
            return plan;
        }
        boolean wantAgentLoss = o.isRerunOnAgentLoss();
        switch (s.getResult()) {
            case "FAILURE", "NOT_BUILT" -> {}
            case "UNSTABLE" -> {
                if (!o.isRerunIfUnstable()) {
                    return plan;
                }
            }
            case "ABORTED" -> {
                String known = abortKind(s, false);
                boolean infraPolicy = isInfraPolicy(o);
                boolean couldBeInfra = !known.equals("user")
                        && !known.equals("superseded")
                        && !k8sIgnored(s)
                        && (infraPolicy || o.isRerunOnAgentLoss());
                if (!abortAllowed(o, known) && !couldBeInfra) {
                    return plan;
                }
                wantAgentLoss = wantAgentLoss || infraPolicy;
            }
            default -> {
                return plan;
            }
        }
        if (wantAgentLoss && !nonLogAgentLoss(s) && !k8sIgnored(s)) {
            plan.getGroups().put(ScanPlan.AGENT_LOSS, o.allAgentLossPatterns());
            plan.getExcludes().put(ScanPlan.AGENT_LOSS, RetryOptions.AGENT_LOSS_EXCLUDES);
        }
        if (o.regexpActive()) {
            plan.getGroups().put(ScanPlan.REGEXP, List.of(o.getRegexpForRerun()));
        }
        List<String> never = o.getNeverRetryPatterns();
        for (int i = 0; i < never.size(); i++) {
            plan.getGroups().put(ScanPlan.never(i + 1), List.of(never.get(i)));
        }
        return plan;
    }

    private static Decision decideImpl(RetryOptions o, RunSnapshot s) {
        RetryOptions.Validation v = o.validate();
        int rawCount = s.getRetryCount();
        int count = effectiveCount(s);
        Decision d = new Decision();
        d.result = s.getResult();
        d.retryCount = count;
        d.maxSchedule = o.getMaxSchedule();
        d.agentLoss = agentLossEvidence(s);
        d.counterReset = count == 0 && rawCount > 0;
        d.dryRun = o.isDryRun();
        d.verbose = o.isVerbose();
        d.delayType = o.getDelay() == null ? null : o.getDelay().typeName();
        d.warnings.addAll(v.warnings());
        if (d.counterReset) {
            d.addDetail("retry count " + rawCount + " reset to 0: a user started this build");
        }
        if (k8sIgnored(s)) {
            d.addDetail("agent loss ignored: kubernetes termination reasons " + String.join(", ", k8sReasons(s)));
        }

        if (s.isAlreadyScheduled()) {
            return stop(d, "already-scheduled");
        }
        if (!v.errors().isEmpty()) {
            return stop(d, "invalid-config: " + String.join("; ", v.errors()));
        }
        String pre = preGateReason(o, s);
        if (pre != null) {
            return stop(d, pre);
        }

        String resultReason = resultGate(o, s, d, count);
        if (resultReason != null) {
            return stop(d, resultReason);
        }
        d.addDetail(
                "result " + d.result + (d.abortKind != null ? " (abort: " + d.abortKind + ")" : "") + " is retryable");

        String never = neverRetryGate(o, s, d, count);
        if (never != null) {
            return stop(d, never);
        }

        if (o.isRerunOnAgentLoss() && d.agentLoss) {
            d.agentLossOverride = true;
            d.addDetail("agent-loss evidence found: regexp gate skipped");
        } else if (o.regexpActive() && s.getLogScan() == null && limitReached(o, count)) {
            d.addDetail("regexp gate: log not scanned because the retry limit is reached");
        } else if (o.regexpActive()) {
            String rx = regexpGate(s, d);
            if (rx != null) {
                return stop(d, rx);
            }
        } else if (o.isCheckRegexp()) {
            d.addDetail("regexp gate: pattern blank, every failure retries");
        }

        String max = o.getMaxSchedule() > 0 ? String.valueOf(o.getMaxSchedule()) : "unlimited";
        d.addDetail("limit: " + count + " retries done, maxSchedule " + max);
        if (limitReached(o, count)) {
            return stop(d, "max-reached");
        }

        int delay = DelaySpec.compute(o.getDelay(), count);
        d.addDetail("delay " + d.delayType + ": " + delay + "s");
        d.willRetry = true;
        d.wouldRetry = true;
        d.reason = "retry";
        d.nextRetryCount = count + 1;
        d.delaySeconds = delay;
        if (o.isDryRun()) {
            d.willRetry = false;
        }
        return d;
    }

    private static String summaryImpl(Decision d) {
        String label = d.abortKind != null ? d.result + "/" + d.abortKind : String.valueOf(d.result);
        if (!d.willRetry && !(d.dryRun && d.wouldRetry)) {
            return LOG_PREFIX + label + " → no retry (" + d.reason + ")";
        }
        String max = d.maxSchedule > 0 ? String.valueOf(d.maxSchedule) : "unlimited";
        List<String> notes = new ArrayList<>();
        if (d.regexMatched) {
            notes.add("regexp matched");
        }
        if (d.agentLossOverride) {
            notes.add("agent loss");
        }
        if (d.counterReset) {
            notes.add("count reset by user start");
        }
        if (d.dryRun) {
            notes.add("dry run");
        }
        String tail = notes.isEmpty() ? "" : " (" + String.join(", ", notes) + ")";
        String verb = d.dryRun ? "would retry" : "retry";
        return LOG_PREFIX + label + " → " + verb + " " + d.nextRetryCount + "/" + max + " in " + d.delaySeconds + "s"
                + tail;
    }

    private static String resultGate(RetryOptions o, RunSnapshot s, Decision d, int count) {
        switch (d.result) {
            case "SUCCESS":
                return "success";
            case "UNSTABLE":
                return o.isRerunIfUnstable() ? null : "unstable-not-enabled";
            case "FAILURE":
            case "NOT_BUILT":
                return null;
            case "ABORTED":
                d.abortKind = abortKind(s, true);
                if (d.abortKind.equals("superseded")) {
                    return "aborted-superseded";
                }
                if (abortAllowed(o, d.abortKind)) {
                    return null;
                }
                if (d.abortKind.equals("unknown")
                        && s.getLogScan() == null
                        && limitReached(o, count)
                        && isInfraPolicy(o)) {
                    d.addDetail("abort kind: log not scanned because the retry limit is reached");
                    return null;
                }
                return "aborted-" + d.abortKind + "-not-enabled";
            default:
                return "result-not-retryable: " + d.result;
        }
    }

    private static String regexpGate(RunSnapshot s, Decision d) {
        d.regexChecked = true;
        ScanResult scan = s.getLogScan();
        if (scan == null) {
            d.addDetail("regexp gate: log was not scanned");
            return "regexp-not-scanned";
        }
        if (scan.isMatched(ScanPlan.REGEXP)) {
            d.regexMatched = true;
            d.addDetail("regexp gate: found in log");
            return null;
        }
        // Freestyle parity: a read error inside the scan thread means no retry.
        String reason = scan.isPatternError()
                ? "regexp-invalid"
                : scan.isTimedOut()
                        ? "regexp-timeout"
                        : scan.isIoError() ? "regexp-log-unreadable" : "regexp-not-found";
        d.addDetail("regexp gate: " + reason);
        return reason;
    }

    private static String neverRetryGate(RetryOptions o, RunSnapshot s, Decision d, int count) {
        List<String> patterns = o.getNeverRetryPatterns();
        if (patterns.isEmpty()) {
            return null;
        }
        ScanResult scan = s.getLogScan();
        if (scan == null) {
            if (!limitReached(o, count)) {
                d.addDetail("never-retry gate: not applied (log not scanned)");
            }
            return null;
        }
        d.neverRetryChecked = true;
        for (int i = 1; i <= patterns.size(); i++) {
            String group = ScanPlan.never(i);
            if (scan.isMatched(group)) {
                d.neverRetryPattern = i;
                d.addDetail("never-retry gate: pattern #" + i + " found in log");
                return "never-retry-matched: pattern #" + i + where(scan, scan.getMatchLine(group));
            }
        }
        if (scan.isTimedOut() || scan.isIoError() || scan.isPatternError()) {
            String why = scan.isTimedOut() ? "scan timed out" : scan.isIoError() ? "log unreadable" : "pattern error";
            d.addDetail("never-retry gate: not applied (" + why + ")");
        } else {
            d.addDetail("never-retry gate: no pattern found");
        }
        return null;
    }

    // Lines after the console are the appended stage error lines; a console not read to its end holds the match.
    private static String where(ScanResult scan, Integer line) {
        if ("failedStages".equals(scan.getScope())) {
            return " in the failed stages' logs";
        }
        if (line == null) {
            return "";
        }
        if (scan.getConsoleLines() >= 0 && line > scan.getConsoleLines()) {
            return " in a stage error line";
        }
        return " at console line " + line;
    }

    private static String preGateReason(RetryOptions o, RunSnapshot s) {
        if (s.getPointOfNoReturn() != null) {
            return "point-of-no-return: " + s.getPointOfNoReturn();
        }
        if (s.isReplay() && o.isSkipReplays()) {
            return "replay-skipped";
        }
        return null;
    }

    // User beats superseded beats infra beats timeout: a person pressing abort is never "the agent died".
    private static String abortKind(RunSnapshot s, boolean useLog) {
        List<String> causes = s.getInterruptionCauses();
        if (causes.contains(USER_INTERRUPTION)) {
            return "user";
        }
        if (causes.stream().anyMatch(SUPERSEDED_INTERRUPTIONS::contains)) {
            return "superseded";
        }
        if (!k8sIgnored(s) && (nonLogAgentLoss(s) || (useLog && logAgentLoss(s)))) {
            return "infra";
        }
        return causes.contains(TIMEOUT_INTERRUPTION) ? "timeout" : "unknown";
    }

    private static boolean abortAllowed(RetryOptions o, String kind) {
        if (kind.equals("superseded")) {
            return false;
        }
        if (o.isRerunOnAgentLoss() && kind.equals("infra")) {
            return true;
        }
        return switch (o.getRerunIfAborted()) {
            case "always" -> true;
            case "infra" -> kind.equals("infra");
            case "timeout" -> kind.equals("timeout");
            case "infraOrTimeout" -> kind.equals("infra") || kind.equals("timeout");
            default -> false;
        };
    }

    private static boolean isInfraPolicy(RetryOptions o) {
        return o.getRerunIfAborted().equals("infra") || o.getRerunIfAborted().equals("infraOrTimeout");
    }

    static boolean agentLossEvidence(RunSnapshot s) {
        return !k8sIgnored(s) && (nonLogAgentLoss(s) || logAgentLoss(s));
    }

    private static boolean nonLogAgentLoss(RunSnapshot s) {
        return s.isRetryableCauseSeen()
                || s.isAgentContextLost()
                || s.getErrorClasses().stream().anyMatch(RetryDecision::isAgentLossError);
    }

    private static boolean logAgentLoss(RunSnapshot s) {
        return s.getLogScan() != null && s.getLogScan().isMatched(ScanPlan.AGENT_LOSS);
    }

    // Same rule as the kubernetes plugin's agent error condition: skip only when every reason is ignored.
    static boolean k8sIgnored(RunSnapshot s) {
        if (!s.getFailedAgentReasons().isEmpty()) {
            return s.getFailedAgentReasons().values().stream().allMatch(RetryDecision::allIgnored);
        }
        return allIgnored(s.getTerminationReasons());
    }

    private static boolean allIgnored(Collection<String> reasons) {
        return reasons != null
                && !reasons.isEmpty()
                && reasons.stream().allMatch(RetryOptions.K8S_IGNORED_TERMINATION_REASONS::contains);
    }

    private static List<String> k8sReasons(RunSnapshot s) {
        if (!s.getFailedAgentReasons().isEmpty()) {
            TreeSet<String> all = new TreeSet<>();
            s.getFailedAgentReasons().values().forEach(all::addAll);
            return new ArrayList<>(all);
        }
        return s.getTerminationReasons();
    }

    private static boolean limitReached(RetryOptions o, int count) {
        return o.getMaxSchedule() > 0 && count >= o.getMaxSchedule();
    }

    // A retry copies the original causes, so its user cause must not reset the count again.
    private static int effectiveCount(RunSnapshot s) {
        return s.isStartedByUser() && !s.isRetry() ? 0 : s.getRetryCount();
    }

    private static Decision stop(Decision d, String reason) {
        d.reason = reason;
        return d;
    }
}
