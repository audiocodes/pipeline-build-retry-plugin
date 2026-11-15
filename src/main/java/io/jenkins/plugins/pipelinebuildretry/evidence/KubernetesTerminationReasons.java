package io.jenkins.plugins.pipelinebuildretry.evidence;

import hudson.Extension;
import java.util.Collection;
import org.csanchez.jenkins.plugins.kubernetes.pod.retention.Reaper;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Pod termination reasons from the kubernetes plugin; loaded only when that plugin is installed. */
@Extension(optional = true)
@Restricted(NoExternalUse.class)
public class KubernetesTerminationReasons implements TerminationReasons {

    @Override
    public Collection<String> reasons(String agent) {
        return Reaper.getInstance().terminationReasons(agent);
    }
}
