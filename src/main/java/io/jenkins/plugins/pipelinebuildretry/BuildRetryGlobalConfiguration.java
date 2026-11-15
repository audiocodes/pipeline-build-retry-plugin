package io.jenkins.plugins.pipelinebuildretry;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.pipelinebuildretry.core.RetryOptions;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/** Controller-wide settings: the off switch for retries, the default regexp timeout and monitoring events. */
@Extension
@Symbol("buildRetry")
public class BuildRetryGlobalConfiguration extends GlobalConfiguration {

    static final String DEFAULT_SERVICE_NAME = "jenkins";

    static final int MAX_RESOURCE_ATTRIBUTES = 20;

    private static final String SERVICE_NAME = "service.name";

    private int regexpTimeoutMs = RetryOptions.DEFAULT_REGEXP_TIMEOUT_MS;

    // Initialized before load(), so a settings file without this field reads as on.
    private boolean enabled = true;

    private boolean sendEvents;

    private boolean sendJobConfig;

    private String eventsEndpoint;

    private String eventsCredentialsId;

    private String eventsServiceName;

    private String eventsResourceAttributes;

    public BuildRetryGlobalConfiguration() {
        load();
    }

    /** The configured instance, or a default one while Jenkins starts. */
    @NonNull
    public static BuildRetryGlobalConfiguration get() {
        BuildRetryGlobalConfiguration c = GlobalConfiguration.all().get(BuildRetryGlobalConfiguration.class);
        return c == null ? new BuildRetryGlobalConfiguration(true) : c;
    }

    private BuildRetryGlobalConfiguration(boolean unused) {}

    public int getRegexpTimeoutMs() {
        return regexpTimeoutMs;
    }

    @DataBoundSetter
    public void setRegexpTimeoutMs(int regexpTimeoutMs) {
        if (regexpTimeoutMs < 1) {
            throw new IllegalArgumentException("regexpTimeoutMs must be at least 1");
        }
        this.regexpTimeoutMs = regexpTimeoutMs;
        save();
    }

    /** Whether the plugin decides and queues retries on this controller; off skips all of it. */
    public boolean isEnabled() {
        return enabled;
    }

    @DataBoundSetter
    public void setEnabled(boolean enabled) {
        boolean before = this.enabled;
        this.enabled = enabled;
        save();
        if (before != enabled) {
            BuildRetryEvents.switchChanged("enabled", before, enabled, sendEvents);
            JobConfigEvents.snapshot("switch-changed");
        }
    }

    /** Whether the plugin sends monitoring events through the controller's OpenTelemetry setup; off by default. */
    public boolean isSendEvents() {
        return sendEvents;
    }

    @DataBoundSetter
    public void setSendEvents(boolean sendEvents) {
        boolean before = this.sendEvents;
        this.sendEvents = sendEvents;
        save();
        if (before != sendEvents) {
            BuildRetryEvents.switchChanged("sendEvents", before, sendEvents, before);
            if (sendEvents) {
                JobConfigEvents.snapshot("events-on");
            }
        }
    }

    /** Whether the events also carry the list of jobs that use the plugin, with their settings; off by default. */
    public boolean isSendJobConfig() {
        return sendJobConfig;
    }

    @DataBoundSetter
    public void setSendJobConfig(boolean sendJobConfig) {
        boolean before = this.sendJobConfig;
        this.sendJobConfig = sendJobConfig;
        save();
        if (before != sendJobConfig) {
            BuildRetryEvents.switchChanged("sendJobConfig", before, sendJobConfig, sendEvents);
            if (sendJobConfig) {
                JobConfigEvents.snapshot("job-config-on");
            }
        }
    }

    /** The collector's OTLP/HTTP address, such as {@code https://collector:4318}; blank sends nothing. */
    @CheckForNull
    public String getEventsEndpoint() {
        return eventsEndpoint;
    }

    @DataBoundSetter
    public void setEventsEndpoint(String eventsEndpoint) {
        this.eventsEndpoint = blankToNull(eventsEndpoint);
        save();
    }

    /** A Secret text credential sent as {@code Authorization: Bearer}; blank sends no header. */
    @CheckForNull
    public String getEventsCredentialsId() {
        return eventsCredentialsId;
    }

    @DataBoundSetter
    public void setEventsCredentialsId(String eventsCredentialsId) {
        this.eventsCredentialsId = blankToNull(eventsCredentialsId);
        save();
    }

    /** The {@code service.name} resource attribute of every event; {@code jenkins} when blank. */
    @NonNull
    public String getEventsServiceName() {
        return eventsServiceName == null ? DEFAULT_SERVICE_NAME : eventsServiceName;
    }

    @DataBoundSetter
    public void setEventsServiceName(String eventsServiceName) {
        String v = blankToNull(eventsServiceName);
        this.eventsServiceName = DEFAULT_SERVICE_NAME.equals(v) ? null : v;
        save();
    }

    /** Extra resource attributes of every event, as {@code key=value} entries split by lines or commas. */
    @CheckForNull
    public String getEventsResourceAttributes() {
        return eventsResourceAttributes;
    }

    @DataBoundSetter
    public void setEventsResourceAttributes(String eventsResourceAttributes) {
        this.eventsResourceAttributes = blankToNull(eventsResourceAttributes);
        save();
    }

    /** The valid entries of {@link #getEventsResourceAttributes()} in order; {@code service.name} has its own field. */
    @NonNull
    public Map<String, String> eventsResourceAttributes() {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (String entry : entries(eventsResourceAttributes)) {
            String key = key(entry);
            if (!key.isEmpty()
                    && !key.equals(SERVICE_NAME)
                    && (attributes.size() < MAX_RESOURCE_ATTRIBUTES || attributes.containsKey(key))) {
                attributes.put(key, entry.substring(entry.indexOf('=') + 1).trim());
            }
        }
        return Collections.unmodifiableMap(attributes);
    }

    private static List<String> entries(String text) {
        return text == null
                ? List.of()
                : Arrays.stream(text.split("[,\\r\\n]+"))
                        .map(String::trim)
                        .filter(e -> !e.isEmpty())
                        .toList();
    }

    // Empty when the entry has no '=' or nothing before it.
    private static String key(String entry) {
        int eq = entry.indexOf('=');
        return eq < 0 ? "" : entry.substring(0, eq).trim();
    }

    /** The token of {@link #getEventsCredentialsId()}, or null when unset or not found. */
    @CheckForNull
    public String eventsToken() {
        if (eventsCredentialsId == null) {
            return null;
        }
        StringCredentials c = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of()),
                CredentialsMatchers.withId(eventsCredentialsId));
        return c == null ? null : c.getSecret().getPlainText();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return "Pipeline Build Retry";
    }

    @POST
    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        try {
            return super.configure(req, json);
        } catch (IllegalArgumentException e) {
            throw new FormException(e.getMessage(), "regexpTimeoutMs");
        }
    }

    @POST
    public FormValidation doCheckEventsEndpoint(@QueryParameter String value) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER) || value == null || value.isBlank()) {
            return FormValidation.ok();
        }
        try {
            URI u = new URI(value.trim());
            return ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && u.getHost() != null
                    ? FormValidation.ok()
                    : FormValidation.error("Enter an http or https address, such as https://collector:4318");
        } catch (URISyntaxException e) {
            return FormValidation.error("Not a valid address: " + e.getReason());
        }
    }

    @POST
    public FormValidation doCheckSendJobConfig(
            @QueryParameter boolean value, @QueryParameter boolean sendEvents, @QueryParameter String eventsEndpoint) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER) || !value) {
            return FormValidation.ok();
        }
        if (!sendEvents) {
            return FormValidation.warning("Nothing is sent until Send events is ticked");
        }
        if (eventsEndpoint == null || eventsEndpoint.isBlank()) {
            return FormValidation.warning("Nothing is sent until an events endpoint is set");
        }
        return FormValidation.ok();
    }

    @POST
    public FormValidation doCheckEventsResourceAttributes(@QueryParameter String value) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return FormValidation.ok();
        }
        List<String> entries = entries(value);
        for (String entry : entries) {
            if (key(entry).isEmpty()) {
                return FormValidation.error("Not a key=value pair: " + entry);
            }
        }
        if (entries.stream().anyMatch(e -> key(e).equals(SERVICE_NAME))) {
            return FormValidation.warning("service.name is ignored here; set it in Events service name");
        }
        if (entries.size() > MAX_RESOURCE_ATTRIBUTES) {
            return FormValidation.warning("Only the first " + MAX_RESOURCE_ATTRIBUTES + " attributes are sent");
        }
        return FormValidation.ok();
    }

    @POST
    public ListBoxModel doFillEventsCredentialsIdItems(@QueryParameter String eventsCredentialsId) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return new StandardListBoxModel().includeCurrentValue(eventsCredentialsId);
        }
        return new StandardListBoxModel()
                .includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2, Jenkins.get(), StringCredentials.class, List.of(), CredentialsMatchers.always())
                .includeCurrentValue(eventsCredentialsId);
    }

    @POST
    public FormValidation doCheckRegexpTimeoutMs(@QueryParameter String value) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return FormValidation.ok();
        }
        try {
            return Integer.parseInt(value.trim()) >= 1
                    ? FormValidation.ok()
                    : FormValidation.error("Must be at least 1 ms");
        } catch (NumberFormatException e) {
            return FormValidation.error("Enter a whole number of milliseconds");
        }
    }
}
