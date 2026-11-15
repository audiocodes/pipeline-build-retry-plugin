package io.jenkins.plugins.pipelinebuildretry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Queue;
import io.jenkins.plugins.pipelinebuildretry.events.RetryEvent;
import io.jenkins.plugins.pipelinebuildretry.events.RetryEvents;
import io.jenkins.plugins.pipelinebuildretry.evidence.FailureLine;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BuildRetryEventsTest {

    @Test
    void aMissingContextEntryKeepsItsTypeInSimpleForm() {
        assertEquals(
                "MissingContextVariableException[FilePath]",
                RetryActions.simpleName(
                        "org.jenkinsci.plugins.workflow.steps.MissingContextVariableException[hudson.FilePath]"));
        assertEquals("RemovedNodeCause", RetryActions.simpleName("a.b.ExecutorStepExecution$RemovedNodeCause"));
    }

    static final List<RetryEvent> EVENTS = new CopyOnWriteArrayList<>();

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        EVENTS.clear();
    }

    private WorkflowJob job(String options, String body) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition("properties([buildRetry(" + options + ")])\n" + body, true));
        return p;
    }

    private WorkflowRun build(WorkflowJob p, Cause... causes) throws Exception {
        WorkflowRun r = p.scheduleBuild2(0, new CauseAction(causes)).waitForStart();
        j.waitForCompletion(r);
        j.waitUntilNoActivity();
        return r;
    }

    private static List<RetryEvent> named(String name) {
        return EVENTS.stream().filter(e -> e.getName().equals(name)).collect(Collectors.toList());
    }

    private static RetryEvent decisionOf(long build) {
        List<RetryEvent> d = named(BuildRetryEvents.DECISION).stream()
                .filter(e -> Long.valueOf(build).equals(e.getAttributes().get("ci.pipeline.run.number")))
                .collect(Collectors.toList());
        assertEquals(1, d.size(), "one decision event for #" + build + ": " + EVENTS);
        return d.get(0);
    }

    @Test
    void aChainThatRunsOutSendsEveryEvent() throws Exception {
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        EVENTS.clear();
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(0)", "error 'boom'");
        build(p, new Cause.UserIdCause(), new Cause.RemoteCause("host", "note"));

        Map<String, Object> first = decisionOf(1).getAttributes();
        assertEquals(p.getFullName(), first.get("ci.pipeline.id"), "job");
        assertEquals("FAILURE", first.get("ci.pipeline.run.result"), "result");
        assertEquals("queued", first.get("build_retry.outcome"), "outcome");
        assertEquals("retry", first.get("build_retry.reason"), "reason");
        assertEquals(1L, first.get("build_retry.next_retry_count"), "next");
        assertEquals(1L, first.get("build_retry.max_retries"), "max");
        assertEquals(0L, first.get("build_retry.delay_seconds"), "delay");
        assertEquals(List.of("user", "remote"), first.get("build_retry.cause_kinds"), "triggered by");
        assertTrue(first.containsKey("build_retry.queue_item_id"), "queue item");
        assertTrue(String.valueOf(first.get("ci.pipeline.run.url")).endsWith(p.getUrl() + "1/"), "url");
        assertTrue(
                decisionOf(1).getBody().endsWith("FAILURE → retry 1/1 in 0s [queued]"),
                decisionOf(1).getBody());

        Map<String, Object> started =
                named(BuildRetryEvents.RETRY_STARTED).get(0).getAttributes();
        assertEquals(
                List.of(2L, 1L, 1L),
                List.of(
                        started.get("ci.pipeline.run.number"),
                        started.get("build_retry.retry_count"),
                        started.get("build_retry.root_build_number")));
        assertEquals(0L, started.get("build_retry.planned_delay_seconds"), "planned delay");
        assertTrue(String.valueOf(started.get("build_retry.cause_kinds")).contains("retry"), "retry cause");

        Map<String, Object> second = decisionOf(2).getAttributes();
        assertEquals("no-retry", second.get("build_retry.outcome"), "outcome");
        assertEquals("max-reached", second.get("build_retry.reason"), "reason");
        assertEquals(1L, second.get("build_retry.root_build_number"), "root");

        List<RetryEvent> ended = named(BuildRetryEvents.CHAIN_ENDED);
        assertEquals(1, ended.size(), "one chain end: " + EVENTS);
        assertEquals("exhausted", ended.get(0).getAttributes().get("build_retry.chain_end"), "how");
        assertEquals(2L, ended.get(0).getAttributes().get("build_retry.attempts"), "attempts");
        assertTrue(ended.get(0).getAttributes().containsKey("build_retry.chain_duration_ms"), "chain time");
    }

    @Test
    void aDecisionNamesTheFailureItsAgentAndAFingerprintThatHoldsAcrossTheChain() throws Exception {
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        j.waitOnline(j.createSlave("box-a", "linux docker", null));
        EVENTS.clear();
        WorkflowJob p = job(
                "maxSchedule: 1, delay: fixedDelay(0)",
                "stage('Test') { node('linux') { error \"boom in build ${env.BUILD_NUMBER}\" } }");
        build(p);
        Map<String, Object> first = decisionOf(1).getAttributes();
        Map<String, Object> second = decisionOf(2).getAttributes();
        assertEquals("script-failure", first.get("build_retry.failure_class"), "class");
        assertEquals("Test", first.get("build_retry.error_stage"), "stage");
        assertEquals(List.of("box-a"), first.get("build_retry.failed_agents"), "agent");
        assertEquals(List.of("docker", "linux"), first.get("build_retry.failed_agent_labels"), "labels");
        String fingerprint = String.valueOf(first.get("build_retry.error_fingerprint"));
        assertEquals(12, fingerprint.length(), fingerprint);
        assertEquals(fingerprint, second.get("build_retry.error_fingerprint"), "the same error in the retry");
        assertEquals("error", first.get("build_retry.error_step"), "step");
        assertEquals("step", first.get("build_retry.error_basis"), "an error step has no log");
        assertTrue(
                first.values().stream().noneMatch(v -> String.valueOf(v).contains("boom in build")),
                "the error text itself is not sent");
    }

    private List<LogRecord> capture(Class<?> source, ThrowingRunnable body) throws Exception {
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logged.add(r);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        h.setLevel(Level.ALL);
        Logger log = Logger.getLogger(source.getName());
        Level before = log.getLevel();
        log.setLevel(Level.FINE);
        log.addHandler(h);
        try {
            body.run();
        } finally {
            log.removeHandler(h);
            log.setLevel(before);
        }
        return logged;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void shFailuresAreToldApartByTheirLogAndTheSameOneKeepsItsFingerprint() throws Exception {
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        WorkflowJob differ = job(
                "maxSchedule: 1, delay: fixedDelay(0)",
                "stage('Test') { node { sh 'if [ \"$BUILD_NUMBER\" = 1 ]; then echo \"error: x.c undeclared\"; "
                        + "else echo \"curl: (28) Timeout was reached\"; fi; exit 1' } }");
        build(differ);
        Map<String, Object> one = decisionOf(1).getAttributes();
        Map<String, Object> two = decisionOf(2).getAttributes();
        assertEquals("sh", one.get("build_retry.error_step"), "step");
        assertEquals("log-line", one.get("build_retry.error_basis"), "basis");
        assertEquals("log-line", two.get("build_retry.error_basis"), "basis");
        assertNotEquals(
                one.get("build_retry.error_fingerprint"),
                two.get("build_retry.error_fingerprint"),
                "one script, two root causes");
        EVENTS.clear();
        WorkflowJob same = job(
                "maxSchedule: 1, delay: fixedDelay(0)",
                "stage('Test') { node { sh \"echo 'fatal: repo build-${env.BUILD_NUMBER} not found'; exit 1\" } }");
        build(same);
        assertEquals(
                decisionOf(1).getAttributes().get("build_retry.error_fingerprint"),
                decisionOf(2).getAttributes().get("build_retry.error_fingerprint"),
                "the same failure in the retry");
        for (RetryEvent e : EVENTS) {
            assertTrue(
                    e.getAttributes().values().stream()
                            .noneMatch(v -> String.valueOf(v).contains("not found")
                                    || String.valueOf(v).contains("echo")),
                    "no log text or script in " + e.getAttributes());
        }
    }

    @Test
    void theStepLogIsReadOnlyWhileEventsAreOn() throws Exception {
        String script = "stage('Test') { node { sh 'echo \"fatal: nope\"; exit 1' } }";
        WorkflowJob off = job("maxSchedule: 1, delay: fixedDelay(0), dryRun: true", script);
        List<LogRecord> quiet = capture(FailureLine.class, () -> build(off));
        assertEquals(List.of(), quiet.stream().map(LogRecord::getMessage).toList(), "events off");
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        WorkflowJob on = job("maxSchedule: 1, delay: fixedDelay(0), dryRun: true", script);
        List<LogRecord> read = capture(FailureLine.class, () -> build(on));
        assertEquals(1, read.size(), "one read for one decision: " + read);
        WorkflowJob passes =
                job("maxSchedule: 1, delay: fixedDelay(0), dryRun: true", "stage('Test') { node { sh 'echo fine' } }");
        assertEquals(List.of(), capture(FailureLine.class, () -> build(passes)), "a passing build");
    }

    @Test
    void aRetryThatPassesEndsTheChainRecovered() throws Exception {
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        WorkflowJob p = job(
                "maxSchedule: 3, delay: fixedDelay(0)",
                "if (((env.BUILD_RETRY_COUNT ?: '0') as int) < 2) { error 'flaky' }");
        build(p);
        assertEquals(3, p.getBuilds().size(), "two retries");
        assertEquals("queued", decisionOf(2).getAttributes().get("build_retry.outcome"), "a retry that retries");
        assertEquals(
                1L, decisionOf(3).getAttributes().get("build_retry.root_build_number"), "root kept down the chain");
        List<RetryEvent> ended = named(BuildRetryEvents.CHAIN_ENDED);
        assertEquals(1, ended.size(), "one chain end: " + EVENTS);
        assertEquals("recovered", ended.get(0).getAttributes().get("build_retry.chain_end"), "how");
        assertEquals(3L, ended.get(0).getAttributes().get("build_retry.attempts"), "attempts");
        assertEquals("success", decisionOf(3).getAttributes().get("build_retry.reason"), "reason");
    }

    @Test
    void aBuildThatIsNotRetriedSendsOnlyItsDecision() throws Exception {
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        EVENTS.clear();
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(0), dryRun: true", "error 'boom'");
        build(p);
        List<RetryEvent> buildEvents = EVENTS.stream()
                .filter(e -> !e.getName().equals(JobConfigEvents.JOB_CONFIG))
                .collect(Collectors.toList());
        assertEquals(1, buildEvents.size(), "only the decision: " + EVENTS);
        assertEquals(0, named(JobConfigEvents.JOB_CONFIG).size(), "the job list is off by default: " + EVENTS);
        Map<String, Object> a = decisionOf(1).getAttributes();
        assertEquals("dry-run", a.get("build_retry.outcome"), "outcome");
        assertEquals(true, a.get("build_retry.would_retry"), "would retry");
        assertEquals(true, a.get("build_retry.dry_run"), "dry run");
        assertNull(a.get("build_retry.root_build_number"), "no chain");
    }

    @Test
    void aNeverRetryMatchIsSentByIndexOnly() throws Exception {
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        EVENTS.clear();
        WorkflowJob p = job(
                "maxSchedule: 1, delay: fixedDelay(0), neverRetryPatterns: ['nope', 'secret-deny-[0-9]+']",
                "echo 'secret-deny-42'\nerror 'boom'");
        build(p);
        Map<String, Object> a = decisionOf(1).getAttributes();
        assertEquals("never-retry-matched", a.get("build_retry.reason"), "reason code");
        assertEquals(true, a.get("build_retry.never_retry_checked"), "checked");
        assertEquals(true, a.get("build_retry.never_retry_matched"), "matched");
        assertEquals(2L, a.get("build_retry.never_retry_pattern_index"), "index");
        String all = a.values() + decisionOf(1).getBody();
        assertFalse(all.contains("secret-deny"), "no pattern text: " + all);

        EVENTS.clear();
        WorkflowJob q = job("maxSchedule: 1, delay: fixedDelay(0), neverRetryPatterns: ['nope']", "error 'boom'");
        build(q);
        Map<String, Object> b = decisionOf(1).getAttributes();
        assertEquals(true, b.get("build_retry.never_retry_checked"), "checked");
        assertEquals(false, b.get("build_retry.never_retry_matched"), "no match");
        assertNull(b.get("build_retry.never_retry_pattern_index"), "no index");
    }

    @Test
    void nothingIsSentWhileSendEventsIsOff() throws Exception {
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(0)", "error 'boom'");
        build(p);
        BuildRetryGlobalConfiguration.get().setEnabled(false);
        assertEquals(List.of(), EVENTS, "off by default");
    }

    @Test
    void theOffSwitchStillReportsEachBuild() throws Exception {
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        BuildRetryGlobalConfiguration.get().setEnabled(false);
        EVENTS.clear();
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(0)", "error 'boom'");
        build(p);
        Map<String, Object> a = decisionOf(1).getAttributes();
        assertEquals("disabled", a.get("build_retry.outcome"), "outcome");
        assertEquals("disabled", a.get("build_retry.reason"), "reason");
        assertEquals(1, p.getBuilds().size(), "no retry");
    }

    @Test
    void settingChangesAreSentOnlyWhenTheyChange() throws Exception {
        BuildRetryGlobalConfiguration c = BuildRetryGlobalConfiguration.get();
        c.setEnabled(false);
        assertEquals(List.of(), EVENTS, "not sending yet");
        c.setSendEvents(true);
        c.setEnabled(false);
        c.setEnabled(true);
        c.setSendEvents(false);
        c.setEnabled(false);
        List<String> changes = named(BuildRetryEvents.SWITCH_CHANGED).stream()
                .map(e -> e.getAttributes().get("build_retry.setting") + ":"
                        + e.getAttributes().get("build_retry.old_value") + "→"
                        + e.getAttributes().get("build_retry.new_value"))
                .collect(Collectors.toList());
        assertEquals(List.of("sendEvents:false→true", "enabled:false→true", "sendEvents:true→false"), changes);
    }

    @Test
    void aCancelledRetryIsReported() throws Exception {
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(600)", "if (currentBuild.number == 1) { error 'boom' }");
        DisableConcurrentBuildsJobProperty d = new DisableConcurrentBuildsJobProperty();
        d.setAbortPrevious(true);
        p.addProperty(d);
        j.waitForCompletion(p.scheduleBuild2(0).waitForStart());
        j.buildAndAssertSuccess(p);
        List<RetryEvent> cancelled = named(BuildRetryEvents.RETRY_CANCELLED);
        assertEquals(1, cancelled.size(), "one cancel: " + EVENTS);
        Map<String, Object> a = cancelled.get(0).getAttributes();
        assertEquals("superseded", a.get("build_retry.reason"), "reason");
        assertEquals(
                List.of(2L, 1L, 1L),
                List.of(
                        a.get("ci.pipeline.run.number"),
                        a.get("build_retry.retry_count"),
                        a.get("build_retry.parent_build_number")));
        List<RetryEvent> ended = named(BuildRetryEvents.CHAIN_ENDED);
        assertEquals(1, ended.size(), "one chain end: " + EVENTS);
        Map<String, Object> e = ended.get(0).getAttributes();
        assertEquals("cancelled", e.get("build_retry.chain_end"), "how");
        assertEquals("superseded", e.get("build_retry.reason"), "reason");
        assertEquals(
                List.of(1L, 1L, 1L, 2L, "FAILURE"),
                List.of(
                        e.get("ci.pipeline.run.number"),
                        e.get("build_retry.attempts"),
                        e.get("build_retry.root_build_number"),
                        e.get("build_retry.superseded_by"),
                        e.get("ci.pipeline.run.result")));
        assertTrue(e.containsKey("build_retry.chain_duration_ms"), "chain time");
    }

    @Test
    void aRetryCancelledInTheQueueIsReported() throws Exception {
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(600)", "error 'boom'");
        j.waitForCompletion(p.scheduleBuild2(0).waitForStart());
        Queue.Item item = waitingRetry(p);
        assertTrue(j.jenkins.getQueue().cancel(item), "cancelled");
        assertCancelledInQueue(item, "internal");
    }

    @Test
    void aRetryCancelledFromTheWebSaysSo() throws Exception {
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(600)", "error 'boom'");
        j.waitForCompletion(p.scheduleBuild2(0).waitForStart());
        Queue.Item item = waitingRetry(p);
        JenkinsRule.WebClient wc = j.createWebClient();
        WebRequest req =
                new WebRequest(new URI(j.getURL() + "queue/cancelItem?id=" + item.getId()).toURL(), HttpMethod.POST);
        wc.addCrumb(req);
        wc.getPage(req);
        assertCancelledInQueue(item, "web");
    }

    @Test
    void thePluginsOwnCancelIsNotReportedAgain() throws Exception {
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(600)", "error 'boom'");
        j.waitForCompletion(p.scheduleBuild2(0).waitForStart());
        assertTrue(CancelledRetryListener.cancel(j.jenkins.getQueue(), waitingRetry(p)), "cancelled");
        j.waitUntilNoActivity();
        assertEquals(List.of(), named(BuildRetryEvents.RETRY_CANCELLED), "no cancel event");
        assertEquals(List.of(), named(BuildRetryEvents.CHAIN_ENDED), "no chain end");
    }

    private Queue.Item waitingRetry(WorkflowJob p) {
        List<Queue.Item> items = j.jenkins.getQueue().getItems(p);
        assertEquals(1, items.size(), "one waiting retry");
        return items.get(0);
    }

    private void assertCancelledInQueue(Queue.Item item, String source) throws Exception {
        j.waitUntilNoActivity();
        assertEquals(0, j.jenkins.getQueue().getItems().length, "queue empty");
        List<RetryEvent> cancelled = named(BuildRetryEvents.RETRY_CANCELLED);
        assertEquals(1, cancelled.size(), "one cancel: " + EVENTS);
        Map<String, Object> a = cancelled.get(0).getAttributes();
        assertEquals(
                List.of(1L, 1L, 1L, "queue-cancelled", source, item.getId()),
                List.of(
                        a.get("ci.pipeline.run.number"),
                        a.get("build_retry.retry_count"),
                        a.get("build_retry.parent_build_number"),
                        a.get("build_retry.reason"),
                        a.get("build_retry.cancel_source"),
                        a.get("build_retry.queue_item_id")));
        List<RetryEvent> ended = named(BuildRetryEvents.CHAIN_ENDED);
        assertEquals(1, ended.size(), "one chain end: " + EVENTS);
        Map<String, Object> e = ended.get(0).getAttributes();
        assertEquals(
                List.of("cancelled", "queue-cancelled", 1L, 1L, 1L, "FAILURE"),
                List.of(
                        e.get("build_retry.chain_end"),
                        e.get("build_retry.reason"),
                        e.get("ci.pipeline.run.number"),
                        e.get("build_retry.attempts"),
                        e.get("build_retry.root_build_number"),
                        e.get("ci.pipeline.run.result")));
    }

    @Test
    void aRetryCopiedByAnotherPluginLeavesTheChainToTheCopy() throws Exception {
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        WorkflowJob p = job(
                "maxSchedule: 2, delay: fixedDelay(0)",
                "if (env.BUILD_RETRY_COUNT == '1') { echo 'started'\nsleep 2 }\nerror 'boom'");
        j.waitForCompletion(p.scheduleBuild2(0).waitForStart());
        WorkflowRun retry = null;
        for (int i = 0; i < 300 && retry == null; i++) {
            retry = p.getBuildByNumber(2);
            Thread.sleep(100);
        }
        j.waitForMessage("started", retry);
        Queue.Item copy =
                j.jenkins.getQueue().schedule2(p, 600, retry.getActions()).getItem();
        j.waitForCompletion(retry);
        Map<String, Object> a = decisionOf(2).getAttributes();
        assertEquals("requeued-elsewhere", a.get("build_retry.outcome"), "outcome");
        assertEquals(copy.getId(), a.get("build_retry.copy_queue_item_id"), "the copy's queue item");
        assertNull(a.get("build_retry.copy_build_number"), "not started yet");
        assertEquals(List.of(), named(BuildRetryEvents.CHAIN_ENDED), "the copy ends the chain: " + EVENTS);
        j.jenkins.getQueue().clear();
    }

    @Test
    void aCopyThatRanIsLinkedByBuildNumberAndQueueItem() throws Exception {
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        WorkflowJob p = job(
                "maxSchedule: 1, delay: fixedDelay(0)",
                "if (currentBuild.number == 1) { echo 'started'\nsleep 5\nerror 'boom' } else { echo 'copy' }");
        WorkflowRun r =
                p.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause("a"))).waitForStart();
        j.waitForMessage("started", r);
        Queue.Item copy = j.jenkins.getQueue().schedule2(p, 0, r.getActions()).getItem();
        j.waitForCompletion(r);
        j.waitUntilNoActivity();
        Map<String, Object> first = decisionOf(1).getAttributes();
        assertEquals("requeued-elsewhere", first.get("build_retry.outcome"), "outcome");
        assertEquals(2L, first.get("build_retry.copy_build_number"), "the copy's build");
        assertEquals(r.getQueueId(), first.get("ci.pipeline.run.queue_item_id"), "own queue item");
        assertEquals(
                copy.getId(),
                decisionOf(2).getAttributes().get("ci.pipeline.run.queue_item_id"),
                "the copy carries the item the original named");
    }

    @Test
    void aBrokenSinkDoesNotStopTheRetry() throws Exception {
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logged.add(r);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger log = Logger.getLogger(RetryEvents.class.getName());
        log.addHandler(capture);
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        WorkflowJob p = job("maxSchedule: 1, delay: fixedDelay(0)", "error 'boom'");
        try {
            build(p);
        } finally {
            log.removeHandler(capture);
        }
        List<String> warnings = logged.stream()
                .filter(r -> r.getLevel() == Level.WARNING)
                .map(LogRecord::getMessage)
                .collect(Collectors.toList());
        assertEquals(1, warnings.size(), "one warning for several failed events: " + warnings);
        assertTrue(
                warnings.get(0).contains(Broken.class.getName())
                        && warnings.get(0).contains("IllegalStateException: collector down"),
                warnings.get(0));
        assertEquals(2, p.getBuilds().size(), "retried");
        assertEquals(
                "queued", p.getBuildByNumber(1).getAction(DecidedAction.class).getOutcome(), "decided");
        assertEquals("queued", decisionOf(1).getAttributes().get("build_retry.outcome"), "the other sink still got it");
    }

    @Test
    void gerritDetailsAreReadByName() {
        Map<String, String> g = BuildRetryEvents.gerrit(new FakeGerritCause());
        assertEquals(
                Map.of(
                        "build_retry.trigger.gerrit_event", "patchset-created",
                        "build_retry.trigger.gerrit_change", "1234",
                        "build_retry.trigger.gerrit_project", "some/project",
                        "build_retry.trigger.gerrit_patchset", "5"),
                g);
        assertEquals(Map.of(), BuildRetryEvents.gerrit(new Object()), "no getters, nothing");
    }

    public static class FakeGerritCause {
        public Object getEvent() {
            return new Event();
        }
    }

    public static class Event {
        public Object getEventType() {
            return new Type();
        }

        public Object getChange() {
            return new Change();
        }

        public Object getPatchSet() {
            return new PatchSet();
        }
    }

    public static class Type {
        public String getTypeValue() {
            return "patchset-created";
        }
    }

    public static class Change {
        public String getNumber() {
            return "1234";
        }

        public String getProject() {
            return "some/project";
        }
    }

    public static class PatchSet {
        public String getNumber() {
            return "5";
        }
    }

    @TestExtension
    public static class Capture implements RetryEvents {
        @Override
        public void emit(RetryEvent event) {
            EVENTS.add(event);
        }
    }

    @TestExtension("aBrokenSinkDoesNotStopTheRetry")
    public static class Broken implements RetryEvents {
        @Override
        public void emit(RetryEvent event) {
            throw new IllegalStateException("collector down");
        }
    }
}
