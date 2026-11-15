package io.jenkins.plugins.pipelinebuildretry;

import hudson.model.InvisibleAction;
import io.jenkins.plugins.pipelinebuildretry.core.Decision;
import io.jenkins.plugins.pipelinebuildretry.core.RetryOptions;
import java.util.Objects;

/** The preview buildRetryDecision() recorded, with the options and facts it was made from. */
public class RecordedDecisionAction extends InvisibleAction {

    private final String result;
    private final String pointOfNoReturn;
    private final RetryOptions options;
    private final Decision decision;

    public RecordedDecisionAction(String result, String pointOfNoReturn, RetryOptions options, Decision decision) {
        this.result = result;
        this.pointOfNoReturn = pointOfNoReturn;
        this.options = options.copy();
        this.decision = decision;
    }

    public String getResult() {
        return result;
    }

    public String getPointOfNoReturn() {
        return pointOfNoReturn;
    }

    /** A copy of the options the preview used; the end decision uses them too. */
    public RetryOptions getOptions() {
        return options.copy();
    }

    public Decision getDecision() {
        return decision;
    }

    /** True when the build ended as the preview saw it, so the preview stands. */
    public boolean matches(String endResult, String endPointOfNoReturn) {
        return Objects.equals(result, endResult) && Objects.equals(pointOfNoReturn, endPointOfNoReturn);
    }
}
