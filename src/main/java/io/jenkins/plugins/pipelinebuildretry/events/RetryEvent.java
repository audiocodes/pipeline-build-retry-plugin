package io.jenkins.plugins.pipelinebuildretry.events;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * One monitoring event: a stable name, a readable line and plain-value attributes.
 *
 * <p>Attribute values are {@code String}, {@code Long}, {@code Boolean} or {@code List<String>}; null values are
 * left out.
 */
@Restricted(NoExternalUse.class)
public final class RetryEvent {

    /** How loud the event is. */
    public enum Severity {
        INFO,
        WARN
    }

    private final String name;
    private final String body;
    private Severity severity = Severity.INFO;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    public RetryEvent(String name, String body) {
        this.name = name;
        this.body = body;
    }

    public String getName() {
        return name;
    }

    public String getBody() {
        return body;
    }

    public Severity getSeverity() {
        return severity;
    }

    public RetryEvent severity(Severity v) {
        this.severity = v;
        return this;
    }

    /** The attributes in the order they were added. */
    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public RetryEvent put(String key, String value) {
        if (value != null) {
            attributes.put(key, value);
        }
        return this;
    }

    public RetryEvent put(String key, long value) {
        attributes.put(key, value);
        return this;
    }

    public RetryEvent put(String key, Integer value) {
        if (value != null) {
            attributes.put(key, value.longValue());
        }
        return this;
    }

    public RetryEvent put(String key, boolean value) {
        attributes.put(key, value);
        return this;
    }

    public RetryEvent put(String key, List<String> value) {
        if (value != null && !value.isEmpty()) {
            attributes.put(key, List.copyOf(value));
        }
        return this;
    }

    @Override
    public String toString() {
        return name + " " + attributes;
    }
}
