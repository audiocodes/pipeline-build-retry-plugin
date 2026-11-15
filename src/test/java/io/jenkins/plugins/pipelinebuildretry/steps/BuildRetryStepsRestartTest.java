package io.jenkins.plugins.pipelinebuildretry.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.jenkins.plugins.pipelinebuildretry.DecidedAction;
import io.jenkins.plugins.pipelinebuildretry.RecordedDecisionAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class BuildRetryStepsRestartTest {

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void previewSurvivesARestartBeforeTheEnd() throws Throwable {
        sessions.then(j -> {
            WorkflowJob p = j.createProject(WorkflowJob.class, "preview");
            p.setDefinition(new CpsFlowDefinition("""
                    properties([buildRetry(maxSchedule: 1, delay: fixedDelay(0))])
                    if (env.BUILD_RETRY_COUNT == null) {
                      catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') { error 'boom' }
                      def d = buildRetryDecision()
                      echo "preview willRetry=${d.willRetry}"
                      sleep 20
                    }
                    """, true));
            WorkflowRun r = p.scheduleBuild2(0).waitForStart();
            j.waitForMessage("preview willRetry=true", r);
            assertNotNull(r.getAction(RecordedDecisionAction.class), "recorded before the restart");
        });
        sessions.then(j -> {
            WorkflowJob p = j.jenkins.getItemByFullName("preview", WorkflowJob.class);
            WorkflowRun r = p.getBuildByNumber(1);
            j.waitForCompletion(r);
            j.waitUntilNoActivity();
            j.assertLogContains("[Build Retry] using the decision recorded by buildRetryDecision()", r);
            assertEquals("queued", r.getAction(DecidedAction.class).getOutcome(), "retry queued after the restart");
            assertNotNull(p.getBuildByNumber(2), "the retry ran");
        });
    }
}
