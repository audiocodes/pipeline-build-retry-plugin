package io.jenkins.plugins.pipelinebuildretry;

import hudson.model.InvisibleAction;

/** Marks a build that passed a point after which it must never be retried, such as a deployment. */
public class PointOfNoReturnAction extends InvisibleAction {

    private final String reason;

    public PointOfNoReturnAction(String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
