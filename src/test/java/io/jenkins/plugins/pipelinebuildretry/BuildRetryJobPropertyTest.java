package io.jenkins.plugins.pipelinebuildretry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.ExtensionList;
import hudson.model.Descriptor;
import hudson.model.FreeStyleProject;
import hudson.model.Items;
import hudson.model.JobProperty;
import hudson.model.Result;
import hudson.util.FormValidation;
import io.jenkins.plugins.pipelinebuildretry.core.DelaySpec;
import io.jenkins.plugins.pipelinebuildretry.core.RetryOptions;
import io.jenkins.plugins.pipelinebuildretry.delay.FixedDelay;
import io.jenkins.plugins.pipelinebuildretry.delay.ListDelay;
import io.jenkins.plugins.pipelinebuildretry.delay.ProgressiveDelay;
import io.jenkins.plugins.pipelinebuildretry.delay.RetryDelay;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.structs.SymbolLookup;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.Stapler;

@WithJenkins
class BuildRetryJobPropertyTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    private WorkflowJob job(String script) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(script, true));
        return p;
    }

    private static String declarative(String option) {
        return "pipeline {\n  agent none\n  options { " + option + " }\n"
                + "  stages { stage('one') { steps { echo 'hi' } } }\n}\n";
    }

    private BuildRetryJobProperty.DescriptorImpl descriptor() {
        return j.jenkins.getDescriptorByType(BuildRetryJobProperty.DescriptorImpl.class);
    }

    private static BuildRetryJobProperty fullProperty() {
        BuildRetryJobProperty p = new BuildRetryJobProperty();
        p.setMaxSchedule(4);
        p.setRerunIfUnstable(true);
        p.setDelay(new ListDelay(List.of(15, 45)));
        p.setCheckRegexp(true);
        p.setRegexpForRerun("Connection (reset|refused)");
        p.setRegexpTimeoutMs(2500);
        p.setRegexpScope("failedStages");
        p.setNeverRetryPatterns(List.of("COMPILATION ERROR", "error: cannot find symbol"));
        p.setRerunIfAborted("infraOrTimeout");
        p.setRerunOnAgentLoss(true);
        p.setAgentLossPatterns(List.of("agent went away", "pod .* deleted"));
        p.setAgentLossPatternsExtra(List.of("node is offline"));
        p.setSkipReplays(false);
        p.setGerritCause("keep");
        p.setDropCauses(List.of("hudson.model.Cause$UserIdCause"));
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("SKIP_TESTS", true);
        overrides.put("LEVEL", 3);
        p.setParameterOverrides(overrides);
        p.setBadge(false);
        p.setDryRun(true);
        p.setVerbose(true);
        return p;
    }

    private static void assertSameOptions(BuildRetryJobProperty a, BuildRetryJobProperty b) {
        assertEquals(a.getMaxSchedule(), b.getMaxSchedule(), "maxSchedule");
        assertEquals(a.isRerunIfUnstable(), b.isRerunIfUnstable(), "rerunIfUnstable");
        assertEquals(a.getDelay(), b.getDelay(), "delay");
        assertEquals(a.isCheckRegexp(), b.isCheckRegexp(), "checkRegexp");
        assertEquals(a.getRegexpForRerun(), b.getRegexpForRerun(), "regexpForRerun");
        assertEquals(a.getRegexpTimeoutMs(), b.getRegexpTimeoutMs(), "regexpTimeoutMs");
        assertEquals(a.getRegexpScope(), b.getRegexpScope(), "regexpScope");
        assertEquals(a.getNeverRetryPatterns(), b.getNeverRetryPatterns(), "neverRetryPatterns");
        assertEquals(a.getRerunIfAborted(), b.getRerunIfAborted(), "rerunIfAborted");
        assertEquals(a.isRerunOnAgentLoss(), b.isRerunOnAgentLoss(), "rerunOnAgentLoss");
        assertEquals(a.getAgentLossPatterns(), b.getAgentLossPatterns(), "agentLossPatterns");
        assertEquals(a.getAgentLossPatternsExtra(), b.getAgentLossPatternsExtra(), "agentLossPatternsExtra");
        assertEquals(a.isSkipReplays(), b.isSkipReplays(), "skipReplays");
        assertEquals(a.getGerritCause(), b.getGerritCause(), "gerritCause");
        assertEquals(a.getDropCauses(), b.getDropCauses(), "dropCauses");
        assertEquals(
                new ArrayList<>(a.getParameterOverrides().entrySet()),
                new ArrayList<>(b.getParameterOverrides().entrySet()),
                "parameterOverrides, in order");
        assertEquals(a.isBadge(), b.isBadge(), "badge");
        assertEquals(a.isDryRun(), b.isDryRun(), "dryRun");
        assertEquals(a.isVerbose(), b.isVerbose(), "verbose");
    }

    @Test
    void scriptedBindsEveryOption() throws Exception {
        WorkflowJob p = job("""
                properties([buildRetry(maxSchedule: 4, rerunIfUnstable: true, delay: listDelay([15, 45]),
                    checkRegexp: true, regexpForRerun: 'Connection (reset|refused)', regexpTimeoutMs: 2500,
                    regexpScope: 'failedStages', neverRetryPatterns: ['COMPILATION ERROR', 'error: cannot find symbol'],
                    rerunIfAborted: 'infraOrTimeout', rerunOnAgentLoss: true,
                    agentLossPatterns: ['agent went away', 'pod .* deleted'], agentLossPatternsExtra: ['node is offline'],
                    skipReplays: false, gerritCause: 'keep', dropCauses: ['hudson.model.Cause$UserIdCause'],
                    parameterOverrides: [SKIP_TESTS: true, LEVEL: 3], badge: false, dryRun: true, verbose: true)])
                """);
        j.buildAndAssertSuccess(p);
        BuildRetryJobProperty prop = p.getProperty(BuildRetryJobProperty.class);
        assertNotNull(prop, "properties step set the property");
        assertSameOptions(fullProperty(), prop);
        assertEquals("true", prop.getParameterOverrides().get("SKIP_TESTS"), "boolean override kept as text");
        assertEquals("3", prop.getParameterOverrides().get("LEVEL"), "number override kept as text");
    }

    @Test
    void scriptedDefaultsAndPositionalDelay() throws Exception {
        WorkflowJob p = job("properties([buildRetry(delay: fixedDelay(30))])");
        j.buildAndAssertSuccess(p);
        BuildRetryJobProperty prop = p.getProperty(BuildRetryJobProperty.class);
        assertEquals(new FixedDelay(30), prop.getDelay(), "single-argument delay binds positionally");
        RetryOptions o = prop.toOptions();
        assertEquals(DelaySpec.fixed(30), o.getDelay(), "delay reaches the options");
        assertEquals(List.of(), o.validate().errors(), "defaults validate");
        assertNull(prop.getAgentLossPatterns(), "built-in agent-loss patterns stay implicit");
        assertTrue(prop.isSkipReplays(), "skipReplays defaults on");
        assertTrue(prop.isBadge(), "badge defaults on");
        assertEquals("never", prop.getRerunIfAborted(), "rerunIfAborted defaults to never");

        WorkflowJob bare = job("properties([buildRetry()])");
        j.buildAndAssertSuccess(bare);
        BuildRetryJobProperty none = bare.getProperty(BuildRetryJobProperty.class);
        assertNull(none.getDelay(), "no delay given");
        assertEquals(DelaySpec.defaultDelay(), none.toOptions().getDelay(), "no delay means progressive 300/10800");
    }

    @Test
    void declarativeBindsNestedDelayAndMap() throws Exception {
        WorkflowJob p = job(declarative("buildRetry(maxSchedule: 2, delay: progressiveDelay(increment: 60, max: 600),"
                + " parameterOverrides: [SKIP_TESTS: true, LEVEL: 3, MODE: 'quick'])"));
        j.buildAndAssertSuccess(p);
        BuildRetryJobProperty prop = p.getProperty(BuildRetryJobProperty.class);
        assertNotNull(prop, "options block set the property");
        assertEquals(2, prop.getMaxSchedule(), "maxSchedule");
        assertEquals(new ProgressiveDelay(60, 600), prop.getDelay(), "nested delay");
        assertEquals(
                List.of("SKIP_TESTS", "LEVEL", "MODE"),
                new ArrayList<>(prop.getParameterOverrides().keySet()),
                "map keeps its order");
        assertEquals("quick", prop.getParameterOverrides().get("MODE"), "string override");
    }

    @Test
    void declarativeListDelay() throws Exception {
        WorkflowJob p = job(declarative("buildRetry(delay: listDelay([5, 10, 20]))"));
        j.buildAndAssertSuccess(p);
        assertEquals(
                new ListDelay(List.of(5, 10, 20)),
                p.getProperty(BuildRetryJobProperty.class).getDelay(),
                "list delay");
    }

    @Test
    void unknownOptionFailsBothSyntaxes() throws Exception {
        WorkflowJob s = job("properties([buildRetry(maxSchedule: 1, maxRetries: 2)])");
        WorkflowRun rs = j.buildAndAssertStatus(Result.FAILURE, s);
        j.assertLogContains("Unknown parameter(s) found", rs);
        j.assertLogContains("maxRetries", rs);
        assertNull(s.getProperty(BuildRetryJobProperty.class), "Scripted: nothing set");

        WorkflowJob d = job(declarative("buildRetry(maxSchedule: 1, maxRetries: 2)"));
        WorkflowRun rd = j.buildAndAssertStatus(Result.FAILURE, d);
        j.assertLogContains("Invalid parameter \"maxRetries\"", rd);
        assertNull(d.getProperty(BuildRetryJobProperty.class), "Declarative: nothing set");
    }

    @Test
    void invalidValuesFailTheBuildWithTheOptionName() throws Exception {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("rerunIfAborted: 'sometimes'", "buildRetry: rerunIfAborted must be one of never, infra, timeout");
        cases.put("regexpScope: 'all'", "buildRetry: regexpScope must be one of build, failedStages");
        cases.put("gerritCause: 'maybe'", "buildRetry: gerritCause must be one of drop, keep");
        cases.put("regexpForRerun: 'oops-('", "buildRetry: regexpForRerun is not a valid regular expression");
        cases.put("regexpTimeoutMs: 0", "buildRetry: regexpTimeoutMs must be at least 1");
        cases.put("agentLossPatternsExtra: ['ok', '[']", "buildRetry: agentLossPatternsExtra #2 is not a valid");
        cases.put("neverRetryPatterns: ['ok', '[']", "buildRetry: neverRetryPatterns #2 is not a valid");
        cases.put(
                "neverRetryPatterns: [" + String.join(", ", java.util.Collections.nCopies(21, "'x'")) + "]",
                "buildRetry: neverRetryPatterns has 21 patterns; at most 20 are allowed");
        cases.put("parameterOverrides: [A: [1, 2]]", "buildRetry: parameterOverrides.A must be a string");
        cases.put("delay: fixedDelay(-1)", "fixedDelay: delay must be at least 0");
        cases.put("delay: progressiveDelay(increment: -5, max: 10)", "progressiveDelay: increment and max");
        cases.put("delay: listDelay([])", "listDelay: seconds must be a non-empty list");
        cases.put("delay: listDelay([10, -1])", "listDelay: every value in seconds must be at least 0");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            WorkflowJob p = job("properties([buildRetry(" + c.getKey() + ")])");
            WorkflowRun r = j.buildAndAssertStatus(Result.FAILURE, p);
            j.assertLogContains(c.getValue(), r);
            assertNull(p.getProperty(BuildRetryJobProperty.class), c.getKey() + " sets nothing");
        }
    }

    @Test
    void appliesToPipelineJobsOnly() {
        assertTrue(descriptor().isApplicable(WorkflowJob.class), "Pipeline");
        assertFalse(descriptor().isApplicable(FreeStyleProject.class), "Freestyle");
    }

    @Test
    void jobPageRoundTripKeepsEveryOption() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        BuildRetryJobProperty before = fullProperty();
        p.addProperty(before);
        j.configRoundtrip(p);
        BuildRetryJobProperty after = p.getProperty(BuildRetryJobProperty.class);
        assertNotSame(before, after, "the form built a new property");
        assertSameOptions(before, after);
    }

    @Test
    void jobPageRoundTripKeepsDefaults() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        BuildRetryJobProperty before = new BuildRetryJobProperty();
        before.setDelay(new ProgressiveDelay(300, 10800));
        p.addProperty(before);
        j.configRoundtrip(p);
        BuildRetryJobProperty after = p.getProperty(BuildRetryJobProperty.class);
        assertSameOptions(before, after);
        assertNull(after.getAgentLossPatterns(), "untouched built-in patterns stay implicit");
        assertNull(after.getRegexpTimeoutMs(), "empty timeout means the global default");
    }

    @Test
    void jobPageTextFieldsBecomeOptions() throws Exception {
        BuildRetryJobProperty.DescriptorImpl d = descriptor();
        JSONObject form = new JSONObject()
                .element("specified", true)
                .element("maxSchedule", 2)
                .element("regexpTimeoutMs", " ")
                .element("agentLossPatternsText", "agent lost\n\n  \r\nchannel closed\r\n")
                .element("agentLossPatternsExtraText", "")
                .element("neverRetryPatternsText", "COMPILATION ERROR\n\n  \r\nerror: cannot find symbol\r\n")
                .element("dropCausesText", "a.B\n")
                .element("parameterOverridesText", "A=1\nB=x=y\n\nC=\n");
        BuildRetryJobProperty prop = j.executeOnServer(() -> d.newInstance(Stapler.getCurrentRequest2(), form));
        assertEquals(2, prop.getMaxSchedule(), "plain fields still bind");
        assertNull(prop.getRegexpTimeoutMs(), "blank timeout means the global default");
        assertEquals(List.of("agent lost", "channel closed"), prop.getAgentLossPatterns(), "blank lines dropped");
        assertEquals(List.of(), prop.getAgentLossPatternsExtra(), "empty extra list");
        assertEquals(List.of("a.B"), prop.getDropCauses(), "drop causes");
        assertEquals(
                List.of("COMPILATION ERROR", "error: cannot find symbol"),
                prop.getNeverRetryPatterns(),
                "never-retry lines, blank lines dropped");
        assertEquals(
                List.of(Map.entry("A", "1"), Map.entry("B", "x=y"), Map.entry("C", "")),
                new ArrayList<>(prop.getParameterOverrides().entrySet()),
                "NAME=value lines, split at the first '='");

        JSONObject unchecked = new JSONObject().element("specified", false).element("maxSchedule", 2);
        assertNull(
                j.executeOnServer(() -> d.newInstance(Stapler.getCurrentRequest2(), unchecked)),
                "unchecked block means no property");

        JSONObject badOverride = JSONObject.fromObject(form).element("parameterOverridesText", "=1");
        Descriptor.FormException e1 = assertThrows(
                Descriptor.FormException.class,
                () -> j.executeOnServer(() -> d.newInstance(Stapler.getCurrentRequest2(), badOverride)));
        assertEquals("Each line must be NAME=value", e1.getMessage(), "override line error");

        JSONObject badPattern = JSONObject.fromObject(form).element("agentLossPatternsExtraText", "ok\n(oops");
        Descriptor.FormException e2 = assertThrows(
                Descriptor.FormException.class,
                () -> j.executeOnServer(() -> d.newInstance(Stapler.getCurrentRequest2(), badPattern)));
        assertTrue(e2.getMessage().startsWith("buildRetry: agentLossPatternsExtra #2 is not a valid"), e2.getMessage());
        assertFalse(e2.getMessage().contains("(oops"), "the pattern text is never echoed");

        JSONObject badNever = JSONObject.fromObject(form).element("neverRetryPatternsText", "ok\n(SECRET");
        Descriptor.FormException e3 = assertThrows(
                Descriptor.FormException.class,
                () -> j.executeOnServer(() -> d.newInstance(Stapler.getCurrentRequest2(), badNever)));
        assertTrue(e3.getMessage().startsWith("buildRetry: neverRetryPatterns #2 is not a valid"), e3.getMessage());
        assertFalse(e3.getMessage().contains("SECRET"), "the pattern text is never echoed");
    }

    @Test
    void theNeverRetryFieldChecksEachLineAndTheCap() throws Exception {
        WorkflowJob p = job("echo 'ok'");
        BuildRetryJobProperty.DescriptorImpl d = descriptor();
        assertEquals(FormValidation.Kind.OK, d.doCheckNeverRetryPatternsText(p, "COMPILATION ERROR\n\n").kind, "valid");
        assertEquals(FormValidation.Kind.OK, d.doCheckNeverRetryPatternsText(p, "").kind, "empty");
        FormValidation bad = d.doCheckNeverRetryPatternsText(p, "ok\n(SECRET");
        assertEquals(FormValidation.Kind.ERROR, bad.kind, "bad pattern");
        assertFalse(bad.renderHtml().contains("SECRET"), "the pattern text is never echoed");
        FormValidation many =
                d.doCheckNeverRetryPatternsText(p, String.join("\n", java.util.Collections.nCopies(21, "x")));
        assertEquals(FormValidation.Kind.ERROR, many.kind, "over the cap");
        assertTrue(many.getMessage().contains("at most 20"), many.getMessage());
        assertEquals(
                FormValidation.Kind.OK,
                d.doCheckNeverRetryPatternsText(p, String.join("\n", java.util.Collections.nCopies(20, "x"))).kind,
                "exactly the cap");
    }

    @Test
    void theRetryLimitFieldWarnsThatZeroIsUnlimited() throws Exception {
        WorkflowJob p = job("echo 'ok'");
        BuildRetryJobProperty.DescriptorImpl d = descriptor();
        FormValidation zero = d.doCheckMaxSchedule(p, "0");
        assertEquals(FormValidation.Kind.WARNING, zero.kind, "the page default");
        assertTrue(zero.getMessage().contains("unlimited"), zero.getMessage());
        assertEquals(FormValidation.Kind.WARNING, d.doCheckMaxSchedule(p, "-1").kind, "below 0");
        assertEquals(FormValidation.Kind.OK, d.doCheckMaxSchedule(p, " 3 ").kind, "a limit");
        assertEquals(FormValidation.Kind.ERROR, d.doCheckMaxSchedule(p, "three").kind, "not a number");
    }

    @Test
    void defaultPatternListIsStoredAsImplicit() {
        BuildRetryJobProperty p = new BuildRetryJobProperty();
        p.setAgentLossPatterns(RetryOptions.DEFAULT_AGENT_LOSS_PATTERNS);
        assertNull(p.getAgentLossPatterns(), "same as built-in");
        assertEquals(String.join("\n", RetryOptions.DEFAULT_AGENT_LOSS_PATTERNS), p.getAgentLossPatternsText());
        p.setAgentLossPatterns(List.of());
        assertEquals(List.of(), p.getAgentLossPatterns(), "an empty list means none");
    }

    @Test
    void sparseConfigXmlLoadsWithDefaults() {
        BuildRetryJobProperty p = (BuildRetryJobProperty) Items.XSTREAM2.fromXML(
                "<io.jenkins.plugins.pipelinebuildretry.BuildRetryJobProperty><maxSchedule>2</maxSchedule>"
                        + "</io.jenkins.plugins.pipelinebuildretry.BuildRetryJobProperty>");
        assertEquals(2, p.getMaxSchedule(), "stored value");
        assertEquals("", p.getRegexpForRerun(), "regexpForRerun");
        assertEquals("build", p.getRegexpScope(), "regexpScope");
        assertEquals("never", p.getRerunIfAborted(), "rerunIfAborted");
        assertEquals("drop", p.getGerritCause(), "gerritCause");
        assertEquals(List.of(), p.getAgentLossPatternsExtra(), "agentLossPatternsExtra");
        assertEquals(List.of(), p.getNeverRetryPatterns(), "neverRetryPatterns");
        assertEquals("", p.getNeverRetryPatternsText(), "neverRetryPatternsText");
        assertEquals(List.of(), p.toOptions().getNeverRetryPatterns(), "options");
        assertEquals(List.of(), p.getDropCauses(), "dropCauses");
        assertEquals(Map.of(), p.getParameterOverrides(), "parameterOverrides");
        assertEquals(List.of(), p.toOptions().validate().errors(), "loads into valid options");
    }

    @Test
    void snippetGeneratorRoundTrip() throws Exception {
        BuildRetryJobProperty before = fullProperty();
        DescribableModel<BuildRetryJobProperty> model = new DescribableModel<>(BuildRetryJobProperty.class);
        BuildRetryJobProperty after =
                model.instantiate(DescribableModel.uninstantiate2_(before).toMap());
        assertSameOptions(before, after);
        assertEquals(
                Map.of(),
                DescribableModel.uninstantiate2_(new BuildRetryJobProperty()).getArguments(),
                "defaults omitted");
    }

    @Test
    void symbolsBelongToThisPluginOnly() {
        assertSame(descriptor(), SymbolLookup.get().findDescriptor(JobProperty.class, "buildRetry"), "job property");
        assertSame(
                GlobalConfiguration.all().get(BuildRetryGlobalConfiguration.class),
                SymbolLookup.get().findDescriptor(GlobalConfiguration.class, "buildRetry"),
                "global configuration");
        assertTrue(
                SymbolLookup.get().findDescriptor(RetryDelay.class, "fixedDelay") instanceof FixedDelay.DescriptorImpl,
                "fixedDelay");
        assertTrue(
                SymbolLookup.get().findDescriptor(RetryDelay.class, "progressiveDelay")
                        instanceof ProgressiveDelay.DescriptorImpl,
                "progressiveDelay");
        assertTrue(
                SymbolLookup.get().findDescriptor(RetryDelay.class, "listDelay") instanceof ListDelay.DescriptorImpl,
                "listDelay");
        Set<String> ours = Set.of("buildRetry", "fixedDelay", "progressiveDelay", "listDelay");
        Set<String> stepNames = Set.of(
                "buildRetry",
                "fixedDelay",
                "progressiveDelay",
                "listDelay",
                "buildRetryDecision",
                "buildRetryPointOfNoReturn");
        Set<String> clashes = new TreeSet<>();
        for (Descriptor<?> d : ExtensionList.lookup(Descriptor.class)) {
            if (d.getClass().getName().startsWith("io.jenkins.plugins.pipelinebuildretry.")) {
                continue;
            }
            for (String s : SymbolLookup.getSymbolValue(d)) {
                if (ours.contains(s)) {
                    clashes.add(s + " in " + d.getClass().getName());
                }
            }
        }
        for (StepDescriptor d : StepDescriptor.all()) {
            if (stepNames.contains(d.getFunctionName())
                    && !d.getClass().getName().startsWith("io.jenkins.plugins.pipelinebuildretry.")) {
                clashes.add(
                        "step " + d.getFunctionName() + " in " + d.getClass().getName());
            }
        }
        assertEquals(Set.of(), clashes, "no other installed descriptor or step uses these names");
    }
}
