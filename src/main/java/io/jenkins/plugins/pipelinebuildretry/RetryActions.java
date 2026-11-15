package io.jenkins.plugins.pipelinebuildretry;

import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.SimpleParameterDefinition;
import hudson.model.StringParameterValue;
import io.jenkins.plugins.pipelinebuildretry.core.Decision;
import io.jenkins.plugins.pipelinebuildretry.core.RetryOptions;
import io.jenkins.plugins.pipelinebuildretry.evidence.ClassNames;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** The actions a retry is queued with: parameters, causes and the counter. */
@Restricted(NoExternalUse.class)
final class RetryActions {

    static final String RESTART_CAUSE =
            "org.jenkinsci.plugins.pipeline.modeldefinition.causes.RestartDeclarativePipelineCause";
    static final String GERRIT_CAUSE = "com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.GerritCause";

    /** What was copied, dropped and added, for the verbose log. */
    record Built(List<Action> actions, List<String> kept, List<String> dropped, List<String> parameters) {}

    private RetryActions() {}

    static Built build(WorkflowRun run, RetryOptions o, Decision d) {
        int next = d.getNextRetryCount();
        WorkflowJob job = run.getParent();
        List<Action> actions = new ArrayList<>();
        List<String> parameterNames = new ArrayList<>();
        ParametersAction params = parameters(run, job, o.getParameterOverrides(), parameterNames);
        if (params != null) {
            actions.add(params);
        }
        List<Cause> keep = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (Cause c : run.getCauses()) {
            if (dropped(c, o)) {
                dropped.add(simpleName(c.getClass().getName()));
            } else {
                keep.add(c);
                kept.add(simpleName(c.getClass().getName()));
            }
        }
        keep.add(new BuildRetryCause(
                job.getFullName(), run.getNumber(), next, o.getMaxSchedule(), d.getResult(), d.getAbortKind()));
        actions.add(new CauseAction(keep));
        BuildRetryAction parent = run.getAction(BuildRetryAction.class);
        int root = parent != null ? parent.getRootBuildNumber() : run.getNumber();
        actions.add(new BuildRetryAction(
                next, o.getMaxSchedule(), run.getNumber(), o.isBadge(), root, d.getResult(), d.getAbortKind()));
        return new Built(actions, kept, dropped, parameterNames);
    }

    private static boolean dropped(Cause c, RetryOptions o) {
        if (c instanceof BuildRetryCause
                || ClassNames.isA(c, ClassNames.REPLAY_CAUSE)
                || ClassNames.isA(c, RESTART_CAUSE)
                || (!"keep".equals(o.getGerritCause()) && ClassNames.isA(c, GERRIT_CAUSE))) {
            return true;
        }
        for (Class<?> k = c.getClass(); k != null; k = k.getSuperclass()) {
            if (o.getDropCauses().contains(k.getName()) || o.getDropCauses().contains(simpleName(k.getName()))) {
                return true;
            }
        }
        return false;
    }

    private static ParametersAction parameters(
            WorkflowRun run, WorkflowJob job, Map<String, String> overrides, List<String> names) {
        ParametersAction original = run.getAction(ParametersAction.class);
        List<ParameterValue> values = new ArrayList<>();
        Set<String> safe = new LinkedHashSet<>();
        if (original != null) {
            original.getParameters().forEach(p -> safe.add(p.getName()));
            for (ParameterValue v : original.getAllParameters()) {
                if (!overrides.containsKey(v.getName())) {
                    values.add(v);
                }
            }
        }
        overrides.forEach((name, value) -> {
            values.add(overrideValue(job, name, value));
            safe.add(name);
        });
        values.forEach(v -> names.add(v.getName()));
        return values.isEmpty() ? null : new ParametersAction(values, safe);
    }

    // A defined parameter keeps its type; an undefined one becomes a string the job can still read.
    private static ParameterValue overrideValue(WorkflowJob job, String name, String value) {
        try {
            ParametersDefinitionProperty p = job.getProperty(ParametersDefinitionProperty.class);
            ParameterDefinition d = p == null ? null : p.getParameterDefinition(name);
            ParameterValue v = d instanceof SimpleParameterDefinition s ? s.createValue(value) : null;
            if (v != null) {
                return v;
            }
        } catch (RuntimeException e) {
            // an unparsable value falls back to a string
        }
        return new StringParameterValue(name, value);
    }

    static String simpleName(String name) {
        int open = name.indexOf('[');
        if (open > 0 && name.endsWith("]")) {
            return simpleName(name.substring(0, open)) + "[" + simpleName(name.substring(open + 1, name.length() - 1))
                    + "]";
        }
        String s = name.substring(name.lastIndexOf('.') + 1);
        return s.substring(s.lastIndexOf('$') + 1);
    }
}
