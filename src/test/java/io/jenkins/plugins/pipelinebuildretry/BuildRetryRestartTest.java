package io.jenkins.plugins.pipelinebuildretry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Queue;
import java.nio.file.Files;
import java.util.List;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class BuildRetryRestartTest {

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void retryWaitingInTheQueueSurvivesARestart() throws Throwable {
        sessions.then(j -> {
            WorkflowJob p = j.createProject(WorkflowJob.class, "chain");
            p.setDefinition(new CpsFlowDefinition("""
                    properties([buildRetry(maxSchedule: 1, delay: fixedDelay(20))])
                    echo "count=${env.BUILD_RETRY_COUNT}"
                    if (env.BUILD_RETRY_COUNT == null) { error 'boom' }
                    """, true));
            j.waitForCompletion(p.scheduleBuild2(0).waitForStart());
            List<Queue.Item> items = j.jenkins.getQueue().getItems(p);
            assertEquals(1, items.size(), "retry waiting");
            String xml = Files.readString(j.jenkins.getRootDir().toPath().resolve("queue.xml"));
            assertTrue(xml.contains("BuildRetryAction"), "the queue was saved at once");
        });
        sessions.then(j -> {
            WorkflowJob p = j.jenkins.getItemByFullName("chain", WorkflowJob.class);
            assertNotNull(p, "job loaded");
            j.waitUntilNoActivityUpTo(90_000);
            WorkflowRun retry = p.getBuildByNumber(2);
            assertNotNull(retry, "the retry ran after the restart");
            j.assertBuildStatusSuccess(retry);
            j.assertLogContains("count=1", retry);
            assertEquals(
                    "Build retry 1/1 after build #1 failed",
                    retry.getCause(BuildRetryCause.class).getShortDescription(),
                    "cause loaded from build.xml");
            assertEquals(
                    "queued",
                    p.getBuildByNumber(1).getAction(DecidedAction.class).getOutcome(),
                    "decided");
        });
    }
}
