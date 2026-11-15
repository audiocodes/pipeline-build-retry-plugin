package io.jenkins.plugins.pipelinebuildretry;

import hudson.model.Action;
import hudson.model.BuildBadgeAction;
import hudson.model.Queue;
import java.util.List;

/** A build's place in its retry chain and its badge; this plugin adds it to every build it queues. */
public class BuildRetryAction implements BuildBadgeAction, Queue.QueueAction {

    private final int retryCount;
    private final int maxRetryCount;
    private final int parentBuildNumber;
    private final boolean badge;
    // 0 in actions saved before this field existed.
    private final int rootBuildNumber;
    // Null in actions saved before these fields existed.
    private final String parentResult;
    private final String parentAbortKind;

    public BuildRetryAction(int retryCount, int maxRetryCount, int parentBuildNumber) {
        this(retryCount, maxRetryCount, parentBuildNumber, true);
    }

    public BuildRetryAction(int retryCount, int maxRetryCount, int parentBuildNumber, boolean badge) {
        this(retryCount, maxRetryCount, parentBuildNumber, badge, parentBuildNumber);
    }

    public BuildRetryAction(
            int retryCount, int maxRetryCount, int parentBuildNumber, boolean badge, int rootBuildNumber) {
        this(retryCount, maxRetryCount, parentBuildNumber, badge, rootBuildNumber, null, null);
    }

    public BuildRetryAction(
            int retryCount,
            int maxRetryCount,
            int parentBuildNumber,
            boolean badge,
            int rootBuildNumber,
            String parentResult,
            String parentAbortKind) {
        this.retryCount = retryCount;
        this.maxRetryCount = maxRetryCount;
        this.parentBuildNumber = parentBuildNumber;
        this.badge = badge;
        this.rootBuildNumber = rootBuildNumber;
        this.parentResult = parentResult;
        this.parentAbortKind = parentAbortKind;
    }

    /** 1 for the first retry. */
    public int getRetryCount() {
        return retryCount;
    }

    /** The job's maxSchedule when this retry was queued; 0 or less means unlimited. */
    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    /** The number of the build whose failure queued this one. */
    public int getParentBuildNumber() {
        return parentBuildNumber;
    }

    /** The first build of this retry chain; the parent for an action saved by an older plugin version. */
    public int getRootBuildNumber() {
        return rootBuildNumber > 0 ? rootBuildNumber : parentBuildNumber;
    }

    /** True when the build history shows the retry badge. */
    public boolean isBadge() {
        return badge;
    }

    /** The badge's tooltip, such as "Build retry 2/3 after build #41 failed". */
    public String getTooltip() {
        return BuildRetryCause.describe(retryCount, maxRetryCount, parentBuildNumber, parentResult, parentAbortKind);
    }

    // Each retry is its own queue item: never folded into another item of the same job.
    @Override
    public boolean shouldSchedule(List<Action> actions) {
        return true;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return null;
    }
}
