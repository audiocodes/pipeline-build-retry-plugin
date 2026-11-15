package io.jenkins.plugins.pipelinebuildretry.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.model.Result;
import io.jenkins.plugins.pipelinebuildretry.BuildRetryGlobalConfiguration;
import io.jenkins.plugins.pipelinebuildretry.DecidedAction;
import io.jenkins.plugins.pipelinebuildretry.PointOfNoReturnAction;
import io.jenkins.plugins.pipelinebuildretry.RecordedDecisionAction;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BuildRetryStepsTest {

    private static final String OPTIONS = "properties([buildRetry(maxSchedule: 1, delay: fixedDelay(0))])\n";

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    private WorkflowRun run(String script, Result expected) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun r = j.buildAndAssertStatus(expected, p);
        j.waitUntilNoActivity();
        return r;
    }

    @Test
    void declarativePostPreviewIsUsedAtTheEnd() throws Exception {
        WorkflowRun r = run("""
                pipeline {
                  agent none
                  options { buildRetry(maxSchedule: 1, delay: fixedDelay(0)) }
                  stages { stage('one') { steps { error 'boom' } } }
                  post {
                    always {
                      script {
                        def d = buildRetryDecision()
                        echo "preview willRetry=${d.willRetry} reason=${d.reason} next=${d.nextRetryCount}"
                      }
                    }
                  }
                }
                """, Result.FAILURE);
        j.assertLogContains("preview willRetry=true reason=retry next=1", r);
        j.assertLogContains("[Build Retry] using the decision recorded by buildRetryDecision()", r);
        assertNotNull(r.getAction(RecordedDecisionAction.class), "preview recorded");
        assertEquals("queued", r.getAction(DecidedAction.class).getOutcome(), "retry queued");
        assertEquals(2, r.getParent().getBuilds().size(), "retried");
    }

    @Test
    void previewInPostSeesTheErrorLineNotYetPrinted() throws Exception {
        WorkflowRun r = run("""
                pipeline {
                  agent none
                  options {
                    buildRetry(maxSchedule: 1, delay: fixedDelay(0), checkRegexp: true, regexpForRerun: '^ERROR: flaky-net$')
                  }
                  stages { stage('one') { steps { error 'flaky-net' } } }
                  post { always { script { echo "preview regexMatched=${buildRetryDecision().regexMatched}" } } }
                }
                """, Result.FAILURE);
        j.assertLogContains("preview regexMatched=true", r);
    }

    @Test
    void scriptedCatchWithoutAResultIsDecidedAgain() throws Exception {
        WorkflowRun r = run(OPTIONS + """
                        try {
                          error 'boom'
                        } catch (e) {
                          def d = buildRetryDecision()
                          echo "preview result=${d.result} willRetry=${d.willRetry} reason=${d.reason}"
                          throw e
                        }
                        """, Result.FAILURE);
        j.assertLogContains("preview result=SUCCESS willRetry=false reason=success", r);
        j.assertLogContains("[Build Retry] deciding again: recorded at SUCCESS, ended FAILURE", r);
        j.assertLogContains("[Build Retry] FAILURE → retry 1/1 in 0s", r);
        assertEquals(2, r.getParent().getBuilds().size(), "the end decision retried");
    }

    @Test
    void resultChangedAfterThePreview() throws Exception {
        WorkflowRun r = run(OPTIONS + """
                        unstable 'flaky'
                        def d = buildRetryDecision()
                        echo "preview result=${d.result} reason=${d.reason}"
                        error 'boom'
                        """, Result.FAILURE);
        j.assertLogContains("preview result=UNSTABLE reason=unstable-not-enabled", r);
        j.assertLogContains("deciding again: recorded at UNSTABLE, ended FAILURE", r);
        assertEquals(2, r.getParent().getBuilds().size(), "retried");
    }

    @Test
    void pointOfNoReturnAfterThePreviewOverridesIt() throws Exception {
        WorkflowRun r = run(OPTIONS + """
                        catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') { error 'boom' }
                        def d = buildRetryDecision()
                        echo "preview willRetry=${d.willRetry}"
                        buildRetryPointOfNoReturn 'deployed'
                        """, Result.FAILURE);
        j.assertLogContains("preview willRetry=true", r);
        j.assertLogContains("[Build Retry] point of no return: deployed; this build will not be retried", r);
        j.assertLogContains("deciding again: recorded at FAILURE, ended FAILURE after the point of no return", r);
        j.assertLogContains("no retry (point-of-no-return: deployed)", r);
        assertEquals(1, r.getParent().getBuilds().size(), "not retried");
    }

    @Test
    void pointOfNoReturnStopsARetry() throws Exception {
        WorkflowRun r = run(OPTIONS + "buildRetryPointOfNoReturn 'published'\nerror 'boom'", Result.FAILURE);
        assertEquals("published", r.getAction(PointOfNoReturnAction.class).getReason(), "marker");
        j.assertLogContains("no retry (point-of-no-return: published)", r);
        assertEquals(1, r.getParent().getBuilds().size(), "not retried");
    }

    @Test
    void firstPointOfNoReturnWins() throws Exception {
        WorkflowRun r = run(
                OPTIONS + "buildRetryPointOfNoReturn 'first'\nbuildRetryPointOfNoReturn 'second'\nerror 'boom'",
                Result.FAILURE);
        j.assertLogContains("no retry (point-of-no-return: first)", r);
        assertEquals(1, r.getActions(PointOfNoReturnAction.class).size(), "one marker");
    }

    @Test
    void anotherArmedRetryOverridesThePreview() throws Exception {
        ScriptApproval.get().preapproveAll();
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(OPTIONS + """
                        catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') { error 'boom' }
                        echo "preview willRetry=${buildRetryDecision().willRetry}"
                        currentBuild.rawBuild.addAction(new io.jenkins.plugins.pipelinebuildretry.RetryScheduledAction('other'))
                        """, false));
        WorkflowRun r = j.buildAndAssertStatus(Result.FAILURE, p);
        j.waitUntilNoActivity();
        j.assertLogContains("preview willRetry=true", r);
        j.assertLogContains(", retry already scheduled", r);
        j.assertLogContains("no retry (already-scheduled)", r);
        assertEquals(1, p.getBuilds().size(), "no second retry");
    }

    @Test
    void theEndUsesThePreviewsOptions() throws Exception {
        WorkflowRun r = run(OPTIONS + """
                        unstable 'flaky'
                        buildRetryDecision()
                        properties([buildRetry(maxSchedule: 1, delay: fixedDelay(0), dryRun: true)])
                        error 'boom'
                        """, Result.FAILURE);
        j.assertLogContains("deciding again: recorded at UNSTABLE, ended FAILURE", r);
        j.assertLogContains("FAILURE → retry 1/1 in 0s", r);
        assertEquals(2, r.getParent().getBuilds().size(), "the recorded options, not the later dry run");
    }

    @Test
    void notConfiguredRecordsNothing() throws Exception {
        WorkflowRun r = run("""
                def d = buildRetryDecision()
                echo "preview willRetry=${d.willRetry} reason=${d.reason}"
                """, Result.SUCCESS);
        j.assertLogContains("preview willRetry=false reason=not-configured", r);
        assertNull(r.getAction(RecordedDecisionAction.class), "nothing recorded");
        assertNull(r.getAction(DecidedAction.class), "no end decision");
    }

    @Test
    void switchedOffControllerReturnsDisabledAndRecordsNothing() throws Exception {
        BuildRetryGlobalConfiguration.get().setEnabled(false);
        WorkflowRun r = run(OPTIONS + """
                def d = buildRetryDecision()
                echo "preview willRetry=${d.willRetry} reason=${d.reason}"
                """, Result.SUCCESS);
        j.assertLogContains("preview willRetry=false reason=disabled", r);
        assertNull(r.getAction(RecordedDecisionAction.class), "nothing recorded");
        assertNull(r.getAction(DecidedAction.class), "no end decision");
    }
}
