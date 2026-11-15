package io.jenkins.plugins.pipelinebuildretry.events;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.listeners.ItemListener;
import io.jenkins.plugins.pipelinebuildretry.BuildRetryGlobalConfiguration;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporterBuilder;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.resources.Resource;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Filter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Sends events as OpenTelemetry log records over OTLP/HTTP to the endpoint in the global configuration.
 *
 * <p>Loaded only when the opentelemetry-api plugin is installed, whose SDK and exporter it uses. It owns one small
 * logs-only exporter with a bounded queue that drops when full, so a slow or absent collector costs only events.
 */
@Extension(optional = true)
@Restricted(NoExternalUse.class)
public class OtelRetryEvents implements RetryEvents {

    static final String SCOPE = "io.jenkins.plugins.pipelinebuildretry";
    // Loki 3.7 and older drop the record's event name, so it is also sent as a plain attribute.
    static final String EVENT_NAME_ATTRIBUTE = "event_name";
    static final int MAX_STRING = 1024;
    static final int MAX_LIST = 20;
    static final int MAX_QUEUE = 1024;
    static final int MAX_BATCH = 128;
    static final Duration SCHEDULE_DELAY = Duration.ofSeconds(5);
    static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // The SDK's JVM-wide exporter logger; a strong reference keeps its filter from being collected.
    static final String EXPORTER_LOGGER = "io.opentelemetry.exporter.internal.http.HttpExporter";
    private static final java.util.logging.Logger EXPORTER_LOG = java.util.logging.Logger.getLogger(EXPORTER_LOGGER);
    private static final AtomicBoolean QUIET = new AtomicBoolean();
    // LogRecordBuilder.setEventName exists from OpenTelemetry 1.50; older API plugins send only the attribute.
    static final MethodHandle SET_EVENT_NAME = setEventNameMethod();

    /** What the exporter was built from; a change rebuilds it. */
    // Held in memory only, never serialized; the token comes from a Secret text credential.
    @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    record Target(
            String endpoint,
            String token,
            String serviceName,
            String jenkinsUrl,
            Map<String, String> resourceAttributes) {}

    private Target target;
    private SdkLoggerProvider provider;
    private Logger logger;

    @Override
    public void emit(RetryEvent event) {
        Logger l = logger();
        if (l != null) {
            emit(l, event);
        }
    }

    private synchronized Logger logger() {
        BuildRetryGlobalConfiguration c = BuildRetryGlobalConfiguration.get();
        String endpoint = c.getEventsEndpoint();
        if (endpoint == null) {
            close();
            return null;
        }
        Target t = new Target(
                logsUrl(endpoint),
                c.eventsToken(),
                c.getEventsServiceName(),
                Jenkins.get().getRootUrl(),
                c.eventsResourceAttributes());
        if (!t.equals(target)) {
            close();
            provider = provider(t);
            logger = provider.get(SCOPE);
            target = t;
        }
        return logger;
    }

    static SdkLoggerProvider provider(Target t) {
        quietExportFailures();
        OtlpHttpLogRecordExporterBuilder exporter = OtlpHttpLogRecordExporter.builder()
                .setEndpoint(t.endpoint())
                .setTimeout(EXPORT_TIMEOUT)
                .setConnectTimeout(CONNECT_TIMEOUT);
        if (t.token() != null) {
            exporter.addHeader("Authorization", "Bearer " + t.token());
        }
        return SdkLoggerProvider.builder()
                .setResource(resource(t))
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(exporter.build())
                        .setMaxQueueSize(MAX_QUEUE)
                        .setMaxExportBatchSize(MAX_BATCH)
                        .setScheduleDelay(SCHEDULE_DELAY)
                        .setExporterTimeout(EXPORT_TIMEOUT)
                        .build())
                .build();
    }

    static Resource resource(Target t) {
        AttributesBuilder a = Attributes.builder()
                .put(AttributeKey.stringKey("service.namespace"), "jenkins")
                .put(AttributeKey.stringKey("jenkins.url"), Objects.toString(t.jenkinsUrl(), ""));
        t.resourceAttributes().forEach((k, v) -> a.put(AttributeKey.stringKey(k), truncate(v)));
        a.put(AttributeKey.stringKey("service.name"), t.serviceName());
        return Resource.getDefault().merge(Resource.create(a.build()));
    }

    /** Turns the SDK's SEVERE export failures, with their stack trace, into one-line warnings. */
    static void quietExportFailures() {
        if (QUIET.compareAndSet(false, true)) {
            EXPORTER_LOG.setFilter(new ExportFailureFilter(EXPORTER_LOG.getFilter()));
        }
    }

    record ExportFailureFilter(Filter next) implements Filter {
        @Override
        public boolean isLoggable(LogRecord r) {
            if (r.getLevel() == Level.SEVERE) {
                r.setLevel(Level.WARNING);
                r.setThrown(null);
            }
            return next == null || next.isLoggable(r);
        }
    }

    static String logsUrl(String endpoint) {
        String e = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return e.endsWith("/v1/logs") ? e : e + "/v1/logs";
    }

    /** What the open exporter was built from, or null; for tests. */
    synchronized Target target() {
        return target;
    }

    /** True while an exporter is open. */
    synchronized boolean isOpen() {
        return provider != null;
    }

    /** Sends what is queued, waiting at most the given time; for tests and shutdown. */
    synchronized boolean flush(Duration wait) {
        return provider == null
                || provider.forceFlush()
                        .join(wait.toMillis(), TimeUnit.MILLISECONDS)
                        .isSuccess();
    }

    synchronized void close() {
        if (provider != null) {
            provider.shutdown();
        }
        provider = null;
        logger = null;
        target = null;
    }

    static void emit(Logger logger, RetryEvent event) {
        LogRecordBuilder b = logger.logRecordBuilder()
                .setAttribute(AttributeKey.stringKey(EVENT_NAME_ATTRIBUTE), event.getName())
                .setBody(truncate(event.getBody()));
        setEventName(b, event.getName());
        if (event.getSeverity() == RetryEvent.Severity.WARN) {
            b.setSeverity(Severity.WARN).setSeverityText("WARN");
        } else {
            b.setSeverity(Severity.INFO).setSeverityText("INFO");
        }
        for (Map.Entry<String, Object> a : event.getAttributes().entrySet()) {
            Object v = a.getValue();
            if (v instanceof Long l) {
                b.setAttribute(AttributeKey.longKey(a.getKey()), l);
            } else if (v instanceof Boolean x) {
                b.setAttribute(AttributeKey.booleanKey(a.getKey()), x);
            } else if (v instanceof List<?> list) {
                b.setAttribute(AttributeKey.stringKey(a.getKey()), join(list));
            } else {
                b.setAttribute(AttributeKey.stringKey(a.getKey()), truncate(String.valueOf(v)));
            }
        }
        b.emit();
    }

    private static MethodHandle setEventNameMethod() {
        try {
            return MethodHandles.publicLookup()
                    .findVirtual(
                            LogRecordBuilder.class,
                            "setEventName",
                            MethodType.methodType(LogRecordBuilder.class, String.class));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @SuppressWarnings("checkstyle:IllegalCatch")
    private static void setEventName(LogRecordBuilder b, String name) {
        if (SET_EVENT_NAME != null) {
            try {
                SET_EVENT_NAME.invoke(b, name);
            } catch (Throwable e) {
                // the attribute still carries the name
            }
        }
    }

    // A joined string filters in LogQL with a plain regexp, which Loki's flattened arrays do not.
    static String join(List<?> values) {
        StringBuilder s = new StringBuilder();
        int n = Math.min(values.size(), MAX_LIST);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                s.append(", ");
            }
            s.append(values.get(i));
        }
        if (values.size() > MAX_LIST) {
            s.append(", … ").append(values.size() - MAX_LIST).append(" more");
        }
        return truncate(s.toString());
    }

    static String truncate(String s) {
        return s == null || s.length() <= MAX_STRING ? s : s.substring(0, MAX_STRING - 1) + "…";
    }

    /** Sends what is queued when Jenkins stops. */
    @Extension(optional = true)
    @Restricted(NoExternalUse.class)
    public static class Shutdown extends ItemListener {
        @Override
        public void onBeforeShutdown() {
            for (OtelRetryEvents sink : ExtensionList.lookup(OtelRetryEvents.class)) {
                sink.flush(EXPORT_TIMEOUT);
                sink.close();
            }
        }
    }
}
