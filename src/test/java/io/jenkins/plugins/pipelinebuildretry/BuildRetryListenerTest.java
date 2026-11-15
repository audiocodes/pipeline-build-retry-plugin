package io.jenkins.plugins.pipelinebuildretry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.AbandonedPatchsetInterruption;
import com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.NewPatchSetInterruption;
import hudson.ExtensionList;
import hudson.model.BooleanParameterDefinition;
import hudson.model.BooleanParameterValue;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.TaskListener;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jenkins.model.CauseOfInterruption;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty;
import org.jenkinsci.plugins.workflow.support.steps.build.BuildTriggerCancelledCause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BuildRetryListenerTest {

    private static final String ENV_LINE =
            "echo \"retry-env count=${env.BUILD_RETRY_COUNT} max=${env.BUILD_RETRY_MAXCOUNT}"
                    + " parent=${env.BUILD_RETRY_BUILD_NUMBER}\"\n";

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    private WorkflowJob job(String options, String body) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition("properties([buildRetry(" + options + ")])\n" + body, true));
        return p;
    }

    private WorkflowRun build(WorkflowJob p) throws Exception {
        WorkflowRun r = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(r);
        j.waitUntilNoActivity();
        return r;
    }

    private static DecidedAction decided(WorkflowRun r) {
        DecidedAction a = r.getAction(DecidedAction.class);
        assertNotNull(a, "decided at the end of #" + r.getNumber());
        return a;
    }

    @Test
    void failureRetriesToTheLimitThenStops() throws Exception {
        WorkflowJob p = job("maxSchedule: 2, delay: fixedDelay(0)", ENV_LINE + "error 'boom'");
        WorkflowRun first = build(p);
        assertEquals(3, p.getBuilds().size(), "original and two retries");
        j.assertLogContains("[Build Retry] FAILURE → retry 1/2 in 0s", first);
        j.assertLogContains("[Build Retry] retry 1/2 queued (item ", first);
        assertEquals("queued", decided(first).getOutcome(), "first outcome");
        assertNotNull(first.getAction(RetryScheduledAction.class), "marker on the armed build");
        j.assertLogContains("retry-env count=null max=null parent=null", first);

        WorkflowRun second = p.getBuildByNumber(2);
        BuildRetryAction a2 = second.getAction(BuildRetryAction.class);
        assertEquals(List.of(1, 2, 1), List.of(a2.getRetryCount(), a2.getMaxRetryCount(), a2.getParentBuildNumber()));
        j.assertLogContains("retry-env count=1 max=2 parent=1", second);
        BuildRetryCause cause = second.getCause(BuildRetryCause.class);
        assertEquals("Build retry 1/2 after build #1 failed", cause.getShortDescription(), "cause text");
        assertEquals(cause.getShortDescription(), a2.getTooltip(), "badge tooltip");
        assertTrue(a2.isBadge(), "badge on by default");

        WorkflowRun third = p.getBuildByNumber(3);
        j.assertLogContains("retry-env count=2 max=2 parent=2", third);
        j.assertLogContains("[Build Retry] FAILURE → no retry (max-reached)", third);
        assertEquals("no-retry", decided(third).getOutcome(), "stopped");
        assertNull(third.getAction(RetryScheduledAction.class), "nothing armed");
        assertEquals(
                1,
                third.getCauses().stream()
                        .filter(c -> c instanceof BuildRetryCause)
                        .count(),
                "one retry cause");
    }

    @Test
    void regexpGateRetriesOnlyOnAMatch() throws Exception {
        String opts = "maxSchedule: 1, delay: fixedDelay(0), checkRegexp: true, regexpForRerun: 'flaky-net'";
        WorkflowJob match = job(opts, "echo 'flaky-net down'\nerror 'boom'");
        build(match);
        assertEquals(2, match.getBuilds().size(), "retried on a match");
        WorkflowJob miss = job(opts, "echo 'compile error'\nerror 'boom'");
        WorkflowRun r = build(miss);
        assertEquals(1, miss.getBuilds().size(), "no match, no retry");
        j.assertLogContains("no retry (regexp-not-found)", r);
        j.assertLogNotContains("flaky-net", r);
    }

    @Test
    void aNeverRetryPatternStopsEveryRetry() throws Exception {
        String opts = "maxSchedule: 1, delay: fixedDelay(0), rerunOnAgentLoss: true,"
                + " neverRetryPatterns: ['nothing-here', 'COMPILATION ERROR']";
        WorkflowJob hit = job(opts, "echo 'step one'\necho 'COMPILATION ERROR in Foo.java'\nerror 'boom'");
        WorkflowRun r = build(hit);
        assertEquals(1, hit.getBuilds().size(), "not retried");
        List<String> lines = List.of(r.getLog().split("\\R", -1));
        int line = 1
                + lines.indexOf(lines.stream()
                        .filter(l -> l.endsWith("COMPILATION ERROR in Foo.java"))
                        .findFirst()
                        .orElseThrow());
        j.assertLogContains(
                "[Build Retry] FAILURE → no retry (never-retry-matched: pattern #2 at console line " + line + ")", r);
        assertEquals("no-retry", decided(r).getOutcome(), "outcome");
        assertEquals(0, j.jenkins.getQueue().getItems().length, "nothing queued");
        j.assertLogNotContains("nothing-here", r);

        WorkflowJob miss = job(opts, "echo 'flaky network'\nerror 'boom'");
        build(miss);
        assertEquals(2, miss.getBuilds().size(), "no match, retried");
    }

    @Test
    void aScanThatStopsAtTheMatchStillNamesTheConsoleLine() throws Exception {
        WorkflowJob p = job(
                "maxSchedule: 1, delay: fixedDelay(0), neverRetryPatterns: ['COMPILATION ERROR']",
                "echo 'COMPILATION ERROR in Foo.java'\necho 'after'\nerror 'boom'");
        WorkflowRun r = build(p);
        List<String> lines = List.of(r.getLog().split("\\R", -1));
        int line = 1
                + lines.indexOf(lines.stream()
                        .filter(l -> l.endsWith("COMPILATION ERROR in Foo.java"))
                        .findFirst()
                        .orElseThrow());
        j.assertLogContains("no retry (never-retry-matched: pattern #1 at console line " + line + ")", r);
    }

    @Test
    void aMatchInTheStageErrorTextIsPlacedRight() throws Exception {
        WorkflowJob p = job(
                "maxSchedule: 1, delay: fixedDelay(0), neverRetryPatterns: ['bad-param-[0-9]+']",
                "stage('a') { error 'bad-param-7' }");
        WorkflowRun r = build(p);
        assertEquals(1, p.getBuilds().size(), "not retried");
        List<String> lines = List.of(r.getLog().split("\\R", -1));
        int decision = lines.indexOf(lines.stream()
                .filter(l -> l.contains("[Build Retry] FAILURE"))
                .findFirst()
                .orElseThrow());
        int match = -1;
        for (int i = 0; i < decision; i++) {
            if (lines.get(i).contains("bad-param-7")) {
                match = i;
                break;
            }
        }
        String where = match >= 0 ? "at console line " + (match + 1) : "in a stage error line";
        j.assertLogContains("no retry (never-retry-matched: pattern #1 " + where + ")", r);
    }

    @Test
    void aNeverRetryPatternFollowsTheFailedStagesScope() throws Exception {
        String opts = "maxSchedule: 1, delay: fixedDelay(0), regexpScope: 'failedStages',"
                + " neverRetryPatterns: ['COMPILATION ERROR']";
        WorkflowJob passing = job(opts, "stage('a') { echo 'COMPILATION ERROR seen' }\nstage('b') { error 'boom' }");
        build(passing);
        assertEquals(2, passing.getBuilds().size(), "text only in a passing stage: retried");

        WorkflowJob failing =
                job(opts, "stage('a') { echo 'ok' }\nstage('b') { echo 'COMPILATION ERROR seen'; error 'boom' }");
        WorkflowRun r = build(failing);
        assertEquals(1, failing.getBuilds().size(), "text in the failed stage: not retried");
        j.assertLogContains("no retry (never-retry-matched: pattern #1 in the failed stages' logs)", r);
    }

    @Test
    void unstableRetriesOnlyWhenEnabled() throws Exception {
        WorkflowJob off = job("maxSchedule: 1, delay: fixedDelay(0)", "unstable 'flaky'");
        j.assertLogContains("no retry (unstable-not-enabled)", build(off));
        assertEquals(1, off.getBuilds().size(), "off");
        WorkflowJob on = job("maxSchedule: 1, delay: fixedDelay(0), rerunIfUnstable: true", "unstable 'flaky'");
        build(on);
        assertEquals(2, on.getBuilds().size(), "on");
    }

    @Test
    void userAbortIsNeverRetried() throws Exception {
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(0), rerunIfAborted: 'always'", "sleep 60");
        WorkflowRun r = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Sleeping for", r);
        r.getExecutor().interrupt(Result.ABORTED, new CauseOfInterruption.UserInterruption("alice"));
        j.waitForCompletion(r);
        j.waitUntilNoActivity();
        assertEquals(2, p.getBuilds().size(), "always retries even a user abort");

        WorkflowJob q = job("maxSchedule: 1, delay: fixedDelay(0), rerunIfAborted: 'infraOrTimeout'", "sleep 60");
        WorkflowRun s = q.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Sleeping for", s);
        s.getExecutor().interrupt(Result.ABORTED, new CauseOfInterruption.UserInterruption("alice"));
        j.waitForCompletion(s);
        j.waitUntilNoActivity();
        assertEquals(1, q.getBuilds().size(), "a user abort is not infra or timeout");
        j.assertLogContains("ABORTED/user → no retry (aborted-user-not-enabled)", s);
    }

    @Test
    void aGerritNewPatchSetAbortIsNeverRetried() throws Exception {
        WorkflowJob p = job(
                "maxSchedule: 1, delay: fixedDelay(0), rerunIfAborted: 'always', rerunOnAgentLoss: true",
                "node { sleep 60 }");
        WorkflowRun r = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Sleeping for", r);
        r.getExecutor().interrupt(Result.ABORTED, new NewPatchSetInterruption());
        j.waitForCompletion(r);
        j.waitUntilNoActivity();
        assertEquals(1, p.getBuilds().size(), "always never retries a superseded build");
        j.assertLogContains("ABORTED/superseded → no retry (aborted-superseded)", r);
    }

    @Test
    void aBuildCancelledByItsUpstreamIsNeverRetried() throws Exception {
        WorkflowJob p = job(
                "maxSchedule: 1, delay: fixedDelay(0), rerunIfAborted: 'always', rerunOnAgentLoss: true",
                "node { sleep 60 }");
        WorkflowRun r = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Sleeping for", r);
        r.getExecutor().interrupt(Result.ABORTED, new BuildTriggerCancelledCause());
        j.waitForCompletion(r);
        j.waitUntilNoActivity();
        assertEquals(1, p.getBuilds().size(), "always never retries a build its upstream cancelled");
        j.assertLogContains("ABORTED/superseded → no retry (aborted-superseded)", r);
    }

    @Test
    void aGerritAbortWhileWaitingForAnAgentIsNotAgentLoss() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(
                "properties([buildRetry(maxSchedule: 1, delay: fixedDelay(0), rerunIfAborted: 'infra',"
                        + " rerunOnAgentLoss: true)])\n"
                        + "pipeline {\n"
                        + "  agent { label 'no-such-agent' }\n"
                        + "  stages { stage('Build') { steps { echo 'never' } } }\n"
                        + "  post { always { archiveArtifacts artifacts: 'x', allowEmptyArchive: true } }\n"
                        + "}\n",
                true));
        WorkflowRun r = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Still waiting to schedule task", r);
        r.getExecutor().interrupt(Result.ABORTED, new AbandonedPatchsetInterruption());
        j.waitForCompletion(r);
        try {
            j.waitForMessage("Finished: ABORTED", r);
            // A wrongly queued retry waits forever for the missing agent, so check the queue, not idleness.
            assertEquals(0, Queue.getInstance().getItems(p).size(), "not retried as agent loss");
            j.assertLogContains("ABORTED/superseded → no retry (aborted-superseded)", r);
        } finally {
            Queue.getInstance().clear();
        }
    }

    private static final String NO_MATCH_ON_LOSS =
            "properties([buildRetry(maxSchedule: 1, delay: fixedDelay(0), checkRegexp: true,"
                    + " regexpForRerun: 'never-matches-anything', rerunOnAgentLoss: true)])\n";

    private static final String POST_ARCHIVE =
            "  post { always { archiveArtifacts artifacts: 'x', allowEmptyArchive: true } }\n";

    @Test
    void aStepOutsideAnyNodeIsNotAgentLoss() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(
                NO_MATCH_ON_LOSS
                        + "pipeline {\n"
                        + "  agent none\n"
                        + "  stages { stage('Build') { steps { echo 'hi' } } }\n"
                        + POST_ARCHIVE
                        + "}\n",
                true));
        WorkflowRun r = build(p);
        j.assertBuildStatus(Result.FAILURE, r);
        j.assertLogContains("[Build Retry] FAILURE → no retry (regexp-not-found)", r);
        j.assertLogNotContains("(agent loss)", r);
        assertEquals(1, p.getBuilds().size(), "not retried");
    }

    @Test
    void aTopLevelPostOutsideNodeIsNotAgentLossEvenAfterAgentStages() throws Exception {
        j.waitOnline(j.createSlave("box", "box", null));
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(
                NO_MATCH_ON_LOSS
                        + "pipeline {\n"
                        + "  agent none\n"
                        + "  stages { stage('Build') { agent { label 'box' }\n"
                        + "    steps { echo 'on box' } } }\n"
                        + POST_ARCHIVE
                        + "}\n",
                true));
        WorkflowRun r = build(p);
        j.assertBuildStatus(Result.FAILURE, r);
        j.assertLogContains("on box", r);
        j.assertLogContains("[Build Retry] FAILURE → no retry (regexp-not-found)", r);
        assertEquals(1, p.getBuilds().size(), "not retried");
    }

    @Test
    void aTimeoutWhileWaitingForAnAgentIsNotAgentLoss() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(
                "properties([buildRetry(maxSchedule: 1, delay: fixedDelay(0), rerunIfAborted: 'infra',"
                        + " rerunOnAgentLoss: true)])\n"
                        // Declarative's own timeout option starts only once the agent is allocated.
                        + "try {\n"
                        + "  timeout(time: 3, unit: 'SECONDS') { node('no-such-agent') { echo 'never' } }\n"
                        + "} finally {\n"
                        + "  try { archiveArtifacts artifacts: 'x', allowEmptyArchive: true }"
                        + " catch (err) { echo 'archive failed outside node' }\n"
                        + "}\n",
                true));
        WorkflowRun r = p.scheduleBuild2(0).waitForStart();
        j.waitForCompletion(r);
        try {
            j.waitForMessage("Finished: ABORTED", r);
            j.assertLogContains("archive failed outside node", r);
            assertEquals(0, Queue.getInstance().getItems(p).size(), "not retried as agent loss");
            j.assertLogContains("ABORTED/timeout → no retry (aborted-timeout-not-enabled)", r);
        } finally {
            Queue.getInstance().clear();
        }
    }

    @Test
    void aMissingAgentContextInsideNodeIsStillAgentLoss() throws Exception {
        j.waitOnline(j.createSlave("box", "box", null));
        ScriptApproval.get().preapproveAll();
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(
                NO_MATCH_ON_LOSS
                        + "node('box') {\n"
                        + "  throw new org.jenkinsci.plugins.workflow.steps.MissingContextVariableException("
                        + "hudson.FilePath)\n"
                        + "}\n",
                false));
        WorkflowRun r = build(p);
        j.assertLogContains("[Build Retry] FAILURE → retry 1/1 in 0s (agent loss)", r);
        assertEquals(2, p.getBuilds().size(), "retried");
    }

    @Test
    void timeoutRetriesUnderTheTimeoutPolicy() throws Exception {
        WorkflowJob p = job(
                "maxSchedule: 1, delay: fixedDelay(0), rerunIfAborted: 'timeout'",
                "timeout(time: 1, unit: 'SECONDS') { sleep 30 }");
        WorkflowRun r = build(p);
        j.assertLogContains("ABORTED/timeout → retry 1/1", r);
        assertEquals(2, p.getBuilds().size(), "retried");
        WorkflowRun retry = p.getBuildByNumber(2);
        assertEquals(
                "Build retry 1/1 after build #1 timed out",
                retry.getCause(BuildRetryCause.class).getShortDescription(),
                "cause text");
        assertEquals(
                "Build retry 1/1 after build #1 timed out",
                retry.getAction(BuildRetryAction.class).getTooltip(),
                "badge tooltip");
    }

    @Test
    void replayIsSkippedAndItsCauseNeverCopied() throws Exception {
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(0)", "echo 'ok'");
        WorkflowRun first = build(p);
        WorkflowRun replay = (WorkflowRun) first.getAction(ReplayAction.class)
                .run("properties([buildRetry(maxSchedule: 1, delay: fixedDelay(0))])\nerror 'boom'", Map.of())
                .get();
        j.waitUntilNoActivity();
        j.assertLogContains("no retry (replay-skipped)", replay);
        assertEquals(2, p.getBuilds().size(), "no retry of a replay");

        WorkflowRun again = (WorkflowRun) first.getAction(ReplayAction.class)
                .run(
                        "properties([buildRetry(maxSchedule: 1, delay: fixedDelay(0), skipReplays: false)])\n"
                                + "error 'boom'",
                        Map.of())
                .get();
        j.waitUntilNoActivity();
        WorkflowRun retry = p.getLastBuild();
        assertEquals(again.getNumber() + 1, retry.getNumber(), "replay retried with skipReplays: false");
        assertFalse(
                retry.getCauses().stream()
                        .anyMatch(c -> c.getClass().getSimpleName().equals("ReplayCause")),
                "the retry is not a replay");
    }

    @Test
    void dryRunNeverQueues() throws Exception {
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(0), dryRun: true", "error 'boom'");
        WorkflowRun r = build(p);
        j.assertLogContains("FAILURE → would retry 1/1 in 0s (dry run)", r);
        assertEquals(1, p.getBuilds().size(), "nothing queued");
        assertEquals("dry-run", decided(r).getOutcome(), "outcome");
        assertNull(r.getAction(RetryScheduledAction.class), "nothing armed");
    }

    @Test
    void parametersAndCausesCarryOver() throws Exception {
        WorkflowJob p = job(
                "maxSchedule: 1, delay: fixedDelay(0), parameterOverrides: [MODE: 'safe', EXTRA: 'x'],"
                        + " dropCauses: ['RemoteCause']",
                "echo \"params ${params.TARGET} ${params.MODE} ${params.EXTRA}\"\nerror 'boom'");
        p.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("TARGET", "default"), new StringParameterDefinition("MODE", "fast")));
        WorkflowRun first = j.waitForCompletion(p.scheduleBuild2(
                        0,
                        new ParametersAction(
                                new hudson.model.StringParameterValue("TARGET", "lab"),
                                new hudson.model.StringParameterValue("MODE", "fast")),
                        new CauseAction(new Cause.UserIdCause("alice"), new Cause.RemoteCause("host", "note")))
                .waitForStart());
        j.waitUntilNoActivity();
        WorkflowRun retry = p.getBuildByNumber(2);
        assertNotNull(retry, "retried");
        j.assertLogContains("params lab safe x", retry);
        assertEquals(
                List.of("TARGET", "MODE", "EXTRA"),
                retry.getAction(ParametersAction.class).getAllParameters().stream()
                        .map(ParameterValue::getName)
                        .toList(),
                "each parameter once, overrides last");
        assertTrue(
                retry.getAction(ParametersAction.class).getParameters().stream()
                        .anyMatch(v -> v.getName().equals("EXTRA")),
                "an undefined override is a safe parameter");
        List<String> causes = retry.getCauses().stream()
                .map(c -> c.getClass().getSimpleName())
                .collect(Collectors.toList());
        assertEquals(List.of("UserIdCause", "BuildRetryCause"), causes, "user kept, remote dropped, retry added");
        assertNotNull(first.getAction(DecidedAction.class), "decided");
    }

    @Test
    void twoChainsAreCountedApartAndNeverFolded() throws Exception {
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(5)", "sleep 1\nerror 'boom'");
        p.setConcurrentBuild(true);
        WorkflowRun a =
                p.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause("a"))).waitForStart();
        WorkflowRun b =
                p.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause("b"))).waitForStart();
        j.waitForCompletion(a);
        j.waitForCompletion(b);
        List<Queue.Item> queued = List.of(j.jenkins.getQueue().getItems(p).toArray(new Queue.Item[0]));
        assertEquals(2, queued.size(), "two retries, not folded into one");
        j.waitUntilNoActivity();
        assertEquals(4, p.getBuilds().size(), "each chain retried once");
        for (WorkflowRun r : p.getBuilds()) {
            BuildRetryAction ra = r.getAction(BuildRetryAction.class);
            if (ra != null) {
                assertEquals(1, ra.getRetryCount(), "each chain counts from its own build");
            }
        }
    }

    @Test
    void delayIsTheQueueQuietPeriod() throws Exception {
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(600)", "error 'boom'");
        WorkflowRun r = j.waitForCompletion(p.scheduleBuild2(0).waitForStart());
        Queue.Item item = j.jenkins.getQueue().getItem(decided(r).getQueueId());
        Queue.WaitingItem waiting = assertInstanceOf(Queue.WaitingItem.class, item, "waiting in the queue");
        long wait = waiting.timestamp.getTimeInMillis() - System.currentTimeMillis();
        assertTrue(wait > 590_000L && wait <= 600_000L, "about 600 s: " + wait);
        j.jenkins.getQueue().cancel(item);
    }

    @Test
    void userStartResetsTheCountAfterTheLimit() throws Exception {
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(0)", "error 'boom'");
        build(p);
        assertEquals(2, p.getBuilds().size(), "one retry, then max-reached");
        WorkflowRun rebuild = j.waitForCompletion(p.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause("alice")))
                .waitForStart());
        j.waitUntilNoActivity();
        j.assertLogContains("FAILURE → retry 1/1 in 0s", rebuild);
        assertEquals(4, p.getBuilds().size(), "the user's build starts a new chain");
    }

    @Test
    void abortPreviousCancelsAQueuedRetry() throws Exception {
        WorkflowJob p = job(
                "maxSchedule: 1, delay: fixedDelay(600)", "echo 'go'\nif (currentBuild.number == 1) { error 'boom' }");
        DisableConcurrentBuildsJobProperty d = new DisableConcurrentBuildsJobProperty();
        d.setAbortPrevious(true);
        p.addProperty(d);
        WorkflowRun first = j.waitForCompletion(p.scheduleBuild2(0).waitForStart());
        j.assertLogContains("; cancelled if another build of this job starts first (abortPrevious)", first);
        assertEquals(1, j.jenkins.getQueue().getItems(p).size(), "retry waiting");
        WorkflowRun second = j.buildAndAssertSuccess(p);
        j.assertLogContains("pending retry 1 of build #1 cancelled: this build supersedes it (abortPrevious)", second);
        assertEquals(0, j.jenkins.getQueue().getItems(p).size(), "retry cancelled");
    }

    @Test
    void withoutAbortPreviousANewBuildLeavesTheRetryQueued() throws Exception {
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(600)", "if (currentBuild.number == 1) { error 'boom' }");
        j.waitForCompletion(p.scheduleBuild2(0).waitForStart());
        assertEquals(1, j.jenkins.getQueue().getItems(p).size(), "retry waiting");
        j.buildAndAssertSuccess(p);
        assertEquals(1, j.jenkins.getQueue().getItems(p).size(), "still waiting");
        j.jenkins.getQueue().clear();
    }

    @Test
    void abortPreviousSupersedesARetryAtTheEnd() throws Exception {
        WorkflowJob p = job(
                "maxSchedule: 1, delay: fixedDelay(0), rerunIfAborted: 'always'",
                "if (currentBuild.number == 1) { sleep 60 } else { echo 'newer' }");
        DisableConcurrentBuildsJobProperty d = new DisableConcurrentBuildsJobProperty();
        d.setAbortPrevious(true);
        p.addProperty(d);
        WorkflowRun first = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Sleeping for", first);
        p.scheduleBuild2(0);
        j.waitForCompletion(first);
        j.waitUntilNoActivity();
        j.assertLogContains("skipped: a newer build of this job started (abortPrevious)", first);
        assertEquals("superseded", decided(first).getOutcome(), "outcome");
        assertEquals(2, p.getBuilds().size(), "no retry of the superseded build");
    }

    @Test
    void aCopyQueuedByAnotherPluginStopsTheRetry() throws Exception {
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(0)", "echo 'started'\nsleep 2\nerror 'boom'");
        WorkflowRun r =
                p.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause("a"))).waitForStart();
        j.waitForMessage("started", r);
        Queue.Item copy = j.jenkins.getQueue().schedule2(p, 600, r.getActions()).getItem();
        j.waitForCompletion(r);
        j.assertLogContains(
                "retry 1/1 skipped: this build was already re-queued by another plugin (queue item " + copy.getId()
                        + ")",
                r);
        assertEquals("requeued-elsewhere", decided(r).getOutcome(), "outcome");
        assertEquals(1, j.jenkins.getQueue().getItems(p).size(), "only the copy waits");
        assertNull(r.getAction(RetryScheduledAction.class), "nothing armed");
        j.jenkins.getQueue().clear();
    }

    @Test
    void aDryRunReportsACopyQueuedByAnotherPlugin() throws Exception {
        WorkflowJob p =
                job("maxSchedule: 1, delay: fixedDelay(0), dryRun: true", "echo 'started'\nsleep 2\nerror 'boom'");
        WorkflowRun r =
                p.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause("a"))).waitForStart();
        j.waitForMessage("started", r);
        Queue.Item copy = j.jenkins.getQueue().schedule2(p, 600, r.getActions()).getItem();
        j.waitForCompletion(r);
        j.assertLogContains(
                "retry 1/1 would be skipped: this build was already re-queued by another plugin (queue item "
                        + copy.getId() + ")",
                r);
        assertEquals("requeued-elsewhere", decided(r).getOutcome(), "outcome");
        assertEquals(1, j.jenkins.getQueue().getItems(p).size(), "only the copy waits");
        assertNull(r.getAction(RetryScheduledAction.class), "nothing armed");
        j.jenkins.getQueue().clear();
    }

    @Test
    void aCopyThatAlreadyStartedStopsTheRetry() throws Exception {
        WorkflowJob p = job(
                "maxSchedule: 1, delay: fixedDelay(0)",
                "if (currentBuild.number == 1) { echo 'started'\nsleep 5\nerror 'boom' } else { echo 'copy' }");
        WorkflowRun r =
                p.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause("a"))).waitForStart();
        j.waitForMessage("started", r);
        j.jenkins.getQueue().schedule2(p, 0, r.getActions());
        j.waitForCompletion(r);
        j.waitUntilNoActivity();
        j.assertLogContains("retry 1/1 skipped: this build was already re-queued by another plugin (build #2)", r);
        assertEquals("requeued-elsewhere", decided(r).getOutcome(), "outcome");
        assertEquals(2, p.getBuilds().size(), "the copy only, no retry");
    }

    @Test
    void disabledJobIsNotQueued() throws Exception {
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(0)", "echo 'started'\nsleep 2\nerror 'boom'");
        WorkflowRun r = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("started", r);
        p.makeDisabled(true);
        j.waitForCompletion(r);
        j.assertLogContains("not queued: the job is not buildable", r);
        assertEquals("not-buildable", decided(r).getOutcome(), "outcome");
        assertNull(r.getAction(RetryScheduledAction.class), "nothing armed");
    }

    @Test
    void decidesOnceEvenIfCompletedTwice() throws Exception {
        WorkflowJob p = job("maxSchedule: 3, delay: fixedDelay(600)", "error 'boom'");
        WorkflowRun r = j.waitForCompletion(p.scheduleBuild2(0).waitForStart());
        assertEquals(1, j.jenkins.getQueue().getItems(p).size(), "one retry");
        ExtensionList.lookupSingleton(BuildRetryListener.class).onCompleted(r, TaskListener.NULL);
        assertEquals(1, j.jenkins.getQueue().getItems(p).size(), "still one retry");
        assertEquals(1, r.getActions(DecidedAction.class).size(), "decided once");
        j.jenkins.getQueue().clear();
    }

    @Test
    void anotherArmedRetryStopsThisOne() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition("error 'boom'", true));
        WorkflowRun r = j.buildAndAssertStatus(Result.FAILURE, p);
        assertNull(r.getAction(DecidedAction.class), "no property, no decision");
        BuildRetryJobProperty prop = new BuildRetryJobProperty();
        prop.setMaxSchedule(3);
        p.addProperty(prop);
        r.addAction(new RetryScheduledAction("another"));
        ExtensionList.lookupSingleton(BuildRetryListener.class).onCompleted(r, TaskListener.NULL);
        assertEquals("already-scheduled", decided(r).getDecision().getReason(), "marker honoured");
        assertEquals(0, j.jenkins.getQueue().getItems(p).size(), "nothing queued");
    }

    @Test
    void jobsWithoutThePropertyAreUntouched() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition("error 'boom'", true));
        WorkflowRun r = j.buildAndAssertStatus(Result.FAILURE, p);
        j.waitUntilNoActivity();
        j.assertLogNotContains("[Build Retry]", r);
        assertNull(r.getAction(DecidedAction.class), "no decision");
        assertEquals(1, p.getBuilds().size(), "no retry");
    }

    @Test
    void globalSwitchOffSkipsTheDecisionAndTakesEffectAtOnce() throws Exception {
        WorkflowJob p = job(
                "maxSchedule: 1, delay: fixedDelay(0), checkRegexp: true, regexpForRerun: 'flaky-net', verbose: true",
                "echo 'flaky-net down'\nerror 'boom'");
        BuildRetryGlobalConfiguration.get().setEnabled(false);
        WorkflowRun off = build(p);
        j.assertLogContains(
                "[Build Retry] retries are turned off on this controller; no decision (an administrator turns them"
                        + " back on with " + BuildRetryGlobalConfiguration.class.getName()
                        + ".get().setEnabled(true) in the script console, or on Manage Jenkins → System)",
                off);
        j.assertLogNotContains("FAILURE →", off);
        j.assertLogNotContains("regexp gate", off);
        assertNull(off.getAction(DecidedAction.class), "no decision");
        assertEquals(1, p.getBuilds().size(), "no retry");

        BuildRetryGlobalConfiguration.get().setEnabled(true);
        WorkflowRun on = build(p);
        j.assertLogContains("[Build Retry] FAILURE → retry 1/1 in 0s", on);
        assertEquals(3, p.getBuilds().size(), "retried once the switch is back on");
    }

    @Test
    void switchingOffLeavesWaitingRetriesQueued() throws Exception {
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(600)", "error 'boom'");
        j.waitForCompletion(p.scheduleBuild2(0).waitForStart());
        assertEquals(1, j.jenkins.getQueue().getItems(p).size(), "retry waiting");
        BuildRetryGlobalConfiguration.get().setEnabled(false);
        j.configRoundtrip();
        assertFalse(BuildRetryGlobalConfiguration.get().isEnabled(), "off through the form");
        assertEquals(1, j.jenkins.getQueue().getItems(p).size(), "still waiting");
        j.jenkins.getQueue().clear();
    }

    @Test
    void abortPreviousStillCancelsAWaitingRetryWhenTheSwitchIsOff() throws Exception {
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(600)", "if (currentBuild.number == 1) { error 'boom' }");
        DisableConcurrentBuildsJobProperty d = new DisableConcurrentBuildsJobProperty();
        d.setAbortPrevious(true);
        p.addProperty(d);
        j.waitForCompletion(p.scheduleBuild2(0).waitForStart());
        assertEquals(1, j.jenkins.getQueue().getItems(p).size(), "retry waiting");
        BuildRetryGlobalConfiguration.get().setEnabled(false);
        WorkflowRun second = j.buildAndAssertSuccess(p);
        j.assertLogContains("pending retry 1 of build #1 cancelled: this build supersedes it (abortPrevious)", second);
        assertEquals(0, j.jenkins.getQueue().getItems(p).size(), "retry cancelled");
    }

    @Test
    void badgeShowsInTheBuildHistory() throws Exception {
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(0)", "error 'boom'");
        build(p);
        String page = j.createWebClient().getPage(p).asXml();
        assertTrue(page.contains("Build retry 1/1 after build #1 failed"), "badge tooltip on the job page");
    }

    @Test
    void verboseLogsEveryGateAndWhatWasCopied() throws Exception {
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(0), verbose: true", "error 'boom'");
        WorkflowRun r = build(p);
        j.assertLogContains("[Build Retry]   limit: 0 retries done, maxSchedule 1", r);
        j.assertLogContains("[Build Retry]   causes kept [", r);
    }

    @Test
    void overridesKeepTheParameterType() throws Exception {
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(0), parameterOverrides: [FAST: true]", "error 'boom'");
        p.addProperty(new ParametersDefinitionProperty(new BooleanParameterDefinition("FAST", false, "")));
        j.waitForCompletion(p.scheduleBuild2(0).waitForStart());
        j.waitUntilNoActivity();
        ParameterValue v =
                p.getBuildByNumber(2).getAction(ParametersAction.class).getParameter("FAST");
        assertInstanceOf(BooleanParameterValue.class, v, "a defined parameter keeps its type");
        assertEquals(true, v.getValue(), "overridden");
    }

    @Test
    void badgeCanBeTurnedOff() throws Exception {
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(0), badge: false", "error 'boom'");
        build(p);
        assertFalse(p.getBuildByNumber(2).getAction(BuildRetryAction.class).isBadge(), "no badge");
    }
}
