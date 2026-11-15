package io.jenkins.plugins.pipelinebuildretry.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.ExtensionList;
import hudson.PluginWrapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class KubernetesTerminationReasonsTest {

    @Test
    void linksAgainstTheOldestSupportedKubernetesPlugin(JenkinsRule j) {
        PluginWrapper kubernetes = j.jenkins.getPluginManager().getPlugin("kubernetes");
        assertNotNull(kubernetes, "kubernetes is on the test classpath");
        assertEquals("4540.v612369217f87", kubernetes.getVersion(), "tests run against the floor");
        KubernetesTerminationReasons source = ExtensionList.lookupSingleton(KubernetesTerminationReasons.class);
        assertEquals(List.of(), List.copyOf(source.reasons("no-such-agent")), "the Reaper call links and runs");
    }
}
