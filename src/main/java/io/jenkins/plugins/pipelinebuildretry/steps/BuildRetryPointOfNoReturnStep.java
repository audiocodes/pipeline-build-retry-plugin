package io.jenkins.plugins.pipelinebuildretry.steps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.pipelinebuildretry.PointOfNoReturnAction;
import io.jenkins.plugins.pipelinebuildretry.core.RetryDecision;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

/** Marks this build as never to be retried from here on, for example after a deployment. */
public class BuildRetryPointOfNoReturnStep extends Step {

    private final String reason;

    @DataBoundConstructor
    public BuildRetryPointOfNoReturnStep(String reason) {
        this.reason = reason == null || reason.isBlank() ? "point of no return" : reason;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, reason);
    }

    private static final class Execution extends SynchronousStepExecution<Void> {

        private static final long serialVersionUID = 1L;
        private final String reason;

        Execution(StepContext context, String reason) {
            super(context);
            this.reason = reason;
        }

        @Override
        protected Void run() throws Exception {
            Run<?, ?> run = getContext().get(Run.class);
            if (run.getAction(PointOfNoReturnAction.class) == null) {
                run.addAction(new PointOfNoReturnAction(reason));
                run.save();
            }
            getContext()
                    .get(TaskListener.class)
                    .getLogger()
                    .println(RetryDecision.LOG_PREFIX + "point of no return: " + reason
                            + "; this build will not be retried");
            return null;
        }
    }

    /** Descriptor for {@link BuildRetryPointOfNoReturnStep}. */
    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class);
        }

        @Override
        public String getFunctionName() {
            return "buildRetryPointOfNoReturn";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Mark the point of no return for build retry";
        }
    }
}
