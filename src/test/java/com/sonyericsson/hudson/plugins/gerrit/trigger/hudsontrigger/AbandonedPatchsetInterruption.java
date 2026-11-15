package com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger;

import jenkins.model.CauseOfInterruption;

/** Test stand-in with the Gerrit Trigger class name; the plugin reads causes by name. */
public final class AbandonedPatchsetInterruption extends CauseOfInterruption {

    private static final long serialVersionUID = 1L;

    @Override
    public String getShortDescription() {
        return "Aborted by abandoned patch set.";
    }
}
