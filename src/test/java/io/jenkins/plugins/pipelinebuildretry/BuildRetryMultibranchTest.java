package io.jenkins.plugins.pipelinebuildretry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.model.Result;
import java.nio.charset.StandardCharsets;
import jenkins.branch.BranchSource;
import jenkins.scm.impl.mock.MockSCMController;
import jenkins.scm.impl.mock.MockSCMDiscoverBranches;
import jenkins.scm.impl.mock.MockSCMSource;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BuildRetryMultibranchTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void branchJobRetriesLikeAPlainJob() throws Exception {
        String jenkinsfile = "properties([buildRetry(maxSchedule: 1, delay: fixedDelay(0))])\n"
                + "echo \"retry-env count=${env.BUILD_RETRY_COUNT} parent=${env.BUILD_RETRY_BUILD_NUMBER}\"\n"
                + "error 'boom'\n";
        try (MockSCMController c = MockSCMController.create()) {
            c.createRepository("repo");
            c.addFile("repo", "master", "add Jenkinsfile", "Jenkinsfile", jenkinsfile.getBytes(StandardCharsets.UTF_8));
            WorkflowMultiBranchProject mp = j.jenkins.createProject(WorkflowMultiBranchProject.class, "mp");
            mp.getSourcesList().add(new BranchSource(new MockSCMSource(c, "repo", new MockSCMDiscoverBranches())));
            mp.scheduleBuild2(0).getFuture().get();
            j.waitUntilNoActivity();

            WorkflowJob branch = mp.getItem("master");
            assertNotNull(branch, "branch job indexed");
            assertEquals(2, branch.getBuilds().size(), "original and one retry");
            WorkflowRun first = branch.getBuildByNumber(1);
            j.assertBuildStatus(Result.FAILURE, first);
            j.assertLogContains("[Build Retry] FAILURE → retry 1/1 in 0s", first);
            j.assertLogContains("[Build Retry] retry 1/1 queued (item ", first);

            WorkflowRun second = branch.getBuildByNumber(2);
            j.assertLogContains("retry-env count=1 parent=1", second);
            j.assertLogContains("[Build Retry] FAILURE → no retry (max-reached)", second);
            assertEquals(
                    "Build retry 1/1 after build #1 failed",
                    second.getCause(BuildRetryCause.class).getShortDescription(),
                    "retry cause on the branch job");
        }
    }
}
