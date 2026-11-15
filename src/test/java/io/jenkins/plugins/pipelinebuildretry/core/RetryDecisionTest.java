package io.jenkins.plugins.pipelinebuildretry.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetryDecisionTest {

    private static final String USER = RetryDecision.USER_INTERRUPTION;
    private static final String TIMEOUT = RetryDecision.TIMEOUT_INTERRUPTION;

    private static RetryOptions opts() {
        return new RetryOptions();
    }

    private static RetryOptions rx() {
        return opts().checkRegexp(true).regexpForRerun("SECRET_PATTERN");
    }

    private static RunSnapshot snap() {
        return new RunSnapshot().result("FAILURE");
    }

    private static RunSnapshot aborted() {
        return snap().result("ABORTED");
    }

    private static ScanResult logWith(Map<String, Boolean> matched) {
        return ScanResult.matched(matched);
    }

    private static Decision decide(RetryOptions o, RunSnapshot s) {
        return RetryDecision.decide(o, s);
    }

    private static Decision decide(RetryOptions o) {
        return decide(o, snap());
    }

    private static boolean hasDetail(Decision d, String text) {
        return d.getDetails().stream().anyMatch(l -> l.contains(text));
    }

    @Test
    void resultGate() {
        Decision d1 = decide(opts());
        assertTrue(d1.isWillRetry(), "FAILURE retries by default");
        assertEquals(300, d1.getDelaySeconds(), "first retry waits 300s");
        assertEquals(1, d1.getNextRetryCount(), "next retry count is 1");
        assertEquals("retry", d1.getReason(), "reason is retry");
        assertEquals("success", decide(opts(), snap().result("SUCCESS")).getReason(), "SUCCESS never retries");
        assertEquals(
                "unstable-not-enabled",
                decide(opts(), snap().result("UNSTABLE")).getReason());
        assertTrue(
                decide(opts().rerunIfUnstable(true), snap().result("UNSTABLE")).isWillRetry(), "UNSTABLE opt-in");
        assertTrue(decide(opts(), snap().result("NOT_BUILT")).isWillRetry(), "NOT_BUILT retries");
        Decision weird = decide(opts(), snap().result("WEIRD"));
        assertFalse(weird.isWillRetry(), "unknown result never retries");
        assertEquals("result-not-retryable: WEIRD", weird.getReason());
        assertEquals("success", RetryDecision.decide(opts(), null).getReason(), "null snapshot is safe");
        assertEquals("retry", RetryDecision.decide(null, snap()).getReason(), "null options use the defaults");
    }

    @Test
    void abortKinds() {
        Decision a0 = decide(opts(), aborted());
        assertEquals("aborted-unknown-not-enabled", a0.getReason(), "ABORTED never retries by default");
        assertEquals(
                "user",
                decide(opts(), aborted().interruptionCauses(List.of(USER))).getAbortKind());
        assertFalse(decide(opts().rerunIfAborted("infra"), aborted().interruptionCauses(List.of(USER)))
                .isWillRetry());
        assertTrue(decide(opts().rerunIfAborted("always"), aborted().interruptionCauses(List.of(USER)))
                .isWillRetry());
        assertTrue(decide(opts().rerunIfAborted("timeout"), aborted().interruptionCauses(List.of(TIMEOUT)))
                .isWillRetry());
        assertFalse(decide(opts().rerunIfAborted("infra"), aborted().interruptionCauses(List.of(TIMEOUT)))
                .isWillRetry());
        assertTrue(decide(opts().rerunIfAborted("infraOrTimeout"), aborted().interruptionCauses(List.of(TIMEOUT)))
                .isWillRetry());
        assertTrue(decide(opts().rerunIfAborted("infra"), aborted().retryableCauseSeen(true))
                .isWillRetry());
        assertEquals(
                "infra",
                decide(opts().rerunIfAborted("infra"), aborted().logScan(logWith(Map.of("agentLoss", true))))
                        .getAbortKind(),
                "agent-loss log line is infra");
        assertTrue(
                decide(
                                opts().rerunIfAborted("infraOrTimeout"),
                                aborted().errorClasses(List.of("hudson.remoting.ChannelClosedException")))
                        .isWillRetry(),
                "closed channel in error chain is infra");
        assertFalse(
                RetryDecision.isAgentLossError(
                        "org.jenkinsci.plugins.workflow.steps.MissingContextVariableException[hudson.FilePath]"),
                "a missing agent context alone is not agent loss");
        assertEquals(
                "infra",
                decide(opts().rerunIfAborted("infra"), aborted().agentContextLost(true))
                        .getAbortKind(),
                "an agent context lost inside node is infra");
        assertEquals(
                "unknown",
                decide(
                                opts().rerunIfAborted("infra"),
                                aborted()
                                        .errorClasses(
                                                List.of(
                                                        "org.jenkinsci.plugins.workflow.steps.MissingContextVariableException[hudson.FilePath]")))
                        .getAbortKind(),
                "the same exception outside node is not");
        assertFalse(RetryDecision.isAgentLossError(
                "org.jenkinsci.plugins.workflow.steps.MissingContextVariableException[hudson.EnvVars]"));
        assertTrue(RetryDecision.isAgentLossError("hudson.remoting.ChannelClosedException"));
        assertTrue(RetryDecision.isAgentLossError("x.AgentOfflineException"));
        assertFalse(RetryDecision.isAgentLossError(null));
        assertFalse(RetryDecision.isAgentLossError(""));
        assertFalse(RetryDecision.isAgentLossError("hudson.AbortException"));
        assertFalse(decide(opts().rerunIfAborted("infra"), aborted()).isWillRetry(), "unknown abort under infra");
        assertTrue(decide(opts().rerunIfAborted("always"), aborted()).isWillRetry(), "unknown abort under always");
        assertEquals(
                "user",
                decide(opts(), aborted().interruptionCauses(List.of(USER)).retryableCauseSeen(true))
                        .getAbortKind(),
                "user wins over infra evidence");
        assertEquals(
                "infra",
                decide(opts(), aborted().interruptionCauses(List.of(TIMEOUT)).retryableCauseSeen(true))
                        .getAbortKind(),
                "infra wins over timeout");
        assertEquals(
                "ABORTED/unknown",
                RetryDecision.summary(a0).replace(RetryDecision.LOG_PREFIX, "").split(" ")[0]);
        assertTrue(
                decide(opts().rerunOnAgentLoss(true), aborted().retryableCauseSeen(true))
                        .isWillRetry(),
                "rerunOnAgentLoss unlocks an infra abort under never");
    }

    @Test
    void kubernetesTerminationReasons() {
        RunSnapshot oomSnap = aborted().retryableCauseSeen(true).terminationReasons(List.of("OOMKilled"));
        Decision oom = decide(opts().rerunIfAborted("infra"), oomSnap);
        assertEquals("unknown", oom.getAbortKind(), "an OOMKilled pod is not infra");
        assertEquals("aborted-unknown-not-enabled", oom.getReason(), "and is not retried");
        assertTrue(hasDetail(oom, "OOMKilled"), "and the details say why");
        assertTrue(
                decide(
                                opts().rerunIfAborted("infra"),
                                aborted()
                                        .retryableCauseSeen(true)
                                        .terminationReasons(List.of("OOMKilled", "EvictionByEvictionAPI")))
                        .isWillRetry(),
                "OOMKilled plus an eviction is infra");
        RunSnapshot failingOnly = aborted()
                .retryableCauseSeen(true)
                .terminationReasons(List.of("Error", "OOMKilled"))
                .failedAgentReasons(Map.of("pod-b", List.of("OOMKilled")));
        assertFalse(
                decide(opts().rerunIfAborted("infra"), failingOnly).isWillRetry(),
                "only failing agents count: a healthy deleted pod does not hide an OOMKilled failing pod");
        assertTrue(
                decide(opts().rerunIfAborted("infra"), failingOnly).getDetails().stream()
                        .anyMatch(l -> l.equals("agent loss ignored: kubernetes termination reasons OOMKilled")),
                "the ignored detail names the failing agents' reasons");
        assertTrue(
                decide(
                                opts().rerunIfAborted("infra"),
                                aborted()
                                        .retryableCauseSeen(true)
                                        .terminationReasons(List.of("OOMKilled"))
                                        .failedAgentReasons(Map.of("pod-a", List.of("OOMKilled"), "pod-b", List.of())))
                        .isWillRetry(),
                "a failing agent without reasons is real loss, even next to an OOMKilled one");
        assertEquals(
                "regexp-not-found",
                decide(
                                rx().rerunOnAgentLoss(true),
                                snap().retryableCauseSeen(true)
                                        .terminationReasons(List.of("Completed"))
                                        .logScan(logWith(Map.of("regexp", false))))
                        .getReason(),
                "rerunOnAgentLoss does not skip the regexp for a Completed container");
        RunSnapshot oomFailure = snap().terminationReasons(List.of("OOMKilled"))
                .failedAgentReasons(Map.of("pod-in", List.of("OOMKilled")))
                .logScan(logWith(Map.of("regexp", false, "agentLoss", true)));
        assertEquals(
                "regexp-not-found",
                decide(rx().rerunOnAgentLoss(true), oomFailure).getReason(),
                "a FAILURE whose only failing agent was OOMKilled keeps the regexp gate");
        assertTrue(
                decide(opts().rerunOnAgentLoss(true), oomFailure).isWillRetry(),
                "without a regexp that FAILURE retries, like any failure");
        assertFalse(decide(opts().rerunOnAgentLoss(true), oomFailure).isAgentLoss(), "and shows no agent loss");
    }

    @Test
    void regexpGate() {
        Decision matched = decide(rx(), snap().logScan(logWith(Map.of("regexp", true))));
        assertTrue(matched.isRegexMatched(), "regexp match retries");
        assertTrue(matched.isRegexChecked());
        assertTrue(matched.isWillRetry());
        assertEquals(
                "regexp-not-found",
                decide(rx(), snap().logScan(logWith(Map.of("regexp", false)))).getReason());
        assertEquals(
                "regexp-timeout",
                decide(rx(), snap().logScan(logWith(Map.of("regexp", false)).timedOut(true)))
                        .getReason());
        assertEquals(
                "regexp-invalid",
                decide(rx(), snap().logScan(logWith(Map.of("regexp", false)).patternError(true)))
                        .getReason());
        assertEquals(
                "regexp-log-unreadable",
                decide(rx(), snap().logScan(logWith(Map.of("regexp", false)).ioError(true)))
                        .getReason(),
                "unreadable log stops (freestyle behaviour)");
        assertEquals("regexp-not-scanned", decide(rx(), snap()).getReason(), "regexp not scanned stops");
        Decision rxLimit = decide(rx().maxSchedule(2), snap().retryCount(2));
        assertEquals("max-reached", rxLimit.getReason(), "regexp not scanned at the limit gives max-reached");
        assertTrue(rxLimit.getDetails().stream().anyMatch(l -> l.startsWith("limit: 2 retries done")));
        assertTrue(hasDetail(rxLimit, "log not scanned because the retry limit is reached"));
        assertTrue(decide(opts().checkRegexp(true)).isWillRetry(), "blank regexp with checkRegexp retries");
        assertTrue(hasDetail(decide(opts().checkRegexp(true)), "pattern blank, every failure retries"));
        assertTrue(
                decide(opts().regexpForRerun("x"), snap().logScan(logWith(Map.of("regexp", false))))
                        .isWillRetry(),
                "regexp ignored without checkRegexp");
        Decision ovr = decide(
                rx().rerunOnAgentLoss(true), snap().logScan(logWith(Map.of("regexp", false, "agentLoss", true))));
        assertTrue(ovr.isWillRetry(), "agent loss skips the regexp gate");
        assertTrue(ovr.isAgentLoss(), "and says why");
        assertTrue(ovr.isAgentLossOverride());
        assertFalse(ovr.isRegexChecked());
    }

    @Test
    void abortAtTheLimit() {
        Decision abortLimit = decide(
                opts().rerunIfAborted("infra").maxSchedule(3),
                aborted().retryCount(3).retry(true));
        assertEquals("max-reached", abortLimit.getReason(), "an unclassified abort at the limit gives max-reached");
        assertTrue(hasDetail(abortLimit, "log not scanned because the retry limit"));
        assertEquals(
                "aborted-unknown-not-enabled",
                decide(opts().maxSchedule(3), aborted().retryCount(3).retry(true))
                        .getReason(),
                "with rerunIfAborted never the abort reason stays");
        assertEquals(
                "aborted-unknown-not-enabled",
                decide(
                                opts().rerunIfAborted("infra").maxSchedule(3),
                                aborted().retryCount(1).retry(true).logScan(logWith(Map.of("agentLoss", false))))
                        .getReason(),
                "below the limit an unknown abort is still not retried under infra");
    }

    @Test
    void limitAndCounter() {
        assertTrue(decide(opts().maxSchedule(2), snap().retryCount(0)).isWillRetry(), "limit: 0 of 2 retries");
        assertEquals(2, decide(opts().maxSchedule(2), snap().retryCount(1)).getNextRetryCount(), "limit: 1 of 2");
        assertEquals(
                "max-reached",
                decide(opts().maxSchedule(2), snap().retryCount(2)).getReason(),
                "limit: 2 of 2");
        assertTrue(decide(opts().maxSchedule(0), snap().retryCount(500)).isWillRetry(), "maxSchedule 0 is unlimited");
        assertTrue(decide(opts().maxSchedule(-1), snap().retryCount(500)).isWillRetry(), "negative is unlimited");
        Decision reset = decide(opts().maxSchedule(2), snap().retryCount(5).startedByUser(true));
        assertEquals(
                List.of(true, 0, 1),
                List.of(reset.isWillRetry(), reset.getRetryCount(), reset.getNextRetryCount()),
                "a user-started build resets the count");
        assertTrue(reset.isCounterReset(), "and records the reset");
        assertTrue(hasDetail(reset, "retry count 5 reset to 0"));
        assertEquals(
                "max-reached",
                decide(
                                opts().maxSchedule(2),
                                snap().retryCount(5).startedByUser(true).retry(true))
                        .getReason(),
                "a retry never resets the count");
        assertFalse(decide(opts(), snap().retryCount(0).startedByUser(true)).isCounterReset());
        assertEquals(1800, decide(opts(), snap().retryCount(2)).getDelaySeconds(), "delay follows the count");
        assertEquals(3, decide(opts(), snap().retryCount(2)).getNextRetryCount());
    }

    @Test
    void preGates() {
        assertEquals(
                "already-scheduled",
                decide(opts(), snap().alreadyScheduled(true)).getReason());
        assertEquals(
                "point-of-no-return: images pushed",
                decide(opts(), snap().pointOfNoReturn("images pushed")).getReason());
        assertTrue(decide(opts(), snap().pointOfNoReturn("  ")).isWillRetry(), "a blank point of no return is unset");
        assertEquals("replay-skipped", decide(opts(), snap().replay(true)).getReason(), "replay skipped by default");
        assertTrue(decide(opts().skipReplays(false), snap().replay(true)).isWillRetry());
        Decision inval = decide(opts().rerunIfAborted("sometimes"));
        assertFalse(inval.isWillRetry(), "invalid config never retries");
        assertTrue(inval.getReason().startsWith("invalid-config: rerunIfAborted must be one of"), "and names it");
        assertEquals(
                "already-scheduled",
                decide(opts().rerunIfAborted("sometimes"), snap().alreadyScheduled(true))
                        .getReason(),
                "already scheduled beats invalid config");
        assertEquals(
                "point-of-no-return: x",
                decide(opts(), snap().pointOfNoReturn("x").replay(true)).getReason(),
                "point of no return before replay");
    }

    @Test
    void dryRunAndWarnings() {
        Decision dry = decide(opts().dryRun(true).maxSchedule(3));
        assertEquals(List.of(false, true, "retry"), List.of(dry.isWillRetry(), dry.isWouldRetry(), dry.getReason()));
        assertTrue(dry.isDryRun());
        assertEquals("[Build Retry] FAILURE → would retry 1/3 in 300s (dry run)", RetryDecision.summary(dry));
        assertTrue(decide(opts()).getWarnings().stream().anyMatch(w -> w.contains("unlimited")), "warnings carried");
        assertTrue(decide(opts().verbose(true)).isVerbose());
        assertEquals("progressive", decide(opts()).getDelayType());
    }

    @Test
    void summaryLine() {
        assertEquals("[Build Retry] FAILURE → retry 1/3 in 300s", RetryDecision.summary(decide(opts().maxSchedule(3))));
        assertEquals("[Build Retry] FAILURE → retry 1/unlimited in 300s", RetryDecision.summary(decide(opts())));
        assertEquals(
                "[Build Retry] FAILURE → retry 1/3 in 300s (regexp matched)",
                RetryDecision.summary(decide(rx().maxSchedule(3), snap().logScan(logWith(Map.of("regexp", true))))));
        assertEquals(
                "[Build Retry] SUCCESS → no retry (success)",
                RetryDecision.summary(decide(opts(), snap().result("SUCCESS"))));
        assertEquals(
                "[Build Retry] ABORTED/unknown → no retry (aborted-unknown-not-enabled)",
                RetryDecision.summary(decide(opts(), aborted())));
        assertEquals(
                "[Build Retry] FAILURE → retry 1/unlimited in 300s (agent loss)",
                RetryDecision.summary(
                        decide(opts().rerunOnAgentLoss(true), snap().logScan(logWith(Map.of("agentLoss", true))))));
        assertEquals(
                "[Build Retry] FAILURE → retry 1/2 in 300s (count reset by user start)",
                RetryDecision.summary(
                        decide(opts().maxSchedule(2), snap().retryCount(4).startedByUser(true))));
        Decision secret = decide(rx().verbose(true), snap().logScan(logWith(Map.of("regexp", false))));
        assertFalse(RetryDecision.summary(secret).contains("SECRET"), "summary never contains the regexp");
        assertFalse(secret.getDetails().stream().anyMatch(l -> l.contains("SECRET")), "details never do either");
        assertFalse(secret.getDetails().isEmpty(), "details are recorded");
        assertEquals("[Build Retry] null → no retry (null)", RetryDecision.summary(new Decision()));
    }

    @Test
    void decisionMap() {
        Map<String, Object> m = decide(opts().maxSchedule(2)).toMap();
        assertEquals(true, m.get("willRetry"));
        assertEquals("retry", m.get("reason"));
        assertEquals(1, m.get("nextRetryCount"));
        assertEquals(2, m.get("maxSchedule"));
        assertEquals(300, m.get("delaySeconds"));
        assertEquals("FAILURE", m.get("result"));
        assertNull(m.get("abortKind"));
        assertEquals(
                List.of(
                        "willRetry",
                        "wouldRetry",
                        "reason",
                        "result",
                        "abortKind",
                        "retryCount",
                        "nextRetryCount",
                        "maxSchedule",
                        "delaySeconds",
                        "regexChecked",
                        "regexMatched",
                        "agentLoss",
                        "agentLossOverride",
                        "counterReset",
                        "neverRetryPattern",
                        "dryRun",
                        "warnings"),
                List.copyOf(m.keySet()));
    }

    @Test
    void scanPlan() {
        assertEquals(Map.of(), RetryDecision.logScanPlan(opts(), snap()).getGroups(), "nothing to scan by default");
        assertEquals(
                Map.of("regexp", List.of("SECRET_PATTERN")),
                RetryDecision.logScanPlan(rx(), snap()).getGroups(),
                "regexp group when checkRegexp");
        assertTrue(RetryDecision.logScanPlan(opts().checkRegexp(true), snap()).isEmpty(), "no group when blank");
        assertTrue(RetryDecision.logScanPlan(rx(), snap().result("SUCCESS")).isEmpty(), "no scan on SUCCESS");
        assertTrue(RetryDecision.logScanPlan(rx(), snap().result("UNSTABLE")).isEmpty(), "no scan on UNSTABLE");
        assertFalse(
                RetryDecision.logScanPlan(rx().rerunIfUnstable(true), snap().result("UNSTABLE"))
                        .isEmpty(),
                "scan on UNSTABLE when enabled");
        assertTrue(RetryDecision.logScanPlan(rx(), snap().pointOfNoReturn("x")).isEmpty(), "no scan after PONR");
        assertTrue(
                RetryDecision.logScanPlan(rx(), snap().alreadyScheduled(true)).isEmpty(), "no scan if scheduled");
        assertTrue(RetryDecision.logScanPlan(rx(), snap().replay(true)).isEmpty(), "no scan on a skipped replay");
        ScanPlan ap = RetryDecision.logScanPlan(
                opts().rerunIfAborted("infra").agentLossPatternsExtra(List.of("my line")), aborted());
        List<String> expected = new java.util.ArrayList<>(RetryOptions.DEFAULT_AGENT_LOSS_PATTERNS);
        expected.add("my line");
        assertEquals(expected, ap.getGroups().get("agentLoss"), "agent-loss group on ABORTED with infra");
        assertEquals(
                List.of("Reason: (OOMKilled|Completed|DeadlineExceeded)\\b(?!.*Pod was evicted)"),
                ap.getExcludes().get("agentLoss"),
                "with the OOMKilled exclusion");
        assertTrue(
                RetryDecision.logScanPlan(opts().rerunOnAgentLoss(true), snap())
                        .getGroups()
                        .containsKey("agentLoss"),
                "agent-loss group for rerunOnAgentLoss");
        assertFalse(
                RetryDecision.logScanPlan(opts().rerunOnAgentLoss(true), snap().retryableCauseSeen(true))
                        .getGroups()
                        .containsKey("agentLoss"),
                "no agent-loss scan when the flow graph already proves it");
        assertTrue(
                RetryDecision.logScanPlan(
                                opts().rerunIfAborted("timeout"), aborted().interruptionCauses(List.of(USER)))
                        .isEmpty(),
                "no scan for a user abort");
        assertTrue(RetryDecision.logScanPlan(rx().rerunIfAborted("bad"), snap()).isEmpty(), "no scan if invalid");
        assertTrue(
                RetryDecision.logScanPlan(rx().maxSchedule(1), snap().retryCount(1))
                        .isEmpty(),
                "none at limit");
        assertTrue(
                RetryDecision.logScanPlan(
                                opts().rerunIfAborted("infra"), aborted().terminationReasons(List.of("OOMKilled")))
                        .isEmpty(),
                "no agent-loss scan when every k8s reason is ignored");
        assertEquals(
                "failedStages",
                RetryDecision.logScanPlan(rx().regexpScope("failedStages"), snap())
                        .getRegexpScope(),
                "plan carries the regexp scope");
        assertTrue(RetryDecision.logScanPlan(rx(), snap().result("WEIRD")).isEmpty(), "no scan on an unknown result");
    }

    @Test
    void detailLines() {
        assertEquals(
                List.of(
                        "result FAILURE is retryable",
                        "regexp gate: found in log",
                        "limit: 0 retries done, maxSchedule unlimited",
                        "delay progressive: 300s"),
                decide(rx(), snap().logScan(logWith(Map.of("regexp", true)))).getDetails());
        assertEquals(
                List.of("result FAILURE is retryable", "regexp gate: log was not scanned"),
                decide(rx(), snap()).getDetails());
        assertEquals(
                List.of("result FAILURE is retryable", "regexp gate: regexp-not-found"),
                decide(rx(), snap().logScan(logWith(Map.of("regexp", false)))).getDetails());
        assertEquals(
                List.of(
                        "result ABORTED (abort: infra) is retryable",
                        "agent-loss evidence found: regexp gate skipped",
                        "limit: 0 retries done, maxSchedule 1",
                        "delay fixed: 5s"),
                decide(
                                opts().rerunOnAgentLoss(true).maxSchedule(1).delay(DelaySpec.fixed(5)),
                                aborted().retryableCauseSeen(true))
                        .getDetails());
    }

    @Test
    void agentLossEvidenceAndDefaults() {
        Decision flowOnly = decide(opts(), snap().retryableCauseSeen(true));
        assertTrue(flowOnly.isAgentLoss(), "a Retryable cause alone is agent loss");
        assertFalse(flowOnly.isAgentLossOverride(), "but overrides nothing without rerunOnAgentLoss");
        assertTrue(
                decide(opts(), snap().logScan(logWith(Map.of("agentLoss", true))))
                        .isAgentLoss(),
                "log only");
        Decision none = decide(opts(), snap().result("SUCCESS"));
        assertFalse(none.isAgentLoss(), "no evidence, no agent loss");
        assertFalse(none.isWouldRetry(), "a no-retry decision would not retry either");
        assertFalse(none.isVerbose());
        assertFalse(none.isRegexMatched());
        assertFalse(none.isRegexChecked());
        assertFalse(none.isAgentLossOverride());
        assertFalse(none.isDryRun());
        assertEquals(0, none.getRetryCount());
        assertEquals(2, decide(opts(), snap().retryCount(2)).getRetryCount());
        assertEquals("SUCCESS", none.getResult());
        assertEquals(0, none.getMaxSchedule());
        assertEquals(4, decide(opts().maxSchedule(4)).getMaxSchedule());
        assertEquals(List.of(), Decision.noRetry("FAILURE", "x").getWarnings());
        assertEquals("internal", Decision.noRetry("FAILURE", "internal").getReason());
    }

    @Test
    void aBuildCancelledByItsUpstreamIsSupersededAndNeverRetried() {
        String upstream = "org.jenkinsci.plugins.workflow.support.steps.build.BuildTriggerCancelledCause";
        Decision d = decide(
                opts().rerunIfAborted("always").rerunOnAgentLoss(true),
                aborted().interruptionCauses(List.of(upstream)).retryableCauseSeen(true));
        assertEquals("superseded", d.getAbortKind());
        assertFalse(d.isWillRetry(), "always never retries a build its upstream cancelled");
        assertEquals("aborted-superseded", d.getReason());
        assertEquals(
                "user",
                decide(opts().rerunIfAborted("always"), aborted().interruptionCauses(List.of(upstream, USER)))
                        .getAbortKind(),
                "a user stop still wins");
    }

    @Test
    void aGerritNewPatchSetAbortIsSupersededAndNeverRetried() {
        String newPatchSet = "com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.NewPatchSetInterruption";
        String abandoned = "com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.AbandonedPatchsetInterruption";
        RetryOptions always = opts().rerunIfAborted("always").rerunOnAgentLoss(true);
        Decision d = decide(always, aborted().interruptionCauses(List.of(newPatchSet)));
        assertEquals("superseded", d.getAbortKind());
        assertFalse(d.isWillRetry(), "always never retries a superseded build");
        assertEquals("aborted-superseded", d.getReason());
        assertEquals(
                "superseded",
                decide(always, aborted().interruptionCauses(List.of(abandoned))).getAbortKind(),
                "an abandoned patch set too");
        Decision lost = decide(
                opts().rerunIfAborted("infra").rerunOnAgentLoss(true),
                aborted()
                        .interruptionCauses(List.of(newPatchSet))
                        .errorClasses(
                                List.of(
                                        "org.jenkinsci.plugins.workflow.steps.MissingContextVariableException[hudson.FilePath]"))
                        .retryableCauseSeen(true));
        assertEquals("superseded", lost.getAbortKind(), "superseded wins over agent-loss evidence");
        assertFalse(lost.isWillRetry());
        assertEquals(
                "user",
                decide(always, aborted().interruptionCauses(List.of(newPatchSet, USER)))
                        .getAbortKind(),
                "a user stop still wins");
        assertEquals(
                "ABORTED/superseded → no retry (aborted-superseded)",
                RetryDecision.summary(d).replace(RetryDecision.LOG_PREFIX, ""));
        assertTrue(
                RetryDecision.logScanPlan(
                                always.checkRegexp(true).regexpForRerun("x"),
                                aborted().interruptionCauses(List.of(newPatchSet)))
                        .isEmpty(),
                "nothing to scan for a superseded build");
    }

    @Test
    void scanPlanAbortBranches() {
        assertTrue(RetryDecision.logScanPlan(rx(), aborted()).isEmpty(), "ABORTED under never: nothing to scan");
        assertTrue(
                RetryDecision.logScanPlan(
                                rx().rerunIfAborted("infra"), aborted().interruptionCauses(List.of(USER)))
                        .isEmpty(),
                "a user abort under infra: nothing to scan");
        assertEquals(
                List.of("regexp"),
                List.copyOf(RetryDecision.logScanPlan(
                                rx().rerunIfAborted("timeout"), aborted().interruptionCauses(List.of(TIMEOUT)))
                        .getGroups()
                        .keySet()),
                "a timeout abort under timeout scans the regexp only");
        assertEquals(
                List.of("agentLoss", "regexp"),
                List.copyOf(RetryDecision.logScanPlan(rx().rerunOnAgentLoss(true), aborted())
                        .getGroups()
                        .keySet()),
                "rerunOnAgentLoss looks for agent loss in an unclassified abort");
    }
}
