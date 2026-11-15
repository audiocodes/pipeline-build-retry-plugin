package io.jenkins.plugins.pipelinebuildretry;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Sends the retry-started event when a build this plugin queued starts. */
@Extension
@Restricted(NoExternalUse.class)
public class RetryEventListener extends RunListener<WorkflowRun> {

    @Override
    public void onStarted(WorkflowRun run, @NonNull TaskListener listener) {
        BuildRetryEvents.retryStarted(run);
    }
}
