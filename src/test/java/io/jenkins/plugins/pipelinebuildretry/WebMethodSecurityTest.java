package io.jenkins.plugins.pipelinebuildretry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import hudson.model.Result;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AccessDeniedException3;
import io.jenkins.plugins.pipelinebuildretry.delay.FixedDelay;
import io.jenkins.plugins.pipelinebuildretry.delay.ListDelay;
import io.jenkins.plugins.pipelinebuildretry.delay.ProgressiveDelay;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

@WithJenkins
class WebMethodSecurityTest {

    private record Call(Class<?> describable, String method, String value, String editorSees) {}

    private static final List<Call> JOB_CALLS = List.of(
            new Call(BuildRetryJobProperty.class, "checkMaxSchedule", "x", "Enter a whole number"),
            new Call(BuildRetryJobProperty.class, "checkRegexpForRerun", "tok3n-(", "not a valid regular expression"),
            new Call(BuildRetryJobProperty.class, "checkRegexpTimeoutMs", "0", "Must be at least 1 ms"),
            new Call(BuildRetryJobProperty.class, "checkAgentLossPatternsText", "tok3n-(", "not a valid"),
            new Call(BuildRetryJobProperty.class, "checkAgentLossPatternsExtraText", "tok3n-(", "not a valid"),
            new Call(BuildRetryJobProperty.class, "checkNeverRetryPatternsText", "tok3n-(", "not a valid"),
            new Call(BuildRetryJobProperty.class, "checkParameterOverridesText", "tok3n", "NAME=value"),
            new Call(BuildRetryJobProperty.class, "fillRerunIfAbortedItems", null, "infraOrTimeout"),
            new Call(BuildRetryJobProperty.class, "fillRegexpScopeItems", null, "failedStages"),
            new Call(BuildRetryJobProperty.class, "fillGerritCauseItems", null, "keep"),
            new Call(FixedDelay.class, "checkDelay", "-1", "Must be 0 or more"),
            new Call(ProgressiveDelay.class, "checkIncrement", "-1", "Must be 0 or more"),
            new Call(ProgressiveDelay.class, "checkMax", "-1", "Must be 0 or more"),
            new Call(ListDelay.class, "checkSecondsText", "x", "not a whole number"));

    private JenkinsRule j;
    private WorkflowJob job;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        job = j.createProject(WorkflowJob.class, "secured");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("reader")
                .grant(Jenkins.READ, Item.READ, Item.CONFIGURE)
                .everywhere()
                .to("editor"));
    }

    private WebResponse post(String user, String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.login(user);
        WebRequest req = new WebRequest(new URI(j.getURL() + path).toURL(), HttpMethod.POST);
        wc.addCrumb(req);
        return wc.getPage(req).getWebResponse();
    }

    private static String url(Call c) {
        String query = c.value() == null
                ? ""
                : "?checkRegexp=true&value=" + URLEncoder.encode(c.value(), StandardCharsets.UTF_8);
        return "job/secured/descriptorByName/" + c.describable().getName() + "/" + c.method() + query;
    }

    @Test
    void everyWebMethodNeedsPost() throws Exception {
        List<Class<?>> all = pluginClasses();
        assertTrue(all.contains(BuildRetryJobProperty.DescriptorImpl.class), "scan found the plugin classes");
        List<String> missing = new ArrayList<>();
        Set<Class<?>> withWebMethods = new LinkedHashSet<>();
        for (Class<?> k : all) {
            for (Method m : k.getDeclaredMethods()) {
                boolean configure = m.getName().equals("configure")
                        && List.of(m.getParameterTypes()).contains(StaplerRequest2.class);
                if (Modifier.isPublic(m.getModifiers()) && (isWebMethod(m) || configure)) {
                    withWebMethods.add(k);
                    if (m.getAnnotation(POST.class) == null) {
                        missing.add(k.getName() + "." + m.getName());
                    }
                }
            }
        }
        assertEquals(List.of(), missing, "web methods without @POST");
        assertEquals(Set.copyOf(webClasses()), withWebMethods, "classes whose web methods the tests below check");
    }

    private static List<Class<?>> pluginClasses() throws Exception {
        Path root = Path.of("target/classes");
        List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root.resolve("io/jenkins/plugins/pipelinebuildretry"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = root.relativize(f).toString().replace('/', '.').replaceAll("\\.class$", "");
                out.add(Class.forName(name, false, WebMethodSecurityTest.class.getClassLoader()));
            }
        }
        return out;
    }

    @Test
    void everyJobFormMethodIsChecked() {
        Set<String> declared = new TreeSet<>();
        for (Class<?> k : webClasses()) {
            if (k == BuildRetryGlobalConfiguration.class) {
                continue;
            }
            for (Method m : k.getDeclaredMethods()) {
                if (isWebMethod(m)) {
                    String name = m.getName().substring(2);
                    declared.add(Character.toLowerCase(name.charAt(0)) + name.substring(1));
                }
            }
        }
        Set<String> checked = new TreeSet<>();
        JOB_CALLS.forEach(c -> checked.add(c.method()));
        assertEquals(declared, checked, "add a row for each new form method");
    }

    @Test
    void formMethodsAnswerEditorsOnly() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Call c : JOB_CALLS) {
            String reader = post("reader", url(c)).getContentAsString();
            String editor = post("editor", url(c)).getContentAsString();
            if (reader.contains(c.editorSees())) {
                wrong.add("reader sees " + c.method());
            }
            if (!editor.contains(c.editorSees())) {
                wrong.add("editor misses " + c.method() + ": " + editor);
            }
            if (c.value() != null && c.value().startsWith("tok3n") && editor.contains("tok3n")) {
                wrong.add("pattern echoed by " + c.method());
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void getIsRefusedForFormMethods() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.login("editor");
        for (Call c : JOB_CALLS) {
            WebResponse r = wc.goTo(url(c), null).getWebResponse();
            assertTrue(r.getStatusCode() == 404 || r.getStatusCode() == 405, "GET refused: " + c.method());
            assertFalse(r.getContentAsString().contains(c.editorSees()), "GET answered: " + c.method());
        }
    }

    @Test
    void globalTimeoutCheckNeedsAdminister() throws Exception {
        String url =
                "descriptorByName/" + BuildRetryGlobalConfiguration.class.getName() + "/checkRegexpTimeoutMs?value=0";
        assertFalse(post("editor", url).getContentAsString().contains("at least 1"), "editor: no validation");
        assertTrue(post("admin", url).getContentAsString().contains("at least 1"), "admin: validation");
        assertEquals(403, post("editor", "configSubmit").getStatusCode(), "global form needs Administer");
        try (ACLContext ignored = ACL.as2(User.getById("editor", true).impersonate2())) {
            assertThrows(
                    AccessDeniedException3.class,
                    () -> BuildRetryGlobalConfiguration.get().configure((StaplerRequest2) null, new JSONObject()),
                    "configure checks Administer itself");
        }
    }

    @Test
    void readersNeverSeeTheConfiguredPatterns() throws Exception {
        BuildRetryJobProperty p = new BuildRetryJobProperty();
        p.setMaxSchedule(1);
        p.setDelay(new FixedDelay(600));
        p.setCheckRegexp(true);
        p.setRegexpForRerun("s3cr3t-[0-9]{4}");
        p.setAgentLossPatternsExtra(List.of("s3cr3t-agent"));
        p.setVerbose(true);
        job.addProperty(p);
        job.setDefinition(new CpsFlowDefinition("buildRetryDecision(); echo 'code s3cr3t-1234'; error 'boom'", true));
        WorkflowRun run = j.buildAndAssertStatus(Result.FAILURE, job);
        j.assertLogContains("retry 1/1 queued", run);
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.login("reader");
        List<String> leaks = new ArrayList<>();
        for (String page : List.of(
                "job/secured/",
                "job/secured/1/",
                "job/secured/api/json?depth=3",
                "job/secured/1/api/json?depth=3",
                "job/secured/1/api/xml?depth=3",
                "queue/api/json?depth=2",
                "job/secured/configure",
                "job/secured/config.xml")) {
            WebResponse r = wc.goTo(page, null).getWebResponse();
            String body = r.getContentAsString();
            if (r.getStatusCode() == 200 && (body.contains("s3cr3t-[") || body.contains("s3cr3t-agent"))) {
                leaks.add(page);
            }
        }
        String console = wc.goTo("job/secured/1/consoleText", "text/plain")
                .getWebResponse()
                .getContentAsString();
        if (console.contains("s3cr3t-[") || console.contains("s3cr3t-agent")) {
            leaks.add("console");
        }
        assertTrue(console.contains("regexp gate: found in log"), "the gate ran");
        assertEquals(List.of(), leaks, "pages that show a configured pattern to a reader");
        j.jenkins.getQueue().clear();
    }

    @Test
    void viewsHaveNoInlineScript() throws Exception {
        Path root = Path.of("src/main/resources");
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".jelly")).toList()) {
                String s = Files.readString(f);
                if (s.contains("<script") || s.matches("(?s).*\\son[a-z]+=.*") || s.contains("javascript:")) {
                    bad.add(root.relativize(f).toString());
                }
            }
        }
        assertEquals(List.of(), bad, "Jelly views must be CSP-clean");
    }

    private List<Class<?>> webClasses() {
        List<Class<?>> out = new ArrayList<>();
        out.add(BuildRetryGlobalConfiguration.class);
        for (Class<?> d :
                List.of(BuildRetryJobProperty.class, FixedDelay.class, ProgressiveDelay.class, ListDelay.class)) {
            out.add(Jenkins.get()
                    .getDescriptorOrDie(d.asSubclass(hudson.model.Describable.class))
                    .getClass());
        }
        return out;
    }

    private static boolean isWebMethod(Method m) {
        return m.getName().length() > 2
                && m.getName().startsWith("do")
                && Character.isUpperCase(m.getName().charAt(2));
    }
}
