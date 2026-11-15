package io.jenkins.plugins.pipelinebuildretry.steps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.pipelinebuildretry.BuildRetryGlobalConfiguration;
import io.jenkins.plugins.pipelinebuildretry.BuildRetryJobProperty;
import io.jenkins.plugins.pipelinebuildretry.BuildRetryListener;
import io.jenkins.plugins.pipelinebuildretry.PointOfNoReturnAction;
import io.jenkins.plugins.pipelinebuildretry.RecordedDecisionAction;
import io.jenkins.plugins.pipelinebuildretry.RetryScheduledAction;
import io.jenkins.plugins.pipelinebuildretry.core.Decision;
import io.jenkins.plugins.pipelinebuildretry.core.RetryDecision;
import io.jenkins.plugins.pipelinebuildretry.core.RetryOptions;
import io.jenkins.plugins.pipelinebuildretry.core.RunSnapshot;
import io.jenkins.plugins.pipelinebuildretry.core.ScanPlan;
import io.jenkins.plugins.pipelinebuildretry.evidence.LogScan;
import io.jenkins.plugins.pipelinebuildretry.evidence.RunSnapshots;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

/** Decides now, with the build's current result, records that preview, and returns it. */
public class BuildRetryDecisionStep extends Step {

    @DataBoundConstructor
    public BuildRetryDecisionStep() {}

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context);
    }

    private static final class Execution extends SynchronousNonBlockingStepExecution<Map<String, Object>> {

        private static final long serialVersionUID = 1L;

        Execution(StepContext context) {
            super(context);
        }

        @Override
        protected Map<String, Object> run() throws Exception {
            Run<?, ?> raw = getContext().get(Run.class);
            PrintStream log = getContext().get(TaskListener.class).getLogger();
            if (!(raw instanceof WorkflowRun run) || run.getParent().getProperty(BuildRetryJobProperty.class) == null) {
                Map<String, Object> out =
                        Decision.noRetry(currentResult(raw), "not-configured").toMap();
                log.println(RetryDecision.LOG_PREFIX + "no retry (not-configured): this job has no buildRetry option");
                return out;
            }
            if (!BuildRetryGlobalConfiguration.get().isEnabled()) {
                log.println(
                        RetryDecision.LOG_PREFIX + "no retry (disabled): retries are turned off on this controller");
                return Decision.noRetry(currentResult(run), "disabled").toMap();
            }
            String result = currentResult(run);
            RetryOptions options =
                    run.getParent().getProperty(BuildRetryJobProperty.class).toOptions();
            PointOfNoReturnAction ponr = run.getAction(PointOfNoReturnAction.class);
            RunSnapshot snapshot = RunSnapshots.of(run, Result.fromString(result))
                    .alreadyScheduled(run.getAction(RetryScheduledAction.class) != null)
                    .pointOfNoReturn(ponr == null ? null : ponr.getReason());
            BuildRetryListener.warnIfNoGraph(snapshot, log);
            ScanPlan plan = RetryDecision.logScanPlan(options, snapshot);
            if (!plan.isEmpty()) {
                long timeout = options.effectiveRegexpTimeoutMs(
                        BuildRetryGlobalConfiguration.get().getRegexpTimeoutMs());
                snapshot.logScan(LogScan.scan(run, plan, timeout, snapshot));
            }
            Decision d = RetryDecision.decide(options, snapshot);
            run.replaceAction(new RecordedDecisionAction(result, snapshot.getPointOfNoReturn(), options, d));
            run.save();
            log.println(RetryDecision.summary(d) + "; decided again when the build ends if the result changes");
            if (d.isVerbose()) {
                d.getDetails().forEach(line -> log.println(RetryDecision.LOG_PREFIX + "  " + line));
            }
            return new LinkedHashMap<>(d.toMap());
        }

        private static String currentResult(Run<?, ?> run) {
            Result r = run == null ? null : run.getResult();
            return String.valueOf(r == null ? Result.SUCCESS : r);
        }
    }

    /** Descriptor for {@link BuildRetryDecisionStep}. */
    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class);
        }

        @Override
        public String getFunctionName() {
            return "buildRetryDecision";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Preview the build retry decision";
        }
    }
}
