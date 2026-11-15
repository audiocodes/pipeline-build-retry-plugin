package io.jenkins.plugins.pipelinebuildretry;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.CauseAction;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.model.queue.ScheduleResult;
import io.jenkins.plugins.pipelinebuildretry.core.Decision;
import io.jenkins.plugins.pipelinebuildretry.core.RetryDecision;
import io.jenkins.plugins.pipelinebuildretry.core.RetryOptions;
import io.jenkins.plugins.pipelinebuildretry.core.RunSnapshot;
import io.jenkins.plugins.pipelinebuildretry.core.ScanPlan;
import io.jenkins.plugins.pipelinebuildretry.evidence.LogScan;
import io.jenkins.plugins.pipelinebuildretry.evidence.RunSnapshots;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Decides when a Pipeline build ends and queues its retry; acts only on jobs with buildRetry. */
@Extension
@Restricted(NoExternalUse.class)
public class BuildRetryListener extends RunListener<WorkflowRun> {

    static final String ARMED_BY = "pipeline-build-retry";
    private static final Logger LOGGER = Logger.getLogger(BuildRetryListener.class.getName());
    private static final String PREFIX = RetryDecision.LOG_PREFIX;
    // Stops a second onCompleted on another thread while the first one still decides.
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final int NEWER_BUILDS_CHECKED = 10;

    @Override
    public void onCompleted(WorkflowRun run, @NonNull TaskListener listener) {
        BuildRetryJobProperty property = run.getParent().getProperty(BuildRetryJobProperty.class);
        if (property == null) {
            return;
        }
        PrintStream log = listener.getLogger();
        String id = run.getExternalizableId();
        if (run.getAction(DecidedAction.class) != null || !IN_FLIGHT.add(id)) {
            return;
        }
        try {
            if (!BuildRetryGlobalConfiguration.get().isEnabled()) {
                log.println(PREFIX + "retries are turned off on this controller; no decision (an administrator turns"
                        + " them back on with " + BuildRetryGlobalConfiguration.class.getName()
                        + ".get().setEnabled(true) in the script console, or on Manage Jenkins → System)");
                BuildRetryEvents.decision(
                        run, Decision.noRetry(String.valueOf(run.getResult()), "disabled"), null, "disabled", 0L);
                return;
            }
            complete(run, property, log);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.WARNING, "Retry decision failed for " + run, e);
            log.println(PREFIX + "no retry: internal error " + e.getClass().getName());
            record(run, Decision.noRetry(String.valueOf(run.getResult()), "internal-error"), null, "error", 0L);
        } finally {
            IN_FLIGHT.remove(id);
        }
    }

    private void complete(WorkflowRun run, BuildRetryJobProperty property, PrintStream log) {
        String result = String.valueOf(run.getResult() == null ? Result.FAILURE : run.getResult());
        RecordedDecisionAction preview = run.getAction(RecordedDecisionAction.class);
        RetryOptions options = preview != null ? preview.getOptions() : property.toOptions();
        PointOfNoReturnAction ponr = run.getAction(PointOfNoReturnAction.class);
        RunSnapshot snapshot = RunSnapshots.of(run, Result.fromString(result))
                .alreadyScheduled(run.getAction(RetryScheduledAction.class) != null)
                .pointOfNoReturn(ponr == null ? null : ponr.getReason());
        warnIfNoGraph(snapshot, log);
        Decision d;
        if (preview != null
                && !snapshot.isAlreadyScheduled()
                && preview.matches(result, snapshot.getPointOfNoReturn())) {
            d = preview.getDecision();
            log.println(PREFIX + "using the decision recorded by buildRetryDecision()");
        } else {
            if (preview != null) {
                log.println(PREFIX + "deciding again: recorded at " + preview.getResult()
                        + describePonr(preview.getPointOfNoReturn()) + ", ended " + result
                        + describePonr(snapshot.getPointOfNoReturn())
                        + (snapshot.isAlreadyScheduled() ? ", retry already scheduled" : ""));
            }
            d = decide(run, options, snapshot);
        }
        log.println(RetryDecision.summary(d));
        if (d.isVerbose()) {
            d.getDetails().forEach(line -> log.println(PREFIX + "  " + line));
        }
        d.getWarnings().forEach(w -> log.println(PREFIX + "WARNING " + w));
        if (!d.isWillRetry()) {
            if (d.isDryRun() && d.isWouldRetry()) {
                Copy copy = requeuedElsewhere(run);
                if (copy != null) {
                    log.println(PREFIX + describeRetry(d)
                            + " would be skipped: this build was already re-queued by another plugin ("
                            + copy.describe() + ")");
                    record(run, d, snapshot, "requeued-elsewhere", 0L, copy);
                    return;
                }
                record(run, d, snapshot, "dry-run", 0L);
                return;
            }
            record(run, d, snapshot, "no-retry", 0L);
            return;
        }
        schedule(run, options, d, snapshot, log);
    }

    /** Warns that the flow graph was unreadable, so abort and stage evidence are missing. */
    public static void warnIfNoGraph(RunSnapshot snapshot, PrintStream log) {
        if (snapshot.getFlowGraphError() != null) {
            log.println(PREFIX + "WARNING flow graph unreadable (" + snapshot.getFlowGraphError()
                    + "): deciding without abort, agent and stage evidence");
        }
    }

    private static String describeRetry(Decision d) {
        return "retry " + d.getNextRetryCount() + "/"
                + (d.getMaxSchedule() > 0 ? String.valueOf(d.getMaxSchedule()) : "unlimited");
    }

    private static String describePonr(String ponr) {
        return ponr == null ? "" : " after the point of no return";
    }

    private Decision decide(WorkflowRun run, RetryOptions options, RunSnapshot snapshot) {
        ScanPlan plan = RetryDecision.logScanPlan(options, snapshot);
        if (!plan.isEmpty()) {
            long timeout = options.effectiveRegexpTimeoutMs(
                    BuildRetryGlobalConfiguration.get().getRegexpTimeoutMs());
            try {
                snapshot.logScan(LogScan.scan(run, plan, timeout, snapshot));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Decision.noRetry(snapshot.getResult(), "scan-interrupted");
            }
        }
        return RetryDecision.decide(options, snapshot);
    }

    // D28 order: superseded, not buildable, then queue with the delay counted from now.
    private void schedule(WorkflowRun run, RetryOptions options, Decision d, RunSnapshot snapshot, PrintStream log) {
        WorkflowJob job = run.getParent();
        String retry = describeRetry(d);
        if (superseded(run)) {
            log.println(PREFIX + retry + " skipped: a newer build of this job started (abortPrevious)");
            record(run, d, snapshot, "superseded", 0L);
            return;
        }
        Copy copy = requeuedElsewhere(run);
        if (copy != null) {
            log.println(PREFIX + retry + " skipped: this build was already re-queued by another plugin ("
                    + copy.describe() + ")");
            record(run, d, snapshot, "requeued-elsewhere", 0L, copy);
            return;
        }
        if (!job.isBuildable()) {
            log.println(PREFIX + retry + " not queued: the job is not buildable");
            record(run, d, snapshot, "not-buildable", 0L);
            return;
        }
        RetryActions.Built built = RetryActions.build(run, options, d);
        run.addAction(new RetryScheduledAction(ARMED_BY));
        saveRun(run);
        Queue queue = Jenkins.get().getQueue();
        ScheduleResult r = queue.schedule2(job, d.getDelaySeconds(), built.actions());
        Queue.Item item = r.getItem();
        if (!r.isAccepted() || item == null) {
            log.println(PREFIX + retry + " not queued: the queue refused it");
            record(run, d, snapshot, "not-queued", 0L);
            return;
        }
        if (superseded(run)) {
            CancelledRetryListener.cancel(queue, item);
            log.println(PREFIX + retry + " cancelled: a newer build of this job started while queuing (abortPrevious)");
            record(run, d, snapshot, "cancelled", 0L);
            return;
        }
        saveQueue(queue);
        log.println(PREFIX + retry + " queued (item " + item.getId() + ")"
                + (abortPrevious(job) ? "; cancelled if another build of this job starts first (abortPrevious)" : ""));
        if (d.isVerbose()) {
            log.println(PREFIX + "  causes kept " + built.kept() + ", dropped " + built.dropped() + "; parameters "
                    + built.parameters());
        }
        record(run, d, snapshot, "queued", item.getId());
    }

    static boolean abortPrevious(WorkflowJob job) {
        DisableConcurrentBuildsJobProperty p = job.getProperty(DisableConcurrentBuildsJobProperty.class);
        return p != null && p.isAbortPrevious();
    }

    private static boolean superseded(WorkflowRun run) {
        Run<?, ?> last = run.getParent().getLastBuild();
        return abortPrevious(run.getParent()) && last != null && last.getNumber() > run.getNumber();
    }

    // Cloud plugins that re-queue a build on agent loss pass this build's own action objects along.
    /** A copy of a build that another plugin queued: its queue item while it waits, else its build number. */
    record Copy(long queueItemId, int buildNumber) {
        String describe() {
            return queueItemId > 0 ? "queue item " + queueItemId : "build #" + buildNumber;
        }
    }

    private static Copy requeuedElsewhere(WorkflowRun run) {
        List<Action> own = new ArrayList<>(run.getActions(CauseAction.class));
        own.addAll(run.getActions(ParametersAction.class));
        if (own.isEmpty()) {
            return null;
        }
        for (Queue.Item item : Jenkins.get().getQueue().getItems(run.getParent())) {
            if (sharesAny(item.getActions(), own)) {
                return new Copy(item.getId(), 0);
            }
        }
        WorkflowRun newer = run.getNextBuild();
        for (int i = 0; newer != null && i < NEWER_BUILDS_CHECKED; i++, newer = newer.getNextBuild()) {
            if (sharesAny(newer.getActions(), own)) {
                return new Copy(0L, newer.getNumber());
            }
        }
        return null;
    }

    private static boolean sharesAny(List<? extends Action> actions, List<Action> own) {
        for (Action a : actions) {
            for (Action o : own) {
                if (a == o) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void record(WorkflowRun run, Decision d, RunSnapshot snapshot, String outcome, long queueId) {
        record(run, d, snapshot, outcome, queueId, null);
    }

    private static void record(
            WorkflowRun run, Decision d, RunSnapshot snapshot, String outcome, long queueId, Copy copy) {
        run.addAction(new DecidedAction(d, outcome, queueId));
        saveRun(run);
        BuildRetryEvents.decision(run, d, snapshot, outcome, queueId, copy);
    }

    private static void saveRun(WorkflowRun run) {
        try {
            run.save();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not save " + run, e);
        }
    }

    // queue.xml is otherwise written about once a minute; a crash before then would lose the retry.
    private static void saveQueue(Queue queue) {
        try {
            queue.save();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not save the queue", e);
        }
    }
}
