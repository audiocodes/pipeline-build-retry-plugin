package io.jenkins.plugins.pipelinebuildretry.evidence;

import hudson.model.Cause;
import hudson.model.Result;
import io.jenkins.plugins.pipelinebuildretry.BuildRetryAction;
import io.jenkins.plugins.pipelinebuildretry.core.RunSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Builds the decision's snapshot of a Pipeline run, apart from the log scan and the run's own markers. */
@Restricted(NoExternalUse.class)
public final class RunSnapshots {

    private RunSnapshots() {}

    /** The snapshot for this run as if it ended with this result. */
    public static RunSnapshot of(WorkflowRun run, Result result) {
        List<Cause> causes = run.getCauses();
        FlowEvidence graph = FlowEvidence.of(run);
        Set<String> agents = new LinkedHashSet<>(graph.getAgentNodes());
        agents.addAll(graph.getFailedAgents());
        Map<String, List<String>> byAgent = TerminationReasons.byAgent(agents);
        Set<String> all = new TreeSet<>();
        Map<String, List<String>> failed = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : byAgent.entrySet()) {
            if (graph.getAgentNodes().contains(e.getKey())) {
                all.addAll(e.getValue());
            }
            if (graph.getFailedAgents().contains(e.getKey())) {
                failed.put(e.getKey(), e.getValue());
            }
        }
        BuildRetryAction counter = run.getAction(BuildRetryAction.class);
        DisableConcurrentBuildsJobProperty concurrency =
                run.getParent().getProperty(DisableConcurrentBuildsJobProperty.class);
        return new RunSnapshot()
                .result(String.valueOf(result))
                .replay(causes.stream().anyMatch(c -> ClassNames.isA(c, ClassNames.REPLAY_CAUSE)))
                .startedByUser(causes.stream().anyMatch(c -> c instanceof Cause.UserIdCause))
                .retry(counter != null)
                .retryCount(counter == null ? 0 : counter.getRetryCount())
                .interruptionCauses(graph.getInterruptionCauses())
                .retryableCauseSeen(graph.isRetryableCauseSeen())
                .agentContextLost(graph.isAgentContextLost())
                .errorClasses(graph.getErrorClasses())
                .terminationReasons(new ArrayList<>(all))
                .failedAgentReasons(failed)
                .failedAgents(graph.getFailedAgents())
                .failedAgentLabels(graph.getFailedAgentLabels())
                .failedStages(graph.getFailedStages())
                .failedStageIds(graph.getFailedStageIds())
                .stageErrorLines(graph.getStageErrorLines())
                .errorStepId(graph.getErrorStepId())
                .abortPrevious(concurrency != null && concurrency.isAbortPrevious())
                .flowGraphError(graph.getError());
    }
}
