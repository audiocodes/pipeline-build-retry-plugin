package io.jenkins.plugins.pipelinebuildretry;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.EnvironmentContributor;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Sets BUILD_RETRY_COUNT, BUILD_RETRY_MAXCOUNT and BUILD_RETRY_BUILD_NUMBER on retried Pipeline builds. */
@Extension
@Restricted(NoExternalUse.class)
public class BuildRetryEnvironmentContributor extends EnvironmentContributor {

    @Override
    public void buildEnvironmentFor(@NonNull Run r, @NonNull EnvVars envs, @NonNull TaskListener listener) {
        BuildRetryAction a = r instanceof WorkflowRun ? r.getAction(BuildRetryAction.class) : null;
        if (a == null) {
            return;
        }
        envs.put("BUILD_RETRY_COUNT", String.valueOf(a.getRetryCount()));
        envs.put("BUILD_RETRY_MAXCOUNT", String.valueOf(a.getMaxRetryCount()));
        envs.put("BUILD_RETRY_BUILD_NUMBER", String.valueOf(a.getParentBuildNumber()));
    }
}
