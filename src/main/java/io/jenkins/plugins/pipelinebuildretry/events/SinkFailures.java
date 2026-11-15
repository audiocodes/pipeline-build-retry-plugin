package io.jenkins.plugins.pipelinebuildretry.events;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Logs the first failure of each sink class as a warning and later ones at FINE. */
final class SinkFailures {

    private static final Logger LOGGER = Logger.getLogger(RetryEvents.class.getName());
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private SinkFailures() {}

    static void failed(RetryEvents sink, Throwable error) {
        String name = sink.getClass().getName();
        Level level = WARNED.add(name) ? Level.WARNING : Level.FINE;
        LOGGER.log(level, "Retry events: sink " + name + " failed, events from it are lost: " + error);
    }
}
