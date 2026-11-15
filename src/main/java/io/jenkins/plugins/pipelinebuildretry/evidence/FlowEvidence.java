package io.jenkins.plugins.pipelinebuildretry.evidence;

import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Result;
import io.jenkins.plugins.pipelinebuildretry.core.RetryDecision;
import io.jenkins.plugins.pipelinebuildretry.core.RunSnapshot.ErrorLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.CauseOfInterruption;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.TagsAction;
import org.jenkinsci.plugins.workflow.actions.WarningAction;
import org.jenkinsci.plugins.workflow.actions.WorkspaceAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.MissingContextVariableException;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** What a build's flow graph says about its failure: errors, interruptions, agents and failed stages. */
@Restricted(NoExternalUse.class)
public final class FlowEvidence {

    static final String POST_ACTIONS_STAGE = "Declarative: Post Actions";
    static final String STAGE_STATUS_TAG = "STAGE_STATUS";
    private static final int MAX_CAUSE_CHAIN = 20;
    private static final List<Class<?>> AGENT_CONTEXT_TYPES =
            List.of(FilePath.class, Launcher.class, Computer.class, Node.class);
    private static final Logger LOGGER = Logger.getLogger(FlowEvidence.class.getName());

    private List<String> interruptionCauses = List.of();
    private boolean retryableCauseSeen;
    private boolean agentContextLost;
    private List<String> errorClasses = List.of();
    private List<String> failedStages = List.of();
    private List<String> failedStageIds = List.of();
    private List<ErrorLine> stageErrorLines = List.of();
    private List<String> agentNodes = List.of();
    private List<String> failedAgents = List.of();
    private List<String> failedAgentLabels = List.of();
    private String errorStepId;
    private String error;

    private FlowEvidence() {}

    /** Reads the run's flow graph; on any failure returns empty evidence with the error's class name. */
    public static FlowEvidence of(WorkflowRun run) {
        FlowExecution execution = run.getExecution();
        if (execution == null) {
            return failed("no-flow-graph");
        }
        try {
            return collect(execution);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.WARNING, "Could not read the flow graph of " + run, e);
            return failed(e.getClass().getName());
        }
    }

    private static FlowEvidence failed(String error) {
        FlowEvidence out = new FlowEvidence();
        out.error = error;
        return out;
    }

    private static FlowEvidence collect(FlowExecution execution) {
        FlowEvidence out = new FlowEvidence();
        Set<String> interruptions = new LinkedHashSet<>();
        Set<String> errors = new LinkedHashSet<>();
        Set<String> agents = new LinkedHashSet<>();
        Set<String> failedAgents = new LinkedHashSet<>();
        Set<String> failedAgentLabels = new TreeSet<>();
        List<FlowNode> errorNodes = new ArrayList<>();
        Set<String> blocksWithInnerError = new HashSet<>();
        Map<String, Integer> stages = new LinkedHashMap<>();
        Set<String> stageIds = new LinkedHashSet<>();
        Map<String, ErrorLine> lines = new LinkedHashMap<>();
        for (FlowNode node : new DepthFirstScanner().allNodes(execution)) {
            String agent = agentOf(node);
            if (agent != null) {
                agents.add(agent);
            }
            WarningAction warning = node.getPersistentAction(WarningAction.class);
            if (warning != null && warning.getResult() != Result.SUCCESS) {
                markStage(stages, stageIds, outerStage(node));
            }
            ErrorAction errorAction = node.getError();
            Throwable error = errorAction == null ? null : errorAction.getError();
            if (error == null) {
                continue;
            }
            errorNodes.add(node);
            for (BlockStartNode block : node.getEnclosingBlocks()) {
                blocksWithInnerError.add(block.getId());
            }
            for (Throwable t : chain(error)) {
                errors.add(errorEntry(t));
                // Outside node this is a script bug: the step never had an agent.
                if (t instanceof MissingContextVariableException m
                        && AGENT_CONTEXT_TYPES.contains(m.getType())
                        && enclosingWorkspace(node) != null) {
                    out.agentContextLost = true;
                }
                if (t instanceof FlowInterruptedException fie) {
                    for (CauseOfInterruption cause : fie.getCauses()) {
                        interruptions.add(causeName(cause));
                        out.retryableCauseSeen |= ClassNames.isA(cause, ClassNames.RETRYABLE);
                    }
                }
            }
            if (node instanceof BlockEndNode<?> end && markStage(stages, stageIds, end.getStartNode())) {
                String line = finishLine(error);
                if (line != null && !lines.containsKey(line)) {
                    lines.put(
                            line,
                            new ErrorLine(
                                    end.getStartNode().getId(),
                                    topLevelStage(end.getStartNode()),
                                    order(node),
                                    line,
                                    error instanceof AbortException));
                }
            }
        }
        String errorStageId = lines.values().stream()
                .min(Comparator.comparingInt(ErrorLine::order))
                .map(ErrorLine::stageId)
                .orElse(null);
        FlowNode errorStep = null;
        for (FlowNode failed : errorNodes) {
            // A block end with a failed node inside only rethrows; blame the agent where it started.
            if (failed instanceof BlockEndNode<?> end
                    && blocksWithInnerError.contains(end.getStartNode().getId())) {
                continue;
            }
            if (inSkippedStage(failed)) {
                continue;
            }
            FlowNode step = failed instanceof BlockEndNode<?> end ? end.getStartNode() : failed;
            FlowNode stage = step == null ? null : outerStage(step);
            if (stage != null
                    && stage.getId().equals(errorStageId)
                    && (errorStep == null || order(step) < order(errorStep))) {
                errorStep = step;
            }
            WorkspaceAction ws = enclosingWorkspace(failed);
            if (ws != null) {
                failedAgents.add(ws.getNode());
                // Jenkins leaves the agent's own name out of these.
                ws.getLabels().forEach(l -> failedAgentLabels.add(l.getName()));
            }
        }
        out.interruptionCauses = List.copyOf(interruptions);
        out.errorClasses = List.copyOf(errors);
        out.agentNodes = List.copyOf(agents);
        out.failedAgents = List.copyOf(failedAgents);
        out.failedAgentLabels = List.copyOf(failedAgentLabels);
        out.failedStages = stages.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList();
        out.failedStageIds = List.copyOf(stageIds);
        out.errorStepId = errorStep == null ? null : errorStep.getId();
        List<ErrorLine> sorted = new ArrayList<>(lines.values());
        sorted.sort(Comparator.comparingInt(ErrorLine::order));
        out.stageErrorLines = Collections.unmodifiableList(sorted);
        return out;
    }

    // A repeated stage name is listed once, but every failed start id is kept for the scan.
    private static boolean markStage(Map<String, Integer> stages, Set<String> ids, FlowNode start) {
        String stage = start == null ? null : topLevelStage(start);
        if (stage == null || stage.equals(POST_ACTIONS_STAGE) || skippedStage(start)) {
            return false;
        }
        ids.add(start.getId());
        stages.putIfAbsent(stage, order(start));
        return true;
    }

    // The first line WorkflowRun.finish prints for this error, which no step log holds.
    static String finishLine(Throwable t) {
        if (t instanceof FlowInterruptedException) {
            return null;
        }
        return t instanceof AbortException ? "ERROR: " + t.getMessage() : String.valueOf(t);
    }

    /** The outermost stage around a node, the node itself included; null outside any stage. */
    static FlowNode outerStage(FlowNode node) {
        FlowNode outer = isStageStart(node) ? node : null;
        for (BlockStartNode block : node.getEnclosingBlocks()) {
            if (isStageStart(block)) {
                outer = block;
            }
        }
        return outer;
    }

    private static String topLevelStage(FlowNode start) {
        if (!isStageStart(start)) {
            return null;
        }
        for (BlockStartNode block : start.getEnclosingBlocks()) {
            if (isStageStart(block)) {
                return null;
            }
        }
        LabelAction label = start.getPersistentAction(LabelAction.class);
        return label == null ? null : label.getDisplayName();
    }

    private static boolean isStageStart(FlowNode node) {
        return node instanceof BlockStartNode
                && "stage".equals(functionName(node))
                && node.getPersistentAction(LabelAction.class) != null;
    }

    /** The step's function name, such as sh or stage; null for a node that is not a step. */
    static String functionName(FlowNode node) {
        StepDescriptor d = node instanceof StepNode s ? s.getDescriptor() : null;
        return d == null ? null : d.getFunctionName();
    }

    // Declarative tags stages it skips after a failure; their block ends still carry that error.
    private static boolean skippedStage(FlowNode start) {
        TagsAction tags = start.getPersistentAction(TagsAction.class);
        String status = tags == null ? null : tags.getTagValue(STAGE_STATUS_TAG);
        return status != null && status.startsWith("SKIPPED");
    }

    private static boolean inSkippedStage(FlowNode node) {
        List<FlowNode> blocks = new ArrayList<>(node.getEnclosingBlocks());
        if (node instanceof BlockEndNode<?> end) {
            blocks.add(end.getStartNode());
        }
        return blocks.stream().anyMatch(b -> b != null && isStageStart(b) && skippedStage(b));
    }

    // The agent of the innermost node block around a failed node.
    private static WorkspaceAction enclosingWorkspace(FlowNode node) {
        List<FlowNode> blocks = new ArrayList<>();
        if (node instanceof BlockEndNode<?> end) {
            blocks.add(end.getStartNode());
        }
        blocks.addAll(node.getEnclosingBlocks());
        for (FlowNode block : blocks) {
            if (block != null && agentOf(block) != null) {
                return block.getPersistentAction(WorkspaceAction.class);
            }
        }
        return null;
    }

    private static String agentOf(FlowNode node) {
        WorkspaceAction ws = node.getPersistentAction(WorkspaceAction.class);
        String agent = ws == null ? null : ws.getNode();
        return agent == null || agent.isEmpty() ? null : agent;
    }

    /** A numeric node id, or the largest int for an id that is not a number. */
    static int order(FlowNode node) {
        try {
            return Integer.parseInt(node.getId());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static List<Throwable> chain(Throwable top) {
        List<Throwable> out = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = top; t != null && out.size() < MAX_CAUSE_CHAIN && seen.add(t); t = t.getCause()) {
            out.add(t);
        }
        return out;
    }

    private static String errorEntry(Throwable t) {
        if (t instanceof MissingContextVariableException m) {
            return t.getClass().getName() + "["
                    + (m.getType() == null ? "null" : m.getType().getName()) + "]";
        }
        return t.getClass().getName();
    }

    private static String causeName(CauseOfInterruption cause) {
        if (cause instanceof CauseOfInterruption.UserInterruption) {
            return RetryDecision.USER_INTERRUPTION;
        }
        if (ClassNames.isA(cause, RetryDecision.TIMEOUT_INTERRUPTION)) {
            return RetryDecision.TIMEOUT_INTERRUPTION;
        }
        return cause.getClass().getName();
    }

    public List<String> getInterruptionCauses() {
        return interruptionCauses;
    }

    public boolean isRetryableCauseSeen() {
        return retryableCauseSeen;
    }

    public boolean isAgentContextLost() {
        return agentContextLost;
    }

    public List<String> getErrorClasses() {
        return errorClasses;
    }

    public List<String> getFailedStages() {
        return failedStages;
    }

    public List<String> getFailedStageIds() {
        return failedStageIds;
    }

    public List<ErrorLine> getStageErrorLines() {
        return stageErrorLines;
    }

    public List<String> getAgentNodes() {
        return agentNodes;
    }

    public List<String> getFailedAgents() {
        return failedAgents;
    }

    public List<String> getFailedAgentLabels() {
        return failedAgentLabels;
    }

    /** The step where the first error stage's failure started, or null. */
    public String getErrorStepId() {
        return errorStepId;
    }

    /** Why the graph could not be read, or null when it was. */
    public String getError() {
        return error;
    }
}
