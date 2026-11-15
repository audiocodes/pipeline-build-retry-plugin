package io.jenkins.plugins.pipelinebuildretry;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Action;
import hudson.model.Item;
import hudson.model.PeriodicWork;
import hudson.model.Saveable;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.SaveableListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.pipelinebuildretry.core.DelaySpec;
import io.jenkins.plugins.pipelinebuildretry.events.RetryEvent;
import io.jenkins.plugins.pipelinebuildretry.events.RetryEvents;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Sends {@code build_retry.job_config}: which jobs use the plugin now, with their settings (README "Events"). */
@Restricted(NoExternalUse.class)
final class JobConfigEvents {

    static final String JOB_CONFIG = "build_retry.job_config";

    private static final String DECLARATIVE_TRACKER =
            "org.jenkinsci.plugins.pipeline.modeldefinition.actions.DeclarativeJobPropertyTrackerAction";
    private static final String PROPERTIES_TRACKER =
            "org.jenkinsci.plugins.workflow.multibranch.JobPropertyTrackerAction";

    // Settings last seen per configured job's full name; a job without the property has no entry.
    private static final Map<String, Map<String, Object>> KNOWN = new HashMap<>();

    // Jobs whose last event sent says configured, so a full list can report those gone meanwhile.
    private static final Set<String> SENT = new HashSet<>();

    private JobConfigEvents() {}

    private static boolean sending() {
        BuildRetryGlobalConfiguration c = BuildRetryGlobalConfiguration.get();
        return c.isSendEvents() && c.isSendJobConfig();
    }

    /** Re-reads every job and, while events are on, sends one event per job that has the property. */
    static synchronized void snapshot(String why) {
        Map<String, Map<String, Object>> now = new HashMap<>();
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (WorkflowJob job : Jenkins.get().allItems(WorkflowJob.class)) {
                Map<String, Object> s = settings(job);
                if (s != null) {
                    now.put(job.getFullName(), s);
                    send(job.getFullName(), job.getUrl(), s, why);
                }
            }
        }
        // A job gone without an event sent for it, such as one inside a deleted folder, is reported here.
        for (String name : new ArrayList<>(SENT)) {
            if (!now.containsKey(name)) {
                send(name, null, null, "missing");
            }
        }
        KNOWN.clear();
        KNOWN.putAll(now);
    }

    // A save of any kind lands here, including the one options{} and properties() make while a build starts.
    static synchronized void saved(WorkflowJob job) {
        Map<String, Object> s = settings(job);
        String name = job.getFullName();
        if (java.util.Objects.equals(KNOWN.get(name), s)) {
            return;
        }
        if (s == null) {
            KNOWN.remove(name);
        } else {
            KNOWN.put(name, s);
        }
        send(name, job.getUrl(), s, "changed");
    }

    static synchronized void deleted(WorkflowJob job) {
        if (KNOWN.remove(job.getFullName()) != null) {
            send(job.getFullName(), job.getUrl(), null, "deleted");
        }
    }

    static synchronized void moved(WorkflowJob job, String oldFullName) {
        Map<String, Object> s = KNOWN.remove(oldFullName);
        if (s == null) {
            return;
        }
        send(oldFullName, null, null, "renamed");
        if (!s.equals(KNOWN.get(job.getFullName()))) {
            KNOWN.put(job.getFullName(), s);
            send(job.getFullName(), job.getUrl(), s, "renamed");
        }
    }

    private static void send(String name, @CheckForNull String url, @CheckForNull Map<String, Object> s, String why) {
        if (!sending()) {
            return;
        }
        if (s == null) {
            SENT.remove(name);
        } else {
            SENT.add(name);
        }
        RetryEvents.send(JobConfigEvents::sending, () -> event(name, url, s, why));
    }

    private static RetryEvent event(
            String name, @CheckForNull String url, @CheckForNull Map<String, Object> s, String why) {
        RetryEvent e = new RetryEvent(
                        JOB_CONFIG, name + ": " + (s == null ? "buildRetry not set" : summary(s)) + " [" + why + "]")
                .put("ci.pipeline.id", name)
                .put("build_retry.configured", s != null)
                .put("build_retry.snapshot_reason", why);
        String root = Jenkins.get().getRootUrl();
        if (root != null && url != null) {
            e.put("ci.pipeline.url", root + url);
        }
        if (s != null) {
            e.put(
                    "build_retry.retries_enabled",
                    BuildRetryGlobalConfiguration.get().isEnabled());
            s.forEach((k, v) -> put(e, k, v));
        }
        return e;
    }

    @SuppressWarnings("unchecked")
    private static void put(RetryEvent e, String key, Object v) {
        if (v instanceof Boolean b) {
            e.put(key, b.booleanValue());
        } else if (v instanceof Long l) {
            e.put(key, l.longValue());
        } else if (v instanceof java.util.List<?> list) {
            e.put(key, (java.util.List<String>) list);
        } else if (v != null) {
            e.put(key, String.valueOf(v));
        }
    }

    private static String summary(Map<String, Object> s) {
        StringBuilder sb = new StringBuilder("buildRetry max ")
                .append(s.get("build_retry.max_retries"))
                .append(", ")
                .append(s.get("build_retry.delay"))
                .append(", rerunIfAborted ")
                .append(s.get("build_retry.rerun_if_aborted"));
        if (Boolean.TRUE.equals(s.get("build_retry.dry_run"))) {
            sb.append(", dry run");
        }
        if (Boolean.TRUE.equals(s.get("build_retry.job_disabled"))) {
            sb.append(", job disabled");
        }
        return sb.toString();
    }

    // The values the event sends, also the fingerprint that tells a real change from any other save.
    @CheckForNull
    static Map<String, Object> settings(WorkflowJob job) {
        BuildRetryJobProperty p = job.getProperty(BuildRetryJobProperty.class);
        if (p == null) {
            return null;
        }
        DelaySpec delay = p.toOptions().getDelay();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("build_retry.set_by", setBy(job));
        s.put("build_retry.job_disabled", job.isDisabled());
        s.put("build_retry.max_retries", (long) p.getMaxSchedule());
        s.put("build_retry.delay_type", delay.typeName());
        s.put("build_retry.delay", delay.toString());
        s.put("build_retry.rerun_if_unstable", p.isRerunIfUnstable());
        s.put("build_retry.rerun_if_aborted", p.getRerunIfAborted());
        s.put("build_retry.rerun_on_agent_loss", p.isRerunOnAgentLoss());
        s.put("build_retry.custom_agent_loss_patterns", p.getAgentLossPatterns() != null);
        s.put("build_retry.extra_agent_loss_patterns", (long)
                p.getAgentLossPatternsExtra().size());
        s.put("build_retry.check_regexp", p.isCheckRegexp());
        String regexp = p.getRegexpForRerun();
        s.put("build_retry.regexp_set", !regexp.isEmpty());
        s.put("build_retry.regexp_scope", p.getRegexpScope());
        s.put("build_retry.never_retry_count", (long) p.getNeverRetryPatterns().size());
        s.put("build_retry.gerrit_cause", p.getGerritCause());
        s.put("build_retry.drop_causes", (long) p.getDropCauses().size());
        s.put(
                "build_retry.parameter_overrides",
                new ArrayList<>(p.getParameterOverrides().keySet()));
        s.put("build_retry.skip_replays", p.isSkipReplays());
        s.put("build_retry.badge", p.isBadge());
        s.put("build_retry.dry_run", p.isDryRun());
        s.put("build_retry.verbose", p.isVerbose());
        return s;
    }

    // Read by class name: neither tracker's plugin is a dependency.
    private static String setBy(WorkflowJob job) {
        String name = BuildRetryJobProperty.class.getName();
        for (Action a : job.getActions()) {
            String cls = a.getClass().getName();
            if (cls.equals(DECLARATIVE_TRACKER) && tracked(a, "getJobProperties", name)) {
                return "declarative-options";
            }
            if (cls.equals(PROPERTIES_TRACKER) && tracked(a, "getJobPropertyDescriptors", name)) {
                return "properties-step";
            }
        }
        return "job-config";
    }

    private static boolean tracked(Object action, String getter, String name) {
        try {
            Object v = action.getClass().getMethod(getter).invoke(action);
            return v instanceof Set<?> set && set.contains(name);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Sends the full list once all jobs are loaded, and follows deletes and renames. */
    @Extension
    @Restricted(NoExternalUse.class)
    public static class Items extends ItemListener {
        @Override
        public void onLoaded() {
            snapshot("startup");
        }

        @Override
        public void onDeleted(Item item) {
            if (item instanceof WorkflowJob job) {
                deleted(job);
            }
        }

        @Override
        public void onLocationChanged(Item item, String oldFullName, String newFullName) {
            if (item instanceof WorkflowJob job) {
                moved(job, oldFullName);
            }
        }
    }

    /** Follows every job save, so a change made on the job page or by a Jenkinsfile is sent at once. */
    @Extension
    @Restricted(NoExternalUse.class)
    public static class Saves extends SaveableListener {
        @Override
        public void onChange(Saveable o, XmlFile file) {
            if (o instanceof WorkflowJob job) {
                saved(job);
            }
        }
    }

    /** Sends the full list every hour, so a dashboard reading the last hour always has every job. */
    @Extension
    @Restricted(NoExternalUse.class)
    public static class Hourly extends PeriodicWork {
        @Override
        public long getRecurrencePeriod() {
            return HOUR;
        }

        @Override
        protected void doRun() {
            if (sending()) {
                snapshot("hourly");
            }
        }
    }
}
