package io.jenkins.plugins.pipelinebuildretry.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.console.AnnotatedLargeText;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.TaskListener;
import io.jenkins.plugins.pipelinebuildretry.core.RetryDecision;
import io.jenkins.plugins.pipelinebuildretry.core.RetryOptions;
import io.jenkins.plugins.pipelinebuildretry.core.RunSnapshot;
import io.jenkins.plugins.pipelinebuildretry.core.ScanPlan;
import io.jenkins.plugins.pipelinebuildretry.core.ScanResult;
import java.io.File;
import java.io.IOException;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.log.FileLogStorage;
import org.jenkinsci.plugins.workflow.log.LogStorage;
import org.jenkinsci.plugins.workflow.log.LogStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LogScanTest {

    private static final String TWO_STAGES = """
            pipeline {
              agent none
              stages {
                stage('ok') { steps { echo 'Connection reset by peer' } }
                stage('bad') { steps { echo 'before-failure'; error 'boom' } }
              }
            }
            """;

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    private WorkflowRun run(String script, Result expected) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(script, true));
        return j.buildAndAssertStatus(expected, p);
    }

    private static ScanResult scan(WorkflowRun r, RetryOptions o) throws InterruptedException {
        RunSnapshot s = RunSnapshots.of(r, r.getResult());
        ScanPlan plan = RetryDecision.logScanPlan(o, s);
        return LogScan.scan(r, plan, 30_000L, s);
    }

    private static RetryOptions regexp(String scope, String pattern) {
        return new RetryOptions().checkRegexp(true).regexpForRerun(pattern).regexpScope(scope);
    }

    @Test
    void buildScopeReadsTheWholeLog() throws Exception {
        WorkflowRun r = run(TWO_STAGES, Result.FAILURE);
        ScanResult s = scan(r, regexp("build", "Connection reset"));
        assertTrue(s.isMatched(ScanPlan.REGEXP), "passing stage's line is in the console log");
        assertEquals("build", s.getScope(), "scope");
        assertNull(s.getStorageFallback(), "build scope never checks the storage");
        assertTrue(s.getLinesRead() > 0, "lines read");
    }

    @Test
    void failedStagesScopeReadsOnlyTheFailedStage() throws Exception {
        WorkflowRun r = run(TWO_STAGES, Result.FAILURE);
        ScanResult other = scan(r, regexp("failedStages", "Connection reset"));
        assertFalse(other.isMatched(ScanPlan.REGEXP), "passing stage is not read");
        assertEquals("failedStages", other.getScope(), "scope");
        assertNull(other.getStorageFallback(), "file storage streams per step");
        assertTrue(scan(r, regexp("failedStages", "before-failure")).isMatched(ScanPlan.REGEXP), "failed stage's log");
        ScanResult line = scan(r, regexp("failedStages", "^ERROR: boom$"));
        assertTrue(line.isMatched(ScanPlan.REGEXP), "the line finish prints is added");
        assertEquals(1, line.getErrorLines(), "one added line");
    }

    private static RetryOptions never(String scope, String... patterns) {
        return new RetryOptions().regexpScope(scope).neverRetryPatterns(java.util.List.of(patterns));
    }

    @Test
    void neverRetryPatternsFollowTheRegexpScope() throws Exception {
        WorkflowRun r = run(TWO_STAGES, Result.FAILURE);
        ScanResult stages = scan(r, never("failedStages", "Connection reset", "before-failure"));
        assertEquals("failedStages", stages.getScope(), "read per stage without a regexp gate");
        assertFalse(stages.isMatched(ScanPlan.never(1)), "passing stage is not read");
        assertTrue(stages.isMatched(ScanPlan.never(2)), "failed stage's log is read");
        ScanResult whole = scan(r, never("build", "Connection reset"));
        assertTrue(whole.isMatched(ScanPlan.never(1)), "build scope reads every stage");
        Integer line = whole.getMatchLine(ScanPlan.never(1));
        assertTrue(line != null && line > 0, "console line");
        String text = r.getLog();
        assertTrue(text.split("\r?\n")[line - 1].contains("Connection reset"), "the number is the console line");
        ScanResult both = scan(
                r, never("failedStages", "before-failure").checkRegexp(true).regexpForRerun("boom"));
        assertTrue(both.isMatched(ScanPlan.never(1)) && both.isMatched(ScanPlan.REGEXP), "both in the stage pass");
    }

    @Test
    void theConsoleLineCountMarksWhereTheAddedErrorLinesStart() throws Exception {
        WorkflowRun r = run(TWO_STAGES, Result.FAILURE);
        RunSnapshot s = RunSnapshots.of(r, r.getResult())
                .stageErrorLines(
                        java.util.List.of(new RunSnapshot.ErrorLine("9", "two", 1, "ERROR: only-here-42", true)));
        RetryOptions o = never("build", "only-here-[0-9]+");
        ScanResult res = LogScan.scan(r, RetryDecision.logScanPlan(o, s), 30_000L, s);
        int console = r.getLog().split("\\R", -1).length - (r.getLog().endsWith("\n") ? 1 : 0);
        assertEquals(console, res.getConsoleLines(), "every console line counted");
        assertEquals(console + 1, res.getMatchLine(ScanPlan.never(1)), "the added line comes right after");
        assertEquals(
                "never-retry-matched: pattern #1 in a stage error line",
                RetryDecision.decide(o, s.logScan(res)).getReason());
    }

    @Test
    void agentLossPatternsReadTheWholeLogInFailedStagesScope() throws Exception {
        WorkflowRun r = run("""
                pipeline {
                  agent none
                  stages {
                    stage('ok') {
                      steps {
                        echo 'Agent x was deleted; cancelling node body'
                        echo 'Connection reset by peer'
                      }
                    }
                    stage('bad') { steps { error 'boom' } }
                  }
                }
                """, Result.FAILURE);
        ScanResult s = scan(r, regexp("failedStages", "Connection reset").rerunOnAgentLoss(true));
        assertFalse(s.isMatched(ScanPlan.REGEXP), "regexp reads only the failed stage");
        assertTrue(s.isMatched(ScanPlan.AGENT_LOSS), "agent loss from the whole log");
        assertEquals("failedStages", s.getScope(), "scope");
    }

    @Test
    void housekeepingStepsAndStackFramesAreSkippedInStages() throws Exception {
        WorkflowRun r = run("""
                stage('bad') {
                  node {
                    writeFile file: 'f.txt', text: 'x'
                    archiveArtifacts 'f.txt'
                    echo '\tat com.example.Thing.run(Thing.java:1)'
                    error 'boom'
                  }
                }
                """, Result.FAILURE);
        assertTrue(scan(r, regexp("build", "Archiving artifacts")).isMatched(ScanPlan.REGEXP), "console log");
        assertFalse(
                scan(r, regexp("failedStages", "Archiving artifacts")).isMatched(ScanPlan.REGEXP),
                "housekeeping step skipped");
        assertTrue(scan(r, regexp("build", "com.example.Thing")).isMatched(ScanPlan.REGEXP), "console log");
        assertFalse(
                scan(r, regexp("failedStages", "com.example.Thing")).isMatched(ScanPlan.REGEXP), "stack frame skipped");
    }

    @Test
    void nonAbortErrorLinesStayOutOfTheStageScan() throws Exception {
        ScriptApproval.get().preapproveAll();
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(
                "stage('bad') { echo 'step output'; throw new IllegalStateException('Connection reset by peer') }",
                false));
        WorkflowRun r = j.buildAndAssertStatus(Result.FAILURE, p);
        assertTrue(scan(r, regexp("build", "Connection reset")).isMatched(ScanPlan.REGEXP), "console log");
        ScanResult s = scan(r, regexp("failedStages", "Connection reset"));
        assertFalse(s.isMatched(ScanPlan.REGEXP), "only Abort lines are added to a stage scan");
        assertEquals("failedStages", s.getScope(), "the stage's step log was read");
        assertEquals(0, s.getErrorLines(), "no line added");
    }

    @Test
    void bufferedStorageFallsBackToTheWholeLog() throws Exception {
        WorkflowRun r = run(TWO_STAGES, Result.FAILURE);
        ScanResult s = scan(r, regexp("failedStages", "Connection reset"));
        assertTrue(s.isMatched(ScanPlan.REGEXP), "whole log read");
        assertEquals("build", s.getScope(), "scope says what was read");
        assertTrue(
                s.getStorageFallback().startsWith("log storage " + WrappedStorage.class.getName()),
                s.getStorageFallback());
    }

    @TestExtension("bufferedStorageFallsBackToTheWholeLog")
    public static class WrappedStorageFactory implements LogStorageFactory {
        @Override
        public LogStorage forBuild(FlowExecutionOwner owner) {
            try {
                return new WrappedStorage(FileLogStorage.forFile(new File(owner.getRootDir(), "log")));
            } catch (IOException e) {
                return null;
            }
        }
    }

    /** A storage the scan does not know to stream per step. */
    static class WrappedStorage implements LogStorage {
        private final LogStorage inner;

        WrappedStorage(LogStorage inner) {
            this.inner = inner;
        }

        @Override
        public BuildListener overallListener() throws IOException, InterruptedException {
            return inner.overallListener();
        }

        @Override
        public TaskListener nodeListener(FlowNode node) throws IOException, InterruptedException {
            return inner.nodeListener(node);
        }

        @Override
        public AnnotatedLargeText<FlowExecutionOwner.Executable> overallLog(
                FlowExecutionOwner.Executable build, boolean complete) {
            return inner.overallLog(build, complete);
        }

        @Override
        public AnnotatedLargeText<FlowNode> stepLog(FlowNode node, boolean complete) {
            return inner.stepLog(node, complete);
        }

        @Override
        public File getLogFile(FlowExecutionOwner.Executable build, boolean complete) {
            return inner.getLogFile(build, complete);
        }
    }

    @Test
    void ownLinesAreSkipped() throws Exception {
        WorkflowRun r = run("echo '" + RetryDecision.LOG_PREFIX + "Connection reset'\nerror 'x'", Result.FAILURE);
        assertFalse(scan(r, regexp("build", "Connection reset")).isMatched(ScanPlan.REGEXP), "plugin line");
    }

    @Test
    void thePrefixDoesNotHideALineFromNeverRetryPatterns() throws Exception {
        WorkflowRun r = run("echo '" + RetryDecision.LOG_PREFIX + "COMPILATION ERROR'\nerror 'x'", Result.FAILURE);
        assertTrue(scan(r, never("build", "COMPILATION ERROR")).isMatched(ScanPlan.never(1)), "spoofed line");
    }

    @Test
    void scriptedWithoutStagesFallsBackToTheWholeLog() throws Exception {
        WorkflowRun r = run("echo 'Connection reset'\nerror 'x'", Result.FAILURE);
        ScanResult s = scan(r, regexp("failedStages", "Connection reset"));
        assertTrue(s.isMatched(ScanPlan.REGEXP), "no failed stage: whole log");
        assertEquals("build", s.getScope(), "scope says what was read");
    }
}
