package io.jenkins.plugins.pipelinebuildretry.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class OtelRetryEventsTest {

    private final List<LogRecordData> records = Collections.synchronizedList(new ArrayList<>());

    private LogRecordData send(RetryEvent e) {
        try (SdkLoggerProvider provider = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(new Collect()))
                .build()) {
            OtelRetryEvents.emit(provider.get(OtelRetryEvents.SCOPE), e);
        }
        assertEquals(1, records.size(), "one record");
        return records.get(0);
    }

    // LogRecordData.getEventName exists from OpenTelemetry 1.50, like the builder's setter.
    private static String eventName(LogRecordData r) {
        try {
            return (String) LogRecordData.class.getMethod("getEventName").invoke(r);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static boolean apiHasEventName() {
        return Arrays.stream(LogRecordBuilder.class.getMethods())
                .anyMatch(m -> m.getName().equals("setEventName"));
    }

    @Test
    void anEventBecomesANamedLogRecord() {
        LogRecordData r = send(new RetryEvent("build_retry.decision", "job #1: FAILURE → no retry (max-reached)")
                .put("ci.pipeline.id", "folder/job")
                .put("ci.pipeline.run.number", 1L)
                .put("build_retry.dry_run", true)
                .put("build_retry.failed_stages", List.of("Build", "Test")));
        assertEquals(apiHasEventName() ? "build_retry.decision" : null, eventName(r), "OTel event name");
        assertEquals("build_retry.decision", r.getAttributes().get(AttributeKey.stringKey("event_name")), "Loki copy");
        assertEquals("job #1: FAILURE → no retry (max-reached)", r.getBody().asString(), "body");
        assertEquals(Severity.INFO, r.getSeverity(), "severity");
        assertEquals("folder/job", r.getAttributes().get(AttributeKey.stringKey("ci.pipeline.id")), "string");
        assertEquals(1L, r.getAttributes().get(AttributeKey.longKey("ci.pipeline.run.number")), "number");
        assertEquals(true, r.getAttributes().get(AttributeKey.booleanKey("build_retry.dry_run")), "flag");
        assertEquals(
                "Build, Test",
                r.getAttributes().get(AttributeKey.stringKey("build_retry.failed_stages")),
                "list joined");
        assertEquals(
                "io.jenkins.plugins.pipelinebuildretry",
                r.getInstrumentationScopeInfo().getName(),
                "scope");
    }

    @Test
    @EnabledIfSystemProperty(named = "buildRetry.otelLatest", matches = "true")
    void theLatestApiHasTheEventName() {
        assertTrue(apiHasEventName(), "the otel-latest profile resolved an API without setEventName");
    }

    @Test
    void warningsLongTextAndLongListsAreBounded() {
        List<String> many = IntStream.range(0, 25).mapToObj(i -> "s" + i).toList();
        LogRecordData r = send(new RetryEvent("build_retry.decision", "x".repeat(3000))
                .severity(RetryEvent.Severity.WARN)
                .put("build_retry.reason_detail", "y".repeat(3000))
                .put("build_retry.failed_stages", many));
        assertEquals(Severity.WARN, r.getSeverity(), "warn");
        assertEquals(OtelRetryEvents.MAX_STRING, r.getBody().asString().length(), "body cut");
        assertEquals(
                OtelRetryEvents.MAX_STRING,
                r.getAttributes()
                        .get(AttributeKey.stringKey("build_retry.reason_detail"))
                        .length(),
                "attribute cut");
        String stages = r.getAttributes().get(AttributeKey.stringKey("build_retry.failed_stages"));
        assertTrue(stages.startsWith("s0, s1,") && stages.endsWith("s19, … 5 more"), stages);
    }

    @Test
    void theResourceCarriesTheConfiguredAttributes() {
        Resource r = OtelRetryEvents.resource(new OtelRetryEvents.Target(
                "http://c:4318/v1/logs",
                null,
                "controller-a",
                "https://controller-a/",
                Map.of("deployment.environment.name", "prod", "service.namespace", "ci", "service.name", "other")));
        assertEquals("controller-a", r.getAttribute(AttributeKey.stringKey("service.name")), "its own field wins");
        assertEquals("ci", r.getAttribute(AttributeKey.stringKey("service.namespace")), "a default can be replaced");
        assertEquals("prod", r.getAttribute(AttributeKey.stringKey("deployment.environment.name")), "added");
        assertEquals("https://controller-a/", r.getAttribute(AttributeKey.stringKey("jenkins.url")), "kept");
    }

    @Test
    void exportFailuresBecomeWarningsAndOtherFiltersStillRun() {
        List<String> seen = new ArrayList<>();
        OtelRetryEvents.ExportFailureFilter f = new OtelRetryEvents.ExportFailureFilter(r -> {
            seen.add(r.getLevel() + " " + r.getMessage());
            return !r.getMessage().startsWith("drop");
        });
        LogRecord severe = new LogRecord(Level.SEVERE, "Failed to export logs. The request could not be executed.");
        severe.setThrown(new java.net.ConnectException("refused"));
        assertTrue(f.isLoggable(severe), "kept");
        assertEquals(Level.WARNING, severe.getLevel(), "lowered");
        assertEquals(null, severe.getThrown(), "no stack trace");
        LogRecord status =
                new LogRecord(Level.WARNING, "Failed to export logs. Server responded with HTTP status code 401.");
        status.setThrown(new IllegalStateException("kept"));
        f.isLoggable(status);
        assertEquals("kept", status.getThrown().getMessage(), "a warning is left as it is");
        assertTrue(!f.isLoggable(new LogRecord(Level.INFO, "drop me")), "the earlier filter still decides");
        assertEquals(3, seen.size(), "the earlier filter saw every record: " + seen);
    }

    private final class Collect implements LogRecordExporter {
        @Override
        public CompletableResultCode export(Collection<LogRecordData> logs) {
            records.addAll(logs);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
