package io.jenkins.plugins.pipelinebuildretry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import groovy.lang.GroovyShell;
import hudson.util.FormValidation;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.misc.Util;
import io.jenkins.plugins.casc.misc.junit.jupiter.WithJenkinsConfiguredWithCode;
import io.jenkins.plugins.casc.model.CNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.junit.jupiter.api.Test;

@WithJenkinsConfiguredWithCode
class BuildRetryGlobalConfigurationTest {

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    void configurationAsCodeReadsAndExportsTheSettings(JenkinsConfiguredWithCodeRule j) throws Exception {
        assertEquals(12345, BuildRetryGlobalConfiguration.get().getRegexpTimeoutMs(), "read from YAML");
        assertFalse(BuildRetryGlobalConfiguration.get().isEnabled(), "switch read from YAML");
        assertTrue(BuildRetryGlobalConfiguration.get().isSendEvents(), "events read from YAML");
        assertTrue(BuildRetryGlobalConfiguration.get().isSendJobConfig(), "job list read from YAML");
        assertEquals(
                "https://collector.example:4318",
                BuildRetryGlobalConfiguration.get().getEventsEndpoint(),
                "endpoint read from YAML");
        CNode node = Util.getUnclassifiedRoot(new ConfigurationContext(ConfiguratorRegistry.get()))
                .get("buildRetry");
        assertEquals(
                "enabled: false\neventsCredentialsId: \"events-token\"\neventsEndpoint: \"https://collector.example:4318\"\n"
                        + "eventsResourceAttributes: \"deployment.environment.name=test\"\n"
                        + "eventsServiceName: \"controller-a\"\nregexpTimeoutMs: 12345\nsendEvents: true\nsendJobConfig: true",
                Util.toYamlString(node).trim(),
                "exported under its symbol");
    }

    @Test
    void formRoundTripAndReloadKeepTheSettings(JenkinsConfiguredWithCodeRule j) throws Exception {
        BuildRetryGlobalConfiguration c = BuildRetryGlobalConfiguration.get();
        assertEquals(30000, c.getRegexpTimeoutMs(), "default");
        assertTrue(c.isEnabled(), "on by default");
        assertFalse(c.isSendEvents(), "events off by default");
        assertFalse(c.isSendJobConfig(), "job list off by default");
        assertEquals("jenkins", c.getEventsServiceName(), "default service name");
        c.setEventsEndpoint("https://collector:4318");
        c.setEventsServiceName("controller-b");
        c.setEventsResourceAttributes("deployment.environment.name=prod\nteam=ci");
        c.setRegexpTimeoutMs(777);
        c.setSendEvents(true);
        c.setSendJobConfig(true);
        c.setEnabled(false);
        assertFalse(new BuildRetryGlobalConfiguration().isEnabled(), "the setter saves");
        j.configRoundtrip();
        assertEquals(777, BuildRetryGlobalConfiguration.get().getRegexpTimeoutMs(), "kept by the form");
        assertFalse(BuildRetryGlobalConfiguration.get().isEnabled(), "switch kept by the form");
        assertTrue(BuildRetryGlobalConfiguration.get().isSendEvents(), "events kept by the form");
        assertTrue(BuildRetryGlobalConfiguration.get().isSendJobConfig(), "job list kept by the form");
        assertEquals(
                "https://collector:4318", BuildRetryGlobalConfiguration.get().getEventsEndpoint(), "endpoint kept");
        assertEquals("controller-b", BuildRetryGlobalConfiguration.get().getEventsServiceName(), "name kept");
        assertEquals(
                "deployment.environment.name=prod\nteam=ci",
                BuildRetryGlobalConfiguration.get().getEventsResourceAttributes(),
                "attributes kept");
        BuildRetryGlobalConfiguration reloaded = new BuildRetryGlobalConfiguration();
        assertEquals(777, reloaded.getRegexpTimeoutMs(), "saved to disk");
        assertFalse(reloaded.isEnabled(), "switch saved to disk");
        assertTrue(reloaded.isSendJobConfig(), "job list saved to disk");
    }

    @Test
    void theJobListCheckboxWarnsWhenItCannotSend(JenkinsConfiguredWithCodeRule j) {
        BuildRetryGlobalConfiguration c = BuildRetryGlobalConfiguration.get();
        assertEquals(FormValidation.Kind.OK, c.doCheckSendJobConfig(false, false, "").kind, "unticked");
        assertEquals(
                FormValidation.Kind.OK,
                c.doCheckSendJobConfig(true, true, "https://collector:4318").kind,
                "events on, endpoint set");
        FormValidation off = c.doCheckSendJobConfig(true, false, "https://collector:4318");
        assertEquals(FormValidation.Kind.WARNING, off.kind, "events off");
        assertTrue(off.getMessage().contains("Send events"), off.getMessage());
        FormValidation noEndpoint = c.doCheckSendJobConfig(true, true, " ");
        assertEquals(FormValidation.Kind.WARNING, noEndpoint.kind, "no endpoint");
        assertTrue(noEndpoint.getMessage().contains("endpoint"), noEndpoint.getMessage());
    }

    @Test
    void aCredentialIsOnlySentOverHttpsOrToLoopback(JenkinsConfiguredWithCodeRule j) {
        BuildRetryGlobalConfiguration c = BuildRetryGlobalConfiguration.get();
        FormValidation plain = c.doCheckEventsEndpoint("http://collector:4318", "events-token");
        assertEquals(FormValidation.Kind.ERROR, plain.kind, "http with a credential");
        assertTrue(plain.getMessage().contains("https"), plain.getMessage());
        for (String ok : List.of(
                "https://collector:4318",
                "http://127.0.0.1:4318",
                "http://127.1.2.3:4318",
                "http://localhost:4318",
                "http://LOCALHOST:4318",
                "http://[::1]:4318")) {
            assertEquals(FormValidation.Kind.OK, c.doCheckEventsEndpoint(ok, "events-token").kind, ok);
        }
        assertEquals(
                FormValidation.Kind.OK, c.doCheckEventsEndpoint("http://collector:4318", "").kind, "no credential");
        assertEquals(
                FormValidation.Kind.OK, c.doCheckEventsEndpoint("http://collector:4318", null).kind, "no credential");
        assertTrue(BuildRetryGlobalConfiguration.tokenOverPlainHttp("HTTP://collector:4318", true), "scheme case");
        assertTrue(BuildRetryGlobalConfiguration.tokenOverPlainHttp("http://127.evil.example:4318", true), "not IP");
        assertTrue(BuildRetryGlobalConfiguration.tokenOverPlainHttp("http://localhost.evil.example", true), "suffix");
        assertFalse(BuildRetryGlobalConfiguration.tokenOverPlainHttp("http://collector:4318", false), "no token");
        assertFalse(BuildRetryGlobalConfiguration.tokenOverPlainHttp(null, true), "no endpoint");
    }

    @Test
    void unTickingTheSwitchOnTheSystemPageTurnsRetriesOff(JenkinsConfiguredWithCodeRule j) throws Exception {
        HtmlForm form = j.createWebClient().goTo("manage/configure").getFormByName("config");
        HtmlCheckBoxInput box = form.getInputByName("_.enabled");
        assertTrue(box.isChecked(), "ticked by default");
        box.setChecked(false);
        j.submit(form);
        assertFalse(BuildRetryGlobalConfiguration.get().isEnabled(), "off after the form is saved");
    }

    @Test
    void aSettingsFileWithoutTheSwitchLoadsAsOn(JenkinsConfiguredWithCodeRule j) throws Exception {
        String root = BuildRetryGlobalConfiguration.class.getName();
        Files.writeString(
                j.jenkins.getRootDir().toPath().resolve(root + ".xml"),
                "<?xml version='1.1' encoding='UTF-8'?>\n<" + root + ">\n  <regexpTimeoutMs>4321</regexpTimeoutMs>\n</"
                        + root + ">\n");
        BuildRetryGlobalConfiguration c = new BuildRetryGlobalConfiguration();
        assertEquals(4321, c.getRegexpTimeoutMs(), "file read");
        assertTrue(c.isEnabled(), "on when the file has no switch");
    }

    @Test
    void theSetterLineInTheDocsFlipsTheSwitchAndSavesIt(JenkinsConfiguredWithCodeRule j) throws Exception {
        String off = BuildRetryGlobalConfiguration.class.getName() + ".get().setEnabled(false)";
        String on = BuildRetryGlobalConfiguration.class.getName() + ".get().setEnabled(true)";
        String help;
        try (InputStream in = BuildRetryGlobalConfiguration.class.getResourceAsStream(
                "BuildRetryGlobalConfiguration/help-enabled.html")) {
            help = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String readme = Files.readString(Path.of("README.md"));
        for (String line : List.of(off, on)) {
            assertTrue(help.contains(line), "help names " + line);
            assertTrue(readme.contains(line), "README names " + line);
        }
        GroovyShell console = new GroovyShell(j.jenkins.getPluginManager().uberClassLoader);
        console.evaluate(off);
        assertFalse(BuildRetryGlobalConfiguration.get().isEnabled(), "off at once");
        assertFalse(new BuildRetryGlobalConfiguration().isEnabled(), "saved off");
        console.evaluate(on);
        assertTrue(BuildRetryGlobalConfiguration.get().isEnabled(), "on at once");
        assertTrue(new BuildRetryGlobalConfiguration().isEnabled(), "saved on");
    }

    @Test
    void resourceAttributesAreKeyValuePairs(JenkinsConfiguredWithCodeRule j) {
        BuildRetryGlobalConfiguration c = BuildRetryGlobalConfiguration.get();
        assertEquals(Map.of(), c.eventsResourceAttributes(), "none by default");
        c.setEventsResourceAttributes(
                " deployment.environment.name = prod \n\nteam=ci, link=https://x/?a=b\nbroken\n=nokey\nservice.name=other");
        assertEquals(
                List.of("deployment.environment.name=prod", "team=ci", "link=https://x/?a=b"),
                c.eventsResourceAttributes().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .toList(),
                "in order and trimmed; broken entries and service.name left out");
        c.setEventsResourceAttributes(
                IntStream.range(0, 25).mapToObj(i -> "k" + i + "=v").collect(Collectors.joining("\n")));
        assertEquals(
                BuildRetryGlobalConfiguration.MAX_RESOURCE_ATTRIBUTES,
                c.eventsResourceAttributes().size(),
                "capped");
        c.setEventsResourceAttributes("  ");
        assertEquals(null, c.getEventsResourceAttributes(), "blank clears");
    }

    @Test
    void checksTheResourceAttributes(JenkinsConfiguredWithCodeRule j) {
        BuildRetryGlobalConfiguration c = BuildRetryGlobalConfiguration.get();
        assertEquals(FormValidation.Kind.OK, c.doCheckEventsResourceAttributes("").kind, "blank");
        assertEquals(FormValidation.Kind.OK, c.doCheckEventsResourceAttributes("a=1, b=2\nc=3").kind, "good");
        FormValidation broken = c.doCheckEventsResourceAttributes("a=1\nbroken");
        assertEquals(FormValidation.Kind.ERROR, broken.kind, "no equals sign");
        assertTrue(broken.getMessage().contains("broken"), broken.getMessage());
        assertEquals(FormValidation.Kind.ERROR, c.doCheckEventsResourceAttributes("=x").kind, "no key");
        assertEquals(
                FormValidation.Kind.WARNING, c.doCheckEventsResourceAttributes("service.name=x").kind, "own field");
        assertEquals(
                FormValidation.Kind.WARNING,
                c.doCheckEventsResourceAttributes(IntStream.range(0, 25)
                                .mapToObj(i -> "k" + i + "=v")
                                .collect(Collectors.joining(",")))
                        .kind,
                "too many");
    }

    @Test
    void rejectsTimeoutsBelowOne(JenkinsConfiguredWithCodeRule j) {
        BuildRetryGlobalConfiguration c = BuildRetryGlobalConfiguration.get();
        assertThrows(IllegalArgumentException.class, () -> c.setRegexpTimeoutMs(0));
        assertEquals(30000, c.getRegexpTimeoutMs(), "unchanged");
        assertEquals(FormValidation.Kind.ERROR, c.doCheckRegexpTimeoutMs("0").kind, "0");
        assertEquals(FormValidation.Kind.ERROR, c.doCheckRegexpTimeoutMs("soon").kind, "not a number");
        assertEquals(FormValidation.Kind.OK, c.doCheckRegexpTimeoutMs(" 50 ").kind, "50");
    }
}
