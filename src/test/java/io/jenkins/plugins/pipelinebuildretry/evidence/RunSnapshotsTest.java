package io.jenkins.plugins.pipelinebuildretry.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Result;
import hudson.slaves.DumbSlave;
import io.jenkins.plugins.pipelinebuildretry.BuildRetryAction;
import io.jenkins.plugins.pipelinebuildretry.core.RetryDecision;
import io.jenkins.plugins.pipelinebuildretry.core.RetryOptions;
import io.jenkins.plugins.pipelinebuildretry.core.RunSnapshot;
import io.jenkins.plugins.pipelinebuildretry.core.RunSnapshot.ErrorLine;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import jenkins.model.CauseOfInterruption;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class RunSnapshotsTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    private WorkflowJob job(String script) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(script, true));
        return p;
    }

    private WorkflowRun run(String script, Result expected) throws Exception {
        return j.buildAndAssertStatus(expected, job(script));
    }

    private static RunSnapshot snap(WorkflowRun r) {
        return RunSnapshots.of(r, r.getResult());
    }

    private DumbSlave agent(String name, String label) throws Exception {
        DumbSlave a = j.createSlave(name, label, null);
        j.waitOnline(a);
        return a;
    }

    @Test
    void failedAgentsKeepTheirLabelsButNotTheirOwnName() throws Exception {
        agent("box-a", "linux docker");
        agent("box-b", "windows");
        RunSnapshot s = snap(run("node('box-b') { echo 'fine' }\nnode('linux') { error 'boom' }", Result.FAILURE));
        assertEquals(List.of("box-a"), s.getFailedAgents(), "only the agent where the error started");
        assertEquals(List.of("docker", "linux"), s.getFailedAgentLabels(), "its labels, sorted");
        RunSnapshot passed = snap(run("node('windows') { echo 'fine' }", Result.SUCCESS));
        assertEquals(List.of(), passed.getFailedAgents(), "none failed");
        assertEquals(List.of(), new RunSnapshot().getFailedAgentLabels(), "empty by default");
    }

    @Test
    void declarativeFailedStage() throws Exception {
        WorkflowRun r = run("""
                pipeline {
                  agent none
                  stages {
                    stage('ok') { steps { echo 'fine' } }
                    stage('broken') { steps { echo 'before'; error 'boom' } }
                    stage('later') { steps { echo 'never' } }
                  }
                  post { always { echo 'post' } }
                }
                """, Result.FAILURE);
        RunSnapshot s = snap(r);
        assertEquals("FAILURE", s.getResult(), "result");
        assertEquals(List.of("broken"), s.getFailedStages(), "skipped and passing stages left out");
        assertEquals(1, s.getFailedStageIds().size(), "one stage id");
        assertTrue(
                s.getErrorClasses().contains("hudson.AbortException"),
                s.getErrorClasses().toString());
        List<ErrorLine> lines = s.getStageErrorLines();
        assertEquals(1, lines.size(), lines.toString());
        assertEquals("ERROR: boom", lines.get(0).line(), "the line finish prints");
        assertTrue(lines.get(0).abort(), "an AbortException");
        assertEquals(s.getFailedStageIds().get(0), lines.get(0).stageId(), "tied to the failed stage");
        assertEquals("broken", lines.get(0).stage(), "and named after it");
        assertEquals(List.of(), s.getInterruptionCauses(), "no interruption");
        assertFalse(s.isRetryableCauseSeen(), "no agent error");
        assertNull(s.getFlowGraphError(), "graph read");
        assertFalse(s.isReplay() || s.isRetry() || s.isAbortPrevious(), "plain build");
    }

    @Test
    void postActionsNeverCountAsAStage() throws Exception {
        RunSnapshot s = snap(run("""
                pipeline {
                  agent none
                  stages { stage('ok') { steps { echo 'fine' } } }
                  post { always { error 'post broke' } }
                }
                """, Result.FAILURE));
        assertEquals(List.of(), s.getFailedStages(), "post is not a stage");
        assertTrue(s.getErrorClasses().contains("hudson.AbortException"), "its error is still seen");
    }

    @Test
    void unstableInPostIsNotAStage() throws Exception {
        RunSnapshot s = snap(run("""
                pipeline {
                  agent none
                  stages { stage('ok') { steps { echo 'fine' } } }
                  post { always { unstable 'post warned' } }
                }
                """, Result.UNSTABLE));
        assertEquals(List.of(), s.getFailedStages(), "post warnings name no stage");
    }

    @Test
    void interruptedStageAddsNoErrorLine() throws Exception {
        RunSnapshot s = snap(run("stage('slow') { timeout(time: 1, unit: 'SECONDS') { sleep 30 } }", Result.ABORTED));
        assertEquals(List.of("slow"), s.getFailedStages(), "the stage failed");
        assertEquals(List.of(), s.getStageErrorLines(), "finish prints no ERROR line for an interruption");
    }

    @Test
    void failedStagesAndLinesAreInRunOrder() throws Exception {
        RunSnapshot s = snap(run("""
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') { stage('a') { error 'A' } }
                stage('b') { error 'B' }
                """, Result.FAILURE));
        assertEquals(List.of("a", "b"), s.getFailedStages(), "stages");
        assertEquals(
                List.of("ERROR: A", "ERROR: B"),
                s.getStageErrorLines().stream().map(ErrorLine::line).toList(),
                "lines in node order");
    }

    @Test
    void unstableAndCaughtStagesCount() throws Exception {
        RunSnapshot s = snap(run("""
                pipeline {
                  agent none
                  stages {
                    stage('warn') { steps { unstable 'flaky' } }
                    stage('caught') { steps { catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') { error 'x' } } }
                    stage('clean') { steps { echo 'ok' } }
                  }
                }
                """, Result.UNSTABLE));
        assertEquals(List.of("warn", "caught"), s.getFailedStages(), "in run order");
        assertEquals(List.of(), s.getStageErrorLines(), "no error reached a stage end");
    }

    @Test
    void scriptedNestedStagesReportTheOuterOne() throws Exception {
        RunSnapshot s = snap(run("stage('outer') { stage('inner') { error 'deep' } }", Result.FAILURE));
        assertEquals(List.of("outer"), s.getFailedStages(), "top level only");
        assertEquals(1, s.getStageErrorLines().size(), s.getStageErrorLines().toString());
        assertEquals("ERROR: deep", s.getStageErrorLines().get(0).line(), "one line per error");
    }

    @Test
    void userAbortIsAUserInterruption() throws Exception {
        WorkflowJob p = job("sleep 60");
        WorkflowRun r = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Sleeping for", r);
        r.getExecutor().interrupt(Result.ABORTED, new CauseOfInterruption.UserInterruption("alice"));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(r));
        RunSnapshot s = snap(r);
        assertEquals(List.of(RetryDecision.USER_INTERRUPTION), s.getInterruptionCauses(), "user");
        assertTrue(s.getErrorClasses().contains(FlowInterruptedException.class.getName()), "interrupted");
    }

    @Test
    void timeoutIsATimeoutInterruption() throws Exception {
        RunSnapshot s = snap(run("timeout(time: 1, unit: 'SECONDS') { sleep 30 }", Result.ABORTED));
        assertEquals(List.of(RetryDecision.TIMEOUT_INTERRUPTION), s.getInterruptionCauses(), "timeout");
    }

    @Test
    void agentRemovedMidShell() throws Exception {
        long saved = ExecutorStepExecution.TIMEOUT_WAITING_FOR_NODE_MILLIS;
        ExecutorStepExecution.TIMEOUT_WAITING_FOR_NODE_MILLIS = 1000;
        try {
            DumbSlave a = agent("doomed", "remote");
            WorkflowJob p = job("node('remote') { sh 'echo started; sleep 60' }");
            WorkflowRun r = p.scheduleBuild2(0).waitForStart();
            j.waitForMessage("started", r);
            j.jenkins.removeNode(a);
            j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(r));
            RunSnapshot s = snap(r);
            assertEquals(
                    List.of(ExecutorStepExecution.RemovedNodeCause.class.getName()),
                    s.getInterruptionCauses(),
                    "removed-node cause");
            assertTrue(s.isRetryableCauseSeen(), "the cause is retryable");
            assertEquals(Map.of("doomed", List.of()), s.getFailedAgentReasons(), "the removed agent failed");
            Map<String, Object> d = RetryDecision.decide(new RetryOptions().rerunIfAborted("infra"), s)
                    .toMap();
            assertEquals("infra", d.get("abortKind"), "infra abort without a log scan");
            assertEquals(true, d.get("willRetry"), "retried under rerunIfAborted: infra");
        } finally {
            ExecutorStepExecution.TIMEOUT_WAITING_FOR_NODE_MILLIS = saved;
        }
    }

    @Test
    void replayIsMarked() throws Exception {
        WorkflowJob p = job("echo 'hi'");
        WorkflowRun first = j.buildAndAssertSuccess(p);
        assertFalse(snap(first).isReplay(), "first build");
        WorkflowRun second = (WorkflowRun) first.getAction(ReplayAction.class)
                .run("echo 'again'", Map.of())
                .get();
        j.assertBuildStatusSuccess(second);
        assertTrue(snap(second).isReplay(), "replay");
    }

    @Test
    void userCauseAndRetryCounter() throws Exception {
        WorkflowJob p = job("echo 'hi'");
        WorkflowRun byUser =
                j.assertBuildStatusSuccess(p.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause("alice"))));
        RunSnapshot u = snap(byUser);
        assertTrue(u.isStartedByUser(), "user cause");
        assertFalse(u.isRetry(), "not a retry");
        assertEquals(0, u.getRetryCount(), "count 0");
        WorkflowRun retry = j.assertBuildStatusSuccess(p.scheduleBuild2(
                0, new CauseAction(new Cause.UpstreamCause(byUser)), new BuildRetryAction(2, 3, byUser.getNumber())));
        RunSnapshot r = snap(retry);
        assertTrue(r.isRetry(), "retry");
        assertEquals(2, r.getRetryCount(), "count from the action");
        assertFalse(r.isStartedByUser(), "upstream cause only");
    }

    @Test
    void abortPreviousIsRead() throws Exception {
        WorkflowJob p = job("echo 'hi'");
        assertFalse(snap(j.buildAndAssertSuccess(p)).isAbortPrevious(), "no property");
        DisableConcurrentBuildsJobProperty d = new DisableConcurrentBuildsJobProperty();
        d.setAbortPrevious(true);
        p.addProperty(d);
        assertTrue(snap(j.buildAndAssertSuccess(p)).isAbortPrevious(), "abortPrevious");
    }

    @Test
    void terminationReasonsOfTheFailingAgent() throws Exception {
        agent("fine-agent", "a");
        agent("oom-agent", "b");
        RunSnapshot s = snap(run("node('a') { echo 'fine' }\nnode('b') { error 'killed' }", Result.FAILURE));
        assertEquals(List.of("Error", "OOMKilled"), s.getTerminationReasons(), "sorted, over every agent");
        assertEquals(Map.of("oom-agent", List.of("Error", "OOMKilled")), s.getFailedAgentReasons(), "failing only");
    }

    @Test
    void failingAgentWithoutReasons() throws Exception {
        agent("plain-agent", "c");
        RunSnapshot s = snap(run("node('c') { error 'plain' }", Result.FAILURE));
        assertEquals(List.of(), s.getTerminationReasons(), "none known");
        assertEquals(Map.of("plain-agent", List.of()), s.getFailedAgentReasons(), "agent listed, no reasons");
        RunSnapshot builtIn = snap(run("node { error 'built-in' }", Result.FAILURE));
        assertEquals(Map.of(), builtIn.getFailedAgentReasons(), "the built-in node is never an agent");
    }

    @Test
    void failedAgentIsWhereTheErrorStarted() throws Exception {
        agent("outer-agent", "outer");
        agent("inner-agent", "inner");
        RunSnapshot declarative = snap(run("""
                pipeline {
                  agent { label 'outer' }
                  stages {
                    stage('bad') {
                      agent { label 'inner' }
                      steps { error 'boom' }
                    }
                    stage('later') { steps { echo 'skipped' } }
                  }
                }
                """, Result.FAILURE));
        assertEquals(
                List.of("inner-agent"),
                List.copyOf(declarative.getFailedAgentReasons().keySet()),
                "Declarative");
        RunSnapshot scripted = snap(run("node('outer') { node('inner') { error 'x' } }", Result.FAILURE));
        assertEquals(
                List.of("inner-agent"),
                List.copyOf(scripted.getFailedAgentReasons().keySet()),
                "Scripted");
    }

    @Test
    void errorClassesFollowTheCauseChain() throws Exception {
        ScriptApproval.get().preapproveAll();
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(
                new CpsFlowDefinition("throw new RuntimeException('outer', new java.io.EOFException('inner'))", false));
        RunSnapshot s = snap(j.buildAndAssertStatus(Result.FAILURE, p));
        assertTrue(
                s.getErrorClasses().contains("java.lang.RuntimeException"),
                s.getErrorClasses().toString());
        assertTrue(
                s.getErrorClasses().contains("java.io.EOFException"),
                s.getErrorClasses().toString());
        RunSnapshot missing = snap(run("sh 'true'", Result.FAILURE));
        assertTrue(
                missing.getErrorClasses()
                        .contains(
                                "org.jenkinsci.plugins.workflow.steps.MissingContextVariableException[hudson.FilePath]"),
                missing.getErrorClasses().toString());
        assertFalse(missing.isAgentContextLost(), "sh outside node did not lose an agent");
    }

    @TestExtension("terminationReasonsOfTheFailingAgent")
    public static class FakeReasons implements TerminationReasons {
        @Override
        public Collection<String> reasons(String agent) {
            return agent.equals("oom-agent") ? List.of("OOMKilled", "Error") : List.of();
        }
    }

    @TestExtension("terminationReasonsOfTheFailingAgent")
    public static class BrokenReasons implements TerminationReasons {
        @Override
        public Collection<String> reasons(String agent) {
            throw new IllegalStateException("cloud unreachable");
        }
    }
}
