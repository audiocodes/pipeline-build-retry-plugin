package io.jenkins.plugins.pipelinebuildretry;

import hudson.model.Cause;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.pipelinebuildretry.core.Decision;
import io.jenkins.plugins.pipelinebuildretry.core.FailureFacts;
import io.jenkins.plugins.pipelinebuildretry.core.RetryDecision;
import io.jenkins.plugins.pipelinebuildretry.core.RunSnapshot;
import io.jenkins.plugins.pipelinebuildretry.core.ScanResult;
import io.jenkins.plugins.pipelinebuildretry.events.RetryEvent;
import io.jenkins.plugins.pipelinebuildretry.events.RetryEvents;
import io.jenkins.plugins.pipelinebuildretry.evidence.ClassNames;
import io.jenkins.plugins.pipelinebuildretry.evidence.FailureLine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Builds the monitoring events (README "Events") and hands them to {@link RetryEvents}. */
@Restricted(NoExternalUse.class)
final class BuildRetryEvents {

    static final String DECISION = "build_retry.decision";
    static final String RETRY_STARTED = "build_retry.retry_started";
    static final String CHAIN_ENDED = "build_retry.chain_ended";
    static final String RETRY_CANCELLED = "build_retry.retry_cancelled";
    static final String SWITCH_CHANGED = "build_retry.switch_changed";
    static final String QUEUE_CANCELLED = "queue-cancelled";

    private static final Set<String> WARN_REASONS = Set.of("internal-error", "invalid-config");

    private BuildRetryEvents() {}

    private static boolean enabled() {
        return BuildRetryGlobalConfiguration.get().isSendEvents();
    }

    /** The end decision of a build and what became of it; {@code snapshot} is null when none was taken. */
    static void decision(WorkflowRun run, Decision d, RunSnapshot snapshot, String outcome, long queueId) {
        decision(run, d, snapshot, outcome, queueId, null);
    }

    /** As above; {@code copy} is another plugin's copy of this build, which carries the chain on. */
    static void decision(
            WorkflowRun run,
            Decision d,
            RunSnapshot snapshot,
            String outcome,
            long queueId,
            BuildRetryListener.Copy copy) {
        RetryEvents.send(BuildRetryEvents::enabled, () -> {
            RetryEvent e = decisionEvent(run, d, snapshot, outcome, queueId);
            if (copy != null && copy.queueItemId() > 0) {
                e.put("build_retry.copy_queue_item_id", copy.queueItemId());
            } else if (copy != null) {
                e.put("build_retry.copy_build_number", (long) copy.buildNumber());
            }
            return e;
        });
        if (!"queued".equals(outcome)
                && !"requeued-elsewhere".equals(outcome)
                && run.getAction(BuildRetryAction.class) != null) {
            RetryEvents.send(BuildRetryEvents::enabled, () -> chainEndedEvent(run, d));
        }
    }

    static void retryStarted(WorkflowRun run) {
        if (run.getAction(BuildRetryAction.class) != null) {
            RetryEvents.send(BuildRetryEvents::enabled, () -> retryStartedEvent(run));
        }
    }

    static void retryCancelled(WorkflowRun superseding, Queue.Item item, BuildRetryAction a) {
        RetryEvents.send(BuildRetryEvents::enabled, () -> {
            String job = superseding.getParent().getFullName();
            return run(
                            new RetryEvent(
                                    RETRY_CANCELLED,
                                    job + " #" + superseding.getNumber() + ": pending retry " + a.getRetryCount()
                                            + " of build #" + a.getParentBuildNumber() + " cancelled (abortPrevious)"),
                            superseding)
                    .put("build_retry.reason", "superseded")
                    .put("build_retry.retry_count", (long) a.getRetryCount())
                    .put("build_retry.max_retries", (long) a.getMaxRetryCount())
                    .put("build_retry.parent_build_number", (long) a.getParentBuildNumber())
                    .put("build_retry.root_build_number", (long) a.getRootBuildNumber())
                    .put("build_retry.waited_ms", Math.max(0L, System.currentTimeMillis() - item.getInQueueSince()));
        });
        RetryEvents.send(BuildRetryEvents::enabled, () -> {
            Run<?, ?> parent = superseding.getParent().getBuildByNumber(a.getParentBuildNumber());
            if (parent == null) {
                return null;
            }
            return chainEnded(parent, "cancelled", "superseded", a.getRetryCount(), a.getRootBuildNumber())
                    .put("ci.pipeline.run.result", String.valueOf(parent.getResult()))
                    .put("build_retry.superseded_by", (long) superseding.getNumber());
        });
    }

    /** A waiting retry was cancelled in the queue by someone other than this plugin. */
    static void retryCancelledInQueue(Job<?, ?> job, Queue.Item item, BuildRetryAction a, String source) {
        Run<?, ?> parent = job.getBuildByNumber(a.getParentBuildNumber());
        if (parent == null) {
            return;
        }
        RetryEvents.send(
                BuildRetryEvents::enabled,
                () -> run(
                                new RetryEvent(
                                        RETRY_CANCELLED,
                                        job.getFullName() + " #" + parent.getNumber() + ": pending retry "
                                                + a.getRetryCount() + " cancelled in the queue"),
                                parent)
                        .put("build_retry.reason", QUEUE_CANCELLED)
                        .put("build_retry.cancel_source", source)
                        .put("build_retry.queue_item_id", item.getId())
                        .put("build_retry.retry_count", (long) a.getRetryCount())
                        .put("build_retry.max_retries", (long) a.getMaxRetryCount())
                        .put("build_retry.parent_build_number", (long) a.getParentBuildNumber())
                        .put("build_retry.root_build_number", (long) a.getRootBuildNumber())
                        .put(
                                "build_retry.waited_ms",
                                Math.max(0L, System.currentTimeMillis() - item.getInQueueSince())));
        RetryEvents.send(
                BuildRetryEvents::enabled,
                () -> chainEnded(parent, "cancelled", QUEUE_CANCELLED, a.getRetryCount(), a.getRootBuildNumber())
                        .put("ci.pipeline.run.result", String.valueOf(parent.getResult())));
    }

    /** A controller setting changed; sent while sending is on before or after the change. */
    static void switchChanged(String setting, boolean before, boolean after, boolean sendingBefore) {
        RetryEvents.send(
                () -> sendingBefore || enabled(),
                () -> new RetryEvent(SWITCH_CHANGED, setting + " changed from " + before + " to " + after)
                        .put("build_retry.setting", setting)
                        .put("build_retry.old_value", before)
                        .put("build_retry.new_value", after)
                        .put(
                                "build_retry.changed_by",
                                Jenkins.getAuthentication2().getName()));
    }

    private static RetryEvent decisionEvent(
            WorkflowRun run, Decision d, RunSnapshot snapshot, String outcome, long queueId) {
        String summary = RetryDecision.summary(d).substring(RetryDecision.LOG_PREFIX.length());
        String reason = String.valueOf(d.getReason());
        int colon = reason.indexOf(':');
        String code = colon < 0 ? reason : reason.substring(0, colon);
        boolean warn = "error".equals(outcome) || WARN_REASONS.contains(code);
        RetryEvent e = run(
                        new RetryEvent(
                                DECISION,
                                run.getParent().getFullName() + " #" + run.getNumber() + ": " + summary + " [" + outcome
                                        + "]"),
                        run)
                .severity(warn ? RetryEvent.Severity.WARN : RetryEvent.Severity.INFO)
                .put("ci.pipeline.run.result", d.getResult())
                .put("build_retry.outcome", outcome)
                .put("build_retry.reason", code)
                .put(
                        "build_retry.reason_detail",
                        colon < 0 ? null : reason.substring(colon + 1).trim())
                .put("build_retry.abort_kind", d.getAbortKind())
                .put("build_retry.retry_count", (long) d.getRetryCount())
                .put("build_retry.next_retry_count", d.getNextRetryCount())
                .put("build_retry.max_retries", (long) d.getMaxSchedule())
                .put("build_retry.delay_seconds", d.getDelaySeconds())
                .put("build_retry.delay_type", d.getDelayType())
                .put("build_retry.dry_run", d.isDryRun())
                .put("build_retry.would_retry", d.isWouldRetry())
                .put("build_retry.regexp_checked", d.isRegexChecked())
                .put("build_retry.regexp_matched", d.isRegexMatched())
                .put("build_retry.agent_loss", d.isAgentLoss())
                .put("build_retry.agent_loss_override", d.isAgentLossOverride())
                .put("build_retry.counter_reset", d.isCounterReset())
                .put("build_retry.never_retry_checked", d.isNeverRetryChecked())
                .put("build_retry.never_retry_matched", d.getNeverRetryPattern() != null);
        if (d.getNeverRetryPattern() != null) {
            e.put("build_retry.never_retry_pattern_index", (long) d.getNeverRetryPattern());
        }
        if (queueId > 0) {
            e.put("build_retry.queue_item_id", queueId);
        }
        chain(e, run);
        causes(e, run);
        if (snapshot != null) {
            String failureClass = FailureFacts.failureClass(snapshot);
            // Reads a log tail, so only here, where events are on, and only for a build that did not pass.
            FailureFacts.Step step = failureClass == null ? null : FailureLine.of(run, snapshot.getErrorStepId());
            FailureFacts.Fingerprint fingerprint =
                    failureClass == null ? null : FailureFacts.fingerprint(snapshot, step);
            e.put("build_retry.failed_stages", snapshot.getFailedStages())
                    .put("build_retry.termination_reasons", terminationReasons(snapshot))
                    .put("build_retry.error_classes", simpleNames(snapshot.getErrorClasses()))
                    .put("build_retry.interruption_causes", simpleNames(snapshot.getInterruptionCauses()))
                    .put("build_retry.point_of_no_return", snapshot.getPointOfNoReturn())
                    .put("build_retry.flow_graph_error", snapshot.getFlowGraphError())
                    .put("build_retry.failure_class", failureClass)
                    .put("build_retry.error_stage", FailureFacts.errorStage(snapshot))
                    .put("build_retry.error_fingerprint", fingerprint == null ? null : fingerprint.hash())
                    .put("build_retry.error_basis", fingerprint == null ? null : fingerprint.basis())
                    .put("build_retry.error_step", fingerprint == null || step == null ? null : step.function())
                    .put("build_retry.failed_agents", snapshot.getFailedAgents())
                    .put("build_retry.failed_agent_labels", snapshot.getFailedAgentLabels());
            ScanResult scan = snapshot.getLogScan();
            if (scan != null) {
                e.put("build_retry.scan.scope", scan.getScope())
                        .put("build_retry.scan.lines_read", (long) scan.getLinesRead())
                        .put("build_retry.scan.cut_lines", (long) scan.getCutLines())
                        .put("build_retry.scan.timed_out", scan.isTimedOut())
                        .put("build_retry.scan.pattern_error", scan.isPatternError())
                        .put("build_retry.scan.io_error", scan.isIoError())
                        .put("build_retry.scan.storage_fallback", scan.getStorageFallback())
                        .put("build_retry.scan.heap_wait_ms", scan.getHeapWaitMs());
            }
        }
        return e;
    }

    // Sent only for a retry build whose chain stops here, so a single failed build is one decision event.
    private static RetryEvent chainEndedEvent(WorkflowRun run, Decision d) {
        BuildRetryAction a = run.getAction(BuildRetryAction.class);
        String how = "SUCCESS".equals(d.getResult())
                ? "recovered"
                : "max-reached".equals(d.getReason()) ? "exhausted" : "stopped";
        return chainEnded(run, how, d.getReason(), a.getRetryCount() + 1, a.getRootBuildNumber())
                .put("ci.pipeline.run.result", d.getResult());
    }

    private static RetryEvent chainEnded(Run<?, ?> last, String how, String reason, int attempts, int root) {
        RetryEvent e = run(
                        new RetryEvent(
                                CHAIN_ENDED,
                                last.getParent().getFullName() + " #" + last.getNumber() + ": retry chain from #" + root
                                        + " " + how + " after " + attempts + " attempts"),
                        last)
                .put("build_retry.chain_end", how)
                .put("build_retry.reason", reason)
                .put("build_retry.attempts", (long) attempts)
                .put("build_retry.root_build_number", (long) root);
        Run<?, ?> first = last.getParent().getBuildByNumber(root);
        if (first != null) {
            long end = last.getStartTimeInMillis() + Math.max(0L, last.getDuration());
            e.put("build_retry.chain_duration_ms", Math.max(0L, end - first.getStartTimeInMillis()));
        }
        return e;
    }

    private static RetryEvent retryStartedEvent(WorkflowRun run) {
        BuildRetryAction a = run.getAction(BuildRetryAction.class);
        RetryEvent e = run(
                        new RetryEvent(
                                RETRY_STARTED,
                                run.getParent().getFullName() + " #" + run.getNumber() + ": retry " + a.getRetryCount()
                                        + " of build #" + a.getParentBuildNumber() + " started"),
                        run)
                .put("build_retry.retry_count", (long) a.getRetryCount())
                .put("build_retry.max_retries", (long) a.getMaxRetryCount())
                .put("build_retry.parent_build_number", (long) a.getParentBuildNumber())
                .put("build_retry.root_build_number", (long) a.getRootBuildNumber());
        Run<?, ?> parent = run.getParent().getBuildByNumber(a.getParentBuildNumber());
        if (parent != null) {
            DecidedAction decided = parent.getAction(DecidedAction.class);
            if (decided != null) {
                e.put("build_retry.planned_delay_seconds", decided.getDecision().getDelaySeconds());
            }
            long parentEnd = parent.getStartTimeInMillis() + Math.max(0L, parent.getDuration());
            if (parent.getDuration() > 0) {
                e.put("build_retry.waited_ms", Math.max(0L, run.getStartTimeInMillis() - parentEnd));
            }
        }
        causes(e, run);
        return e;
    }

    private static RetryEvent run(RetryEvent e, Run<?, ?> run) {
        e.put("ci.pipeline.id", run.getParent().getFullName())
                .put("ci.pipeline.run.number", (long) run.getNumber())
                .put("ci.pipeline.run.queue_ms", Math.max(0L, run.getStartTimeInMillis() - run.getTimeInMillis()));
        if (run.getQueueId() > 0) {
            e.put("ci.pipeline.run.queue_item_id", run.getQueueId());
        }
        if (run.getDuration() > 0) {
            e.put("ci.pipeline.run.duration_ms", run.getDuration());
        }
        String root = Jenkins.get().getRootUrl();
        if (root != null) {
            e.put("ci.pipeline.run.url", root + run.getUrl());
        }
        return e;
    }

    private static void chain(RetryEvent e, Run<?, ?> run) {
        BuildRetryAction a = run.getAction(BuildRetryAction.class);
        if (a != null) {
            e.put("build_retry.parent_build_number", (long) a.getParentBuildNumber())
                    .put("build_retry.root_build_number", (long) a.getRootBuildNumber());
        }
    }

    private static void causes(RetryEvent e, Run<?, ?> run) {
        Set<String> kinds = new LinkedHashSet<>();
        List<String> descriptions = new ArrayList<>();
        for (Cause c : run.getCauses()) {
            kinds.add(kind(c));
            descriptions.add(c.getShortDescription());
            if (c instanceof Cause.UserIdCause u) {
                e.put("build_retry.trigger.user", u.getUserId());
            } else if (c instanceof Cause.UpstreamCause u) {
                e.put("build_retry.trigger.upstream", u.getUpstreamProject() + " #" + u.getUpstreamBuild());
            } else if (ClassNames.isA(c, RetryActions.GERRIT_CAUSE)) {
                gerrit(c).forEach(e::put);
            }
        }
        e.put("build_retry.cause_kinds", new ArrayList<>(kinds)).put("build_retry.causes", descriptions);
    }

    static String kind(Cause c) {
        if (c instanceof BuildRetryCause) {
            return "retry";
        } else if (c instanceof Cause.UserIdCause) {
            return "user";
        } else if (c instanceof Cause.UpstreamCause) {
            return "upstream";
        } else if (c instanceof SCMTrigger.SCMTriggerCause) {
            return "scm";
        } else if (c instanceof TimerTrigger.TimerTriggerCause) {
            return "timer";
        } else if (c instanceof Cause.RemoteCause) {
            return "remote";
        } else if (ClassNames.isA(c, RetryActions.GERRIT_CAUSE)) {
            return "gerrit";
        } else if (ClassNames.isA(c, ClassNames.REPLAY_CAUSE)) {
            return "replay";
        } else if (ClassNames.isA(c, RetryActions.RESTART_CAUSE)) {
            return "restart";
        } else if (ClassNames.isA(c, ClassNames.BRANCH_INDEXING_CAUSE)) {
            return "branch-indexing";
        } else if (ClassNames.isA(c, ClassNames.BRANCH_EVENT_CAUSE)) {
            return "branch-event";
        }
        return "other";
    }

    /** The Gerrit event type, change, patch set and project, read by name; empty when any getter is missing. */
    static Map<String, String> gerrit(Object cause) {
        Map<String, String> out = new LinkedHashMap<>();
        Object event = call(cause, "getEvent");
        if (event == null) {
            return out;
        }
        Object type = call(event, "getEventType");
        Object typeValue = call(type, "getTypeValue");
        put(out, "build_retry.trigger.gerrit_event", typeValue != null ? typeValue : type);
        Object change = call(event, "getChange");
        put(out, "build_retry.trigger.gerrit_change", call(change, "getNumber"));
        put(out, "build_retry.trigger.gerrit_project", call(change, "getProject"));
        put(out, "build_retry.trigger.gerrit_patchset", call(call(event, "getPatchSet"), "getNumber"));
        return out;
    }

    private static void put(Map<String, String> out, String key, Object value) {
        if (value != null) {
            out.put(key, String.valueOf(value));
        }
    }

    private static Object call(Object target, String getter) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(getter).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static List<String> terminationReasons(RunSnapshot s) {
        Set<String> out = new TreeSet<>();
        Map<String, List<String>> failed = s.getFailedAgentReasons();
        if (failed != null && !failed.isEmpty()) {
            failed.values().forEach(out::addAll);
        } else if (s.getTerminationReasons() != null) {
            out.addAll(s.getTerminationReasons());
        }
        return new ArrayList<>(out);
    }

    private static List<String> simpleNames(List<String> names) {
        if (names == null) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        names.forEach(n -> out.add(RetryActions.simpleName(n)));
        return new ArrayList<>(out);
    }
}
