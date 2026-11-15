package io.jenkins.plugins.pipelinebuildretry.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

// These classes load without the opentelemetry-api plugin, so no OpenTelemetry type may appear in them.
class NoOpenTelemetryTypesTest {

    private static final List<String> LOADED_WITHOUT_IT = List.of(
            "io.jenkins.plugins.pipelinebuildretry.events.RetryEvent",
            "io.jenkins.plugins.pipelinebuildretry.events.RetryEvents",
            "io.jenkins.plugins.pipelinebuildretry.events.SinkFailures",
            "io.jenkins.plugins.pipelinebuildretry.BuildRetryEvents",
            "io.jenkins.plugins.pipelinebuildretry.BuildRetryGlobalConfiguration");

    @Test
    void classesThatLoadWithoutTheApiPluginNameNoOpenTelemetryType() throws Exception {
        List<String> found = new ArrayList<>();
        for (String name : LOADED_WITHOUT_IT) {
            for (Class<?> c = Class.forName(name); c != null && c != Object.class; c = c.getSuperclass()) {
                check(found, c + " extends", c.getSuperclass());
                for (Class<?> i : c.getInterfaces()) {
                    check(found, c + " implements", i);
                }
                for (Field f : c.getDeclaredFields()) {
                    check(found, c + "." + f.getName(), f.getType());
                }
                for (Method m : c.getDeclaredMethods()) {
                    check(found, c + "." + m.getName() + "()", m.getReturnType());
                    for (Class<?> p : m.getParameterTypes()) {
                        check(found, c + "." + m.getName() + "(…)", p);
                    }
                }
                for (Class<?> inner : c.getDeclaredClasses()) {
                    check(found, c + " declares", inner);
                }
            }
        }
        assertEquals(List.of(), found, "OpenTelemetry types outside events/OtelRetryEvents");
    }

    private static void check(List<String> found, String where, Class<?> type) {
        while (type != null && type.isArray()) {
            type = type.getComponentType();
        }
        if (type != null && type.getName().startsWith("io.opentelemetry.")) {
            found.add(where + ": " + type.getName());
        }
    }
}
