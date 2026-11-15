package io.jenkins.plugins.pipelinebuildretry;

import hudson.model.InvisibleAction;

/** Marks a build whose retry is already armed or queued, by this plugin or anything else that honours it. */
public class RetryScheduledAction extends InvisibleAction {

    private final String armedBy;

    public RetryScheduledAction(String armedBy) {
        this.armedBy = armedBy;
    }

    /** Who armed the retry, such as pipeline-build-retry. */
    public String getArmedBy() {
        return armedBy;
    }
}
