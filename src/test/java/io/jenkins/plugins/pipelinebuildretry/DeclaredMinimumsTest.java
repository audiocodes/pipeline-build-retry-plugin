package io.jenkins.plugins.pipelinebuildretry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.PluginWrapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class DeclaredMinimumsTest {

    // A bom bump that turns this red raises what controllers must run: update the list and say so.
    private static final List<String> MINIMUMS = List.of(
            "credentials:1514.vb_01eb_014e7d4",
            "ionicons-api:94.vcc3065403257",
            "kubernetes:4560.vc25b_83890c34 optional",
            "opentelemetry-api:1.54.1.102.v276c93ee966d optional",
            "plain-credentials:199.v9f8e1f741799",
            "workflow-api:1429.v7a_1b_efb_a_f7c5",
            "workflow-job:1571.1580.v18e46842c125",
            "workflow-step-api:724.v538c2362b_dfb_");

    @Test
    void theDeclaredMinimumsAreTheListedOnes(JenkinsRule j) {
        PluginWrapper self = j.jenkins.getPluginManager().getPlugin("pipeline-build-retry");
        List<String> declared = self.getDependencies().stream()
                .map(d -> d.shortName + ":" + d.version + (d.optional ? " optional" : ""))
                .sorted()
                .toList();
        assertEquals(MINIMUMS, declared, "plugin versions controllers must run");
    }
}
