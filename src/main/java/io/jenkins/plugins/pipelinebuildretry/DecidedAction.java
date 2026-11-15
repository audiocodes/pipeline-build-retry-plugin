package io.jenkins.plugins.pipelinebuildretry;

import hudson.model.InvisibleAction;
import io.jenkins.plugins.pipelinebuildretry.core.Decision;

/** The end decision for a build and what became of it; its presence stops a second decision. */
public class DecidedAction extends InvisibleAction {

    private final Decision decision;
    private final String outcome;
    private final long queueId;

    public DecidedAction(Decision decision, String outcome, long queueId) {
        this.decision = decision;
        this.outcome = outcome;
        this.queueId = queueId;
    }

    public Decision getDecision() {
        return decision;
    }

    /** queued, no-retry, dry-run, superseded, not-buildable, not-queued, cancelled or error. */
    public String getOutcome() {
        return outcome;
    }

    /** The queue item id, or 0 when nothing was queued. */
    public long getQueueId() {
        return queueId;
    }
}
