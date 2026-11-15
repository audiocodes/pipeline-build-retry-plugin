package io.jenkins.plugins.pipelinebuildretry.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpServer;
import hudson.ExtensionList;
import hudson.PluginWrapper;
import hudson.util.Secret;
import io.jenkins.plugins.pipelinebuildretry.BuildRetryGlobalConfiguration;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class OtelRetryEventsSenderTest {

    private JenkinsRule j;
    private HttpServer collector;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.createContext("/", x -> {
            byte[] body = x.getRequestBody().readAllBytes();
            requests.add(x.getRequestMethod() + " " + x.getRequestURI().getPath() + " "
                    + x.getRequestHeaders().getFirst("Authorization") + " "
                    + x.getRequestHeaders().getFirst("Content-Type") + " " + (body.length > 0));
            x.sendResponseHeaders(200, -1);
            x.close();
        });
        collector.start();
    }

    @AfterEach
    void tearDown() {
        collector.stop(0);
    }

    private static OtelRetryEvents sink() {
        return ExtensionList.lookupSingleton(OtelRetryEvents.class);
    }

    private void failingJob() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(
                "properties([buildRetry(maxSchedule: 1, delay: fixedDelay(0))])\nerror 'boom'", true));
        j.waitForCompletion(p.scheduleBuild2(0).waitForStart());
        j.waitUntilNoActivity();
        assertEquals(2, p.getBuilds().size(), "retried");
    }

    @Test
    void eventsReachTheCollectorWithTheToken() throws Exception {
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.SYSTEM, "events-token", "test", Secret.fromString("s3cret")));
        BuildRetryGlobalConfiguration c = BuildRetryGlobalConfiguration.get();
        c.setEventsEndpoint("http://127.0.0.1:" + collector.getAddress().getPort() + "/");
        c.setEventsCredentialsId("events-token");
        c.setSendEvents(true);
        failingJob();
        assertTrue(sink().isOpen(), "exporter open");
        assertTrue(sink().flush(Duration.ofSeconds(10)), "flushed");
        assertTrue(!requests.isEmpty(), "the collector got a request");
        assertEquals("POST /v1/logs Bearer s3cret application/x-protobuf true", requests.get(0), "one OTLP request");
    }

    @Test
    void changedResourceAttributesRebuildTheExporter() {
        BuildRetryGlobalConfiguration c = BuildRetryGlobalConfiguration.get();
        c.setEventsEndpoint("http://127.0.0.1:" + collector.getAddress().getPort());
        sink().emit(new RetryEvent("build_retry.test", "one"));
        assertEquals(Map.of(), sink().target().resourceAttributes(), "none set");
        c.setEventsResourceAttributes("deployment.environment.name=prod");
        sink().emit(new RetryEvent("build_retry.test", "two"));
        assertEquals(
                Map.of("deployment.environment.name", "prod"),
                sink().target().resourceAttributes(),
                "the next event uses the new attributes");
    }

    @Test
    void noEndpointMeansNoExporter() throws Exception {
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        failingJob();
        assertTrue(!sink().isOpen(), "no exporter without an endpoint");
        assertEquals(List.of(), requests, "nothing sent");
    }

    @Test
    void aDeadCollectorDoesNotSlowTheRetry() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logged.add(r);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger exporterLog = Logger.getLogger(OtelRetryEvents.EXPORTER_LOGGER);
        exporterLog.addHandler(capture);
        try {
            BuildRetryGlobalConfiguration c = BuildRetryGlobalConfiguration.get();
            c.setEventsEndpoint("http://127.0.0.1:" + closed);
            c.setSendEvents(true);
            long start = System.nanoTime();
            failingJob();
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms < 30_000, "two builds took " + ms + " ms");
            sink().flush(Duration.ofSeconds(20));
            for (int i = 0; i < 100 && logged.isEmpty(); i++) {
                Thread.sleep(200);
            }
        } finally {
            sink().close();
            exporterLog.removeHandler(capture);
        }
        String seen = logged.stream()
                .map(r -> r.getLevel() + " " + r.getMessage() + " thrown=" + r.getThrown())
                .collect(Collectors.joining("; "));
        assertTrue(
                logged.stream().anyMatch(r -> r.getMessage().startsWith("Failed to export logs")),
                "the SDK reported the failure: " + seen);
        assertTrue(
                logged.stream().allMatch(r -> r.getLevel() == Level.WARNING && r.getThrown() == null),
                "a warning without a stack trace: " + seen);
    }

    // Another test dependency pulls a newer credentials in; what controllers see is the declared floor.
    @Test
    void thePluginDeclaresTheOldestSupportedCredentials() {
        PluginWrapper self = j.jenkins.getPluginManager().getPlugin("pipeline-build-retry");
        String declared = self.getDependencies().stream()
                .filter(d -> d.shortName.equals("credentials"))
                .map(d -> d.version)
                .findFirst()
                .orElse(null);
        assertEquals("1506.v948b_b_b_7dec44", declared, "floor declared to controllers");
    }

    @Test
    void theOpenTelemetryApiPluginStaysOptional() {
        PluginWrapper self = j.jenkins.getPluginManager().getPlugin("pipeline-build-retry");
        assertTrue(
                self.getOptionalDependencies().stream().anyMatch(d -> d.shortName.equals("opentelemetry-api")),
                "declared optional: " + self.getOptionalDependencies());
        assertTrue(
                self.getMandatoryDependencies().stream().noneMatch(d -> d.shortName.equals("opentelemetry-api")),
                "not mandatory: " + self.getMandatoryDependencies());
    }

    @Test
    void theEndpointGetsTheLogsPathOnce() {
        assertEquals("https://c:4318/v1/logs", OtelRetryEvents.logsUrl("https://c:4318"));
        assertEquals("https://c:4318/v1/logs", OtelRetryEvents.logsUrl("https://c:4318/"));
        assertEquals("https://c:4318/v1/logs", OtelRetryEvents.logsUrl("https://c:4318/v1/logs"));
    }
}
