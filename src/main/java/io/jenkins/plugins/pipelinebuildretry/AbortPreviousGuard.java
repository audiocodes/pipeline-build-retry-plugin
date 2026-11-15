package io.jenkins.plugins.pipelinebuildretry;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Queue;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.pipelinebuildretry.core.RetryDecision;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Under abortPrevious, a starting build cancels the job's queued retries of older builds. */
@Extension
@Restricted(NoExternalUse.class)
public class AbortPreviousGuard extends RunListener<WorkflowRun> {

    @Override
    public void onStarted(WorkflowRun run, @NonNull TaskListener listener) {
        WorkflowJob job = run.getParent();
        if (!BuildRetryListener.abortPrevious(job)) {
            return;
        }
        Queue queue = Jenkins.get().getQueue();
        for (Queue.Item item : queue.getItems(job)) {
            BuildRetryAction a = item.getAction(BuildRetryAction.class);
            if (a != null && a.getParentBuildNumber() < run.getNumber() && CancelledRetryListener.cancel(queue, item)) {
                listener.getLogger()
                        .println(RetryDecision.LOG_PREFIX + "pending retry " + a.getRetryCount() + " of build #"
                                + a.getParentBuildNumber() + " cancelled: this build supersedes it (abortPrevious)");
                BuildRetryEvents.retryCancelled(run, item, a);
            }
        }
    }
}
