package io.jenkins.plugins.pipelinebuildretry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.ExtensionList;
import hudson.model.PeriodicWork;
import io.jenkins.plugins.pipelinebuildretry.delay.FixedDelay;
import io.jenkins.plugins.pipelinebuildretry.events.RetryEvent;
import io.jenkins.plugins.pipelinebuildretry.events.RetryEvents;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class JobConfigEventsTest {

    static final List<RetryEvent> EVENTS = new CopyOnWriteArrayList<>();

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        EVENTS.clear();
    }

    private static List<RetryEvent> configEvents() {
        return EVENTS.stream()
                .filter(e -> e.getName().equals(JobConfigEvents.JOB_CONFIG))
                .collect(Collectors.toList());
    }

    private static List<RetryEvent> configEventsOf(String job) {
        return configEvents().stream()
                .filter(e -> job.equals(e.getAttributes().get("ci.pipeline.id")))
                .collect(Collectors.toList());
    }

    private static BuildRetryJobProperty property() {
        BuildRetryJobProperty p = new BuildRetryJobProperty();
        p.setMaxSchedule(2);
        p.setDelay(new FixedDelay(60));
        p.setRerunIfAborted("infra");
        p.setRerunOnAgentLoss(true);
        return p;
    }

    private void sending() {
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        BuildRetryGlobalConfiguration.get().setSendJobConfig(true);
        EVENTS.clear();
    }

    private static List<RetryEvent> switchEvents() {
        return EVENTS.stream()
                .filter(e -> e.getName().equals(BuildRetryEvents.SWITCH_CHANGED))
                .collect(Collectors.toList());
    }

    @Test
    void aSnapshotSendsOneEventPerConfiguredJob() throws Exception {
        WorkflowJob configured = j.createProject(WorkflowJob.class, "configured");
        configured.addProperty(property());
        j.createProject(WorkflowJob.class, "plain");
        sending();
        ExtensionList.lookupSingleton(JobConfigEvents.Items.class).onLoaded();
        List<RetryEvent> events = configEvents();
        assertEquals(1, events.size(), "only the configured job: " + events);
        Map<String, Object> a = events.get(0).getAttributes();
        assertEquals("configured", a.get("ci.pipeline.id"));
        assertEquals(true, a.get("build_retry.configured"));
        assertEquals("startup", a.get("build_retry.snapshot_reason"));
        assertEquals(2L, a.get("build_retry.max_retries"));
        assertEquals("fixed", a.get("build_retry.delay_type"));
        assertEquals("fixed 60s", a.get("build_retry.delay"));
        assertEquals("infra", a.get("build_retry.rerun_if_aborted"));
        assertEquals(true, a.get("build_retry.rerun_on_agent_loss"));
        assertEquals(false, a.get("build_retry.dry_run"));
        assertEquals("drop", a.get("build_retry.gerrit_cause"));
        assertEquals(true, a.get("build_retry.retries_enabled"));
        assertEquals(false, a.get("build_retry.job_disabled"));
        assertEquals("job-config", a.get("build_retry.set_by"));
        assertEquals(j.getURL() + "job/configured/", a.get("ci.pipeline.url"));
        assertEquals(false, a.get("build_retry.regexp_set"));
        assertFalse(a.containsKey("build_retry.regexp_hash"));
        assertEquals(0L, a.get("build_retry.never_retry_count"));
    }

    @Test
    void neverRetryPatternsAreSentOnlyAsACount() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "denied");
        BuildRetryJobProperty prop = property();
        prop.setNeverRetryPatterns(List.of("COMPILATION ERROR", "secret-deny-[0-9]+"));
        p.addProperty(prop);
        sending();
        JobConfigEvents.snapshot("hourly");
        RetryEvent e = configEventsOf("denied").get(0);
        assertEquals(2L, e.getAttributes().get("build_retry.never_retry_count"));
        String all = e.getAttributes().values() + e.getBody();
        assertFalse(all.contains("secret-deny") || all.contains("COMPILATION"), "no pattern text: " + all);
    }

    @Test
    void theRegexpIsNeverSentNotEvenAsAHash() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "gated");
        BuildRetryJobProperty prop = property();
        prop.setCheckRegexp(true);
        prop.setRegexpForRerun("secret-token-[0-9]+");
        p.addProperty(prop);
        sending();
        JobConfigEvents.snapshot("hourly");
        Map<String, Object> a = configEventsOf("gated").get(0).getAttributes();
        assertEquals(true, a.get("build_retry.check_regexp"));
        assertEquals(true, a.get("build_retry.regexp_set"));
        assertFalse(a.containsKey("build_retry.regexp_hash"), "no hash of the regexp either");
        String all = a.values() + configEventsOf("gated").get(0).getBody();
        assertFalse(all.contains("secret-token"), "no regexp text: " + all);
    }

    @Test
    void aSaveSendsAnEventOnlyWhenTheSettingsChange() throws Exception {
        sending();
        WorkflowJob p = j.createProject(WorkflowJob.class, "edited");
        assertEquals(List.of(), configEvents(), "a job without the property sends nothing");
        BuildRetryJobProperty prop = property();
        p.addProperty(prop);
        assertEquals(1, configEventsOf("edited").size(), "added");
        assertEquals("changed", configEventsOf("edited").get(0).getAttributes().get("build_retry.snapshot_reason"));
        p.save();
        p.setDescription("no retry setting changed");
        assertEquals(1, configEventsOf("edited").size(), "unchanged saves send nothing");
        prop.setDryRun(true);
        p.save();
        assertEquals(2, configEventsOf("edited").size(), "dry run turned on");
        assertEquals(true, configEventsOf("edited").get(1).getAttributes().get("build_retry.dry_run"));
        p.makeDisabled(true);
        assertEquals(3, configEventsOf("edited").size(), "job disabled");
        assertEquals(true, configEventsOf("edited").get(2).getAttributes().get("build_retry.job_disabled"));
        p.removeProperty(BuildRetryJobProperty.class);
        List<RetryEvent> events = configEventsOf("edited");
        assertEquals(4, events.size(), "removed");
        assertEquals(false, events.get(3).getAttributes().get("build_retry.configured"));
        assertNull(events.get(3).getAttributes().get("build_retry.max_retries"), "no settings on a removal");
        p.save();
        assertEquals(4, configEventsOf("edited").size(), "nothing more once removed");
    }

    @Test
    void settingsFromTheJenkinsfileAreSentOnceAndNamedAsSuch() throws Exception {
        sending();
        WorkflowJob declarative = j.createProject(WorkflowJob.class, "declarative");
        declarative.setDefinition(new CpsFlowDefinition(
                "pipeline { agent none\n options { buildRetry(maxSchedule: 1, delay: fixedDelay(0)) }\n"
                        + " stages { stage('a') { steps { echo 'hi' } } } }",
                true));
        WorkflowJob scripted = j.createProject(WorkflowJob.class, "scripted");
        scripted.setDefinition(new CpsFlowDefinition(
                "properties([buildRetry(maxSchedule: 1, delay: fixedDelay(0))])\necho 'hi'", true));
        for (WorkflowJob p : List.of(declarative, scripted)) {
            j.buildAndAssertSuccess(p);
            j.buildAndAssertSuccess(p);
        }
        List<RetryEvent> d = configEventsOf("declarative");
        assertEquals("declarative-options", d.get(d.size() - 1).getAttributes().get("build_retry.set_by"), "" + d);
        assertTrue(d.size() <= 2, "the second build sends nothing new: " + d);
        List<RetryEvent> s = configEventsOf("scripted");
        assertEquals("properties-step", s.get(s.size() - 1).getAttributes().get("build_retry.set_by"), "" + s);
        assertTrue(s.size() <= 2, "the second build sends nothing new: " + s);
        int before = configEvents().size();
        WorkflowRun third = j.buildAndAssertSuccess(declarative);
        assertEquals(before, configEvents().size(), "#" + third.getNumber() + " changed nothing");
    }

    @Test
    void aDeletedOrRenamedJobIsReported() throws Exception {
        WorkflowJob gone = j.createProject(WorkflowJob.class, "gone");
        gone.addProperty(property());
        WorkflowJob moved = j.createProject(WorkflowJob.class, "old-name");
        moved.addProperty(property());
        sending();
        gone.delete();
        List<RetryEvent> g = configEventsOf("gone");
        assertEquals(1, g.size(), "" + g);
        assertEquals(false, g.get(0).getAttributes().get("build_retry.configured"));
        assertEquals("deleted", g.get(0).getAttributes().get("build_retry.snapshot_reason"));
        moved.renameTo("new-name");
        List<RetryEvent> old = configEventsOf("old-name");
        assertEquals(1, old.size(), "" + old);
        assertEquals(false, old.get(0).getAttributes().get("build_retry.configured"));
        List<RetryEvent> renamed = configEventsOf("new-name");
        assertEquals(1, renamed.size(), "" + renamed);
        assertEquals(true, renamed.get(0).getAttributes().get("build_retry.configured"));
    }

    @Test
    void aJobGoneWithoutItsOwnEventIsReportedByTheNextSnapshot() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "vanished");
        p.addProperty(property());
        sending();
        JobConfigEvents.snapshot("hourly");
        j.jenkins.remove(p);
        assertEquals(1, configEventsOf("vanished").size(), "no listener fired");
        JobConfigEvents.snapshot("hourly");
        JobConfigEvents.snapshot("hourly");
        List<RetryEvent> events = configEventsOf("vanished");
        assertEquals(2, events.size(), "one missing event, then nothing: " + events);
        assertEquals(false, events.get(1).getAttributes().get("build_retry.configured"));
        assertEquals("missing", events.get(1).getAttributes().get("build_retry.snapshot_reason"));
    }

    @Test
    void turningEventsOnSendsASnapshotAndNothingIsSentWhileOff() throws Exception {
        BuildRetryGlobalConfiguration.get().setSendJobConfig(true);
        WorkflowJob p = j.createProject(WorkflowJob.class, "quiet");
        p.addProperty(property());
        JobConfigEvents.snapshot("hourly");
        ExtensionList.lookupSingleton(JobConfigEvents.Hourly.class).run();
        p.removeProperty(BuildRetryJobProperty.class);
        p.addProperty(property());
        assertEquals(List.of(), configEvents(), "events are off");
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        List<RetryEvent> on = configEventsOf("quiet");
        assertEquals(1, on.size(), "" + on);
        assertEquals("events-on", on.get(0).getAttributes().get("build_retry.snapshot_reason"));
        BuildRetryGlobalConfiguration.get().setEnabled(false);
        List<RetryEvent> off = configEventsOf("quiet");
        assertEquals(2, off.size(), "retries turned off sends a new snapshot: " + off);
        assertEquals(false, off.get(1).getAttributes().get("build_retry.retries_enabled"));
    }

    @Test
    void theJobListIsOffByDefaultEvenWithEventsOn() throws Exception {
        assertFalse(BuildRetryGlobalConfiguration.get().isSendJobConfig(), "off by default");
        WorkflowJob p = j.createProject(WorkflowJob.class, "unlisted");
        p.addProperty(property());
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        JobConfigEvents.snapshot("hourly");
        ExtensionList.lookupSingleton(JobConfigEvents.Hourly.class).run();
        p.removeProperty(BuildRetryJobProperty.class);
        p.addProperty(property());
        BuildRetryGlobalConfiguration.get().setEnabled(false);
        p.delete();
        assertEquals(List.of(), configEvents(), "no job list while the checkbox is off");
    }

    @Test
    void tickingTheCheckboxSendsTheListAtOnce() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "listed");
        p.addProperty(property());
        BuildRetryGlobalConfiguration.get().setSendEvents(true);
        EVENTS.clear();
        BuildRetryGlobalConfiguration.get().setSendJobConfig(true);
        List<RetryEvent> on = configEventsOf("listed");
        assertEquals(1, on.size(), "" + on);
        assertEquals("job-config-on", on.get(0).getAttributes().get("build_retry.snapshot_reason"));
        assertEquals(true, on.get(0).getAttributes().get("build_retry.configured"));
        List<RetryEvent> sw = switchEvents();
        assertEquals(1, sw.size(), "" + sw);
        assertEquals("sendJobConfig", sw.get(0).getAttributes().get("build_retry.setting"));
        assertEquals(true, sw.get(0).getAttributes().get("build_retry.new_value"));
        assertTrue(sw.get(0).getAttributes().containsKey("build_retry.changed_by"), "names who changed it");
    }

    @Test
    void untickingStopsTheListWithoutRemovingAnyJob() throws Exception {
        WorkflowJob kept = j.createProject(WorkflowJob.class, "kept");
        kept.addProperty(property());
        WorkflowJob gone = j.createProject(WorkflowJob.class, "gone-while-off");
        gone.addProperty(property());
        sending();
        BuildRetryGlobalConfiguration.get().setSendJobConfig(false);
        assertEquals(List.of(), configEvents(), "no configured=false events");
        List<RetryEvent> sw = switchEvents();
        assertEquals(1, sw.size(), "" + sw);
        assertEquals(false, sw.get(0).getAttributes().get("build_retry.new_value"));
        kept.removeProperty(BuildRetryJobProperty.class);
        kept.addProperty(property());
        gone.delete();
        ExtensionList.lookupSingleton(JobConfigEvents.Hourly.class).run();
        assertEquals(List.of(), configEvents(), "nothing sent while off");
    }

    @Test
    void aJobGoneWhileOffIsMarkedMissingWhenTheCheckboxIsTickedAgain() throws Exception {
        WorkflowJob kept = j.createProject(WorkflowJob.class, "stays");
        kept.addProperty(property());
        WorkflowJob gone = j.createProject(WorkflowJob.class, "deleted-while-off");
        gone.addProperty(property());
        WorkflowJob moved = j.createProject(WorkflowJob.class, "renamed-while-off");
        moved.addProperty(property());
        WorkflowJob dropped = j.createProject(WorkflowJob.class, "unset-while-off");
        dropped.addProperty(property());
        sending();
        BuildRetryGlobalConfiguration.get().setSendJobConfig(false);
        gone.delete();
        moved.renameTo("renamed-now");
        dropped.removeProperty(BuildRetryJobProperty.class);
        BuildRetryGlobalConfiguration.get().setSendJobConfig(true);
        assertEquals("job-config-on", reasonOf("stays"));
        assertEquals("job-config-on", reasonOf("renamed-now"));
        for (String name : List.of("deleted-while-off", "renamed-while-off", "unset-while-off")) {
            List<RetryEvent> e = configEventsOf(name);
            assertEquals(1, e.size(), name + ": " + e);
            assertEquals(false, e.get(0).getAttributes().get("build_retry.configured"), name);
            assertEquals("missing", e.get(0).getAttributes().get("build_retry.snapshot_reason"), name);
        }
        EVENTS.clear();
        JobConfigEvents.snapshot("hourly");
        assertEquals(2, configEvents().size(), "missing is sent once: " + configEvents());
    }

    private static String reasonOf(String job) {
        List<RetryEvent> e = configEventsOf(job);
        assertEquals(1, e.size(), job + ": " + e);
        assertEquals(true, e.get(0).getAttributes().get("build_retry.configured"), job);
        return String.valueOf(e.get(0).getAttributes().get("build_retry.snapshot_reason"));
    }

    @Test
    void theSnapshotRunsHourly() throws Exception {
        PeriodicWork hourly = ExtensionList.lookupSingleton(JobConfigEvents.Hourly.class);
        assertEquals(java.util.concurrent.TimeUnit.HOURS.toMillis(1), hourly.getRecurrencePeriod());
        WorkflowJob p = j.createProject(WorkflowJob.class, "ticking");
        p.addProperty(property());
        sending();
        hourly.run();
        hourly.run();
        List<RetryEvent> events = configEventsOf("ticking");
        assertEquals(2, events.size(), "every run sends the full list again: " + events);
        assertEquals("hourly", events.get(0).getAttributes().get("build_retry.snapshot_reason"));
    }

    @TestExtension
    public static class Capture implements RetryEvents {
        @Override
        public void emit(RetryEvent event) {
            EVENTS.add(event);
        }
    }
}
