package org.jenkinsci.plugins.workflow.support.steps.build;

import jenkins.model.CauseOfInterruption;

/** Test stand-in with the pipeline-build-step class name; the plugin reads causes by name. */
public final class BuildTriggerCancelledCause extends CauseOfInterruption {

    private static final long serialVersionUID = 1L;

    @Override
    public String getShortDescription() {
        return "Calling build was cancelled.";
    }
}
