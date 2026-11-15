package io.jenkins.plugins.pipelinebuildretry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.PluginWrapper;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class OpenTelemetryApiFloorTest {

    // A newer resolved opentelemetry-api would become the floor declared to controllers.
    @Test
    void thePluginDeclaresTheOldestSupportedOpenTelemetryApi(JenkinsRule j) {
        PluginWrapper self = j.jenkins.getPluginManager().getPlugin("pipeline-build-retry");
        String declared = self.getOptionalDependencies().stream()
                .filter(d -> d.shortName.equals("opentelemetry-api"))
                .map(d -> d.version)
                .findFirst()
                .orElse(null);
        assertEquals("1.39.0-11.vd6b_87589988f", declared, "floor declared to controllers");
    }
}
