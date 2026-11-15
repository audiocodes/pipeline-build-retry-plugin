package io.jenkins.plugins.pipelinebuildretry.evidence;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Classes from plugins this one does not depend on, matched by name so they may be absent. */
@Restricted(NoExternalUse.class)
public final class ClassNames {

    public static final String REPLAY_CAUSE = "org.jenkinsci.plugins.workflow.cps.replay.ReplayCause";
    public static final String RETRYABLE = "org.jenkinsci.plugins.workflow.support.steps.AgentErrorCondition$Retryable";
    public static final String LOG_STORAGE = "org.jenkinsci.plugins.workflow.log.LogStorage";
    public static final String FILE_LOG_STORAGE = "org.jenkinsci.plugins.workflow.log.FileLogStorage";
    public static final String BRANCH_INDEXING_CAUSE = "jenkins.branch.BranchIndexingCause";
    public static final String BRANCH_EVENT_CAUSE = "jenkins.branch.BranchEventCause";
    public static final String OTEL_LOG_STORAGE = "io.jenkins.plugins.opentelemetry.job.log.OtelLogStorage";

    private ClassNames() {}

    /** True when the object's class, a superclass or an interface has this name. */
    public static boolean isA(Object o, String name) {
        if (o == null) {
            return false;
        }
        Deque<Class<?>> todo = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        todo.add(o.getClass());
        while (!todo.isEmpty()) {
            Class<?> k = todo.poll();
            if (!seen.add(k)) {
                continue;
            }
            if (k.getName().equals(name)) {
                return true;
            }
            if (k.getSuperclass() != null) {
                todo.add(k.getSuperclass());
            }
            todo.addAll(List.of(k.getInterfaces()));
        }
        return false;
    }
}
