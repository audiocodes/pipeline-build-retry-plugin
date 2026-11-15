package io.jenkins.plugins.pipelinebuildretry.evidence;

import hudson.console.AnnotatedLargeText;
import io.jenkins.plugins.pipelinebuildretry.core.FailureFacts;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.actions.ArgumentsAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** The failing step of a build and the tail of its own log, for the error fingerprint. */
@Restricted(NoExternalUse.class)
public final class FailureLine {

    static final int TAIL_BYTES = 64 * 1024;
    static final int TAIL_LINES = 200;
    private static final Logger LOGGER = Logger.getLogger(FailureLine.class.getName());

    private FailureLine() {}

    /** The step at {@code nodeId}, or null when there is none; a log that cannot be read gives an empty tail. */
    public static FailureFacts.Step of(WorkflowRun run, String nodeId) {
        FlowExecution execution = nodeId == null ? null : run.getExecution();
        if (execution == null) {
            return null;
        }
        FlowNode node;
        String function;
        String arguments;
        try {
            node = execution.getNode(nodeId);
            function = node == null ? null : FlowEvidence.functionName(node);
            arguments = function == null ? null : ArgumentsAction.getStepArgumentsAsString(node);
        } catch (IOException | RuntimeException | LinkageError e) {
            LOGGER.log(Level.FINE, "Could not read the failing step of " + run, e);
            return null;
        }
        if (function == null) {
            return null;
        }
        List<String> tail = List.of();
        LogAction log = node.getPersistentAction(LogAction.class);
        if (log != null) {
            try {
                tail = tail(log.getLogText());
                LOGGER.fine(() -> "Read " + TAIL_BYTES / 1024 + " KiB at most of step " + nodeId + " in " + run);
            } catch (IOException | RuntimeException | LinkageError e) {
                LOGGER.log(Level.FINE, "Could not read the log of step " + nodeId + " in " + run, e);
            }
        }
        return new FailureFacts.Step(function, arguments, tail);
    }

    /** The last {@link #TAIL_LINES} lines of the last {@link #TAIL_BYTES} bytes, console notes removed. */
    static List<String> tail(AnnotatedLargeText<?> text) throws IOException {
        long start = Math.max(0L, text.length() - TAIL_BYTES);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long end = text.writeLogTo(start, out);
        String all = out.toString(StandardCharsets.UTF_8);
        if (end <= start || all.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(Arrays.asList(all.split("\r?\n|\r")));
        if (start > 0 && !lines.isEmpty()) {
            lines.remove(0);
        }
        return List.copyOf(lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size()));
    }
}
