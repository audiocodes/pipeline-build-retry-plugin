package io.jenkins.plugins.pipelinebuildretry.events;

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Where monitoring events go; the OpenTelemetry sink is installed only with the opentelemetry-api plugin. */
@Restricted(NoExternalUse.class)
public interface RetryEvents extends ExtensionPoint {

    /** Sends one event; never throws on purpose, but callers guard anyway. */
    void emit(RetryEvent event);

    /** Builds and sends the event when sending is on and a sink exists; never throws. */
    static void send(BooleanSupplier enabled, Supplier<RetryEvent> event) {
        try {
            if (!enabled.getAsBoolean()) {
                return;
            }
            List<RetryEvents> sinks = ExtensionList.lookup(RetryEvents.class);
            if (sinks.isEmpty()) {
                return;
            }
            RetryEvent e = event.get();
            if (e == null) {
                return;
            }
            for (RetryEvents sink : sinks) {
                try {
                    sink.emit(e);
                } catch (RuntimeException | LinkageError x) {
                    // one broken sink must not stop the others or the retry
                    SinkFailures.failed(sink, x);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            Logger.getLogger(RetryEvents.class.getName()).log(Level.FINE, "Could not build a retry event", e);
        }
    }
}
