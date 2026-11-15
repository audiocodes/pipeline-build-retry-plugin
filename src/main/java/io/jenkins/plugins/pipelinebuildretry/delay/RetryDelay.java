package io.jenkins.plugins.pipelinebuildretry.delay;

import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import io.jenkins.plugins.pipelinebuildretry.core.DelaySpec;

/** How long a retry waits in the queue before it starts. */
public abstract class RetryDelay extends AbstractDescribableImpl<RetryDelay> implements ExtensionPoint {

    /** The plain delay values the decision computes with. */
    public abstract DelaySpec toSpec();

    /** Descriptor for delay types. */
    public abstract static class RetryDelayDescriptor extends Descriptor<RetryDelay> {}
}
