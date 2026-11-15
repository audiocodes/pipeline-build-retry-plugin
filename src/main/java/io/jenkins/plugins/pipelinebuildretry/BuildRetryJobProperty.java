package io.jenkins.plugins.pipelinebuildretry;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Job;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.pipelinebuildretry.core.DelaySpec;
import io.jenkins.plugins.pipelinebuildretry.core.RetryOptions;
import io.jenkins.plugins.pipelinebuildretry.delay.ProgressiveDelay;
import io.jenkins.plugins.pipelinebuildretry.delay.RetryDelay;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import jenkins.model.OptionalJobProperty;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/** Turns on retry after failure for one Pipeline job; the only place its options are set. */
public class BuildRetryJobProperty extends OptionalJobProperty<WorkflowJob> {

    private boolean rerunIfUnstable;
    private RetryDelay delay;
    private int maxSchedule;
    private boolean checkRegexp;
    private String regexpForRerun = "";
    private Integer regexpTimeoutMs;
    private String regexpScope = "build";
    private boolean badge = true;
    private String rerunIfAborted = "never";
    private boolean rerunOnAgentLoss;
    private List<String> agentLossPatterns;
    private List<String> agentLossPatternsExtra = new ArrayList<>();
    private List<String> neverRetryPatterns = new ArrayList<>();
    private boolean skipReplays = true;
    private String gerritCause = "drop";
    private List<String> dropCauses = new ArrayList<>();
    private Map<String, String> parameterOverrides = new LinkedHashMap<>();
    private boolean dryRun;
    private boolean verbose;

    @DataBoundConstructor
    public BuildRetryJobProperty() {}

    /** The options the decision uses, as plain values. */
    public RetryOptions toOptions() {
        return new RetryOptions()
                .rerunIfUnstable(rerunIfUnstable)
                .delay(delay == null ? DelaySpec.defaultDelay() : delay.toSpec())
                .maxSchedule(maxSchedule)
                .checkRegexp(checkRegexp)
                .regexpForRerun(regexpForRerun)
                .regexpTimeoutMs(regexpTimeoutMs)
                .regexpScope(regexpScope)
                .badge(badge)
                .rerunIfAborted(rerunIfAborted)
                .rerunOnAgentLoss(rerunOnAgentLoss)
                .agentLossPatterns(agentLossPatterns)
                .agentLossPatternsExtra(agentLossPatternsExtra)
                .neverRetryPatterns(neverRetryPatterns)
                .skipReplays(skipReplays)
                .gerritCause(gerritCause)
                .dropCauses(dropCauses)
                .parameterOverrides(parameterOverrides)
                .dryRun(dryRun)
                .verbose(verbose);
    }

    public boolean isRerunIfUnstable() {
        return rerunIfUnstable;
    }

    @DataBoundSetter
    public void setRerunIfUnstable(boolean rerunIfUnstable) {
        this.rerunIfUnstable = rerunIfUnstable;
    }

    /** The configured delay, or null for the default progressive 300/10800. */
    @CheckForNull
    public RetryDelay getDelay() {
        return delay;
    }

    @DataBoundSetter
    public void setDelay(RetryDelay delay) {
        this.delay = delay;
    }

    public int getMaxSchedule() {
        return maxSchedule;
    }

    @DataBoundSetter
    public void setMaxSchedule(int maxSchedule) {
        this.maxSchedule = maxSchedule;
    }

    public boolean isCheckRegexp() {
        return checkRegexp;
    }

    @DataBoundSetter
    public void setCheckRegexp(boolean checkRegexp) {
        this.checkRegexp = checkRegexp;
    }

    public String getRegexpForRerun() {
        return regexpForRerun;
    }

    @DataBoundSetter
    public void setRegexpForRerun(String regexpForRerun) {
        String v = regexpForRerun == null ? "" : regexpForRerun;
        if (!v.isEmpty()) {
            requireValidPattern("regexpForRerun", v);
        }
        this.regexpForRerun = v;
    }

    @CheckForNull
    public Integer getRegexpTimeoutMs() {
        return regexpTimeoutMs;
    }

    @DataBoundSetter
    public void setRegexpTimeoutMs(Integer regexpTimeoutMs) {
        if (regexpTimeoutMs != null && regexpTimeoutMs < 1) {
            throw new IllegalArgumentException("buildRetry: regexpTimeoutMs must be at least 1");
        }
        this.regexpTimeoutMs = regexpTimeoutMs;
    }

    public String getRegexpScope() {
        return regexpScope;
    }

    @DataBoundSetter
    public void setRegexpScope(String regexpScope) {
        this.regexpScope = oneOf("regexpScope", regexpScope, RetryOptions.REGEXP_SCOPES, "build");
    }

    public boolean isBadge() {
        return badge;
    }

    @DataBoundSetter
    public void setBadge(boolean badge) {
        this.badge = badge;
    }

    public String getRerunIfAborted() {
        return rerunIfAborted;
    }

    @DataBoundSetter
    public void setRerunIfAborted(String rerunIfAborted) {
        this.rerunIfAborted = oneOf("rerunIfAborted", rerunIfAborted, RetryOptions.ABORT_POLICIES, "never");
    }

    public boolean isRerunOnAgentLoss() {
        return rerunOnAgentLoss;
    }

    @DataBoundSetter
    public void setRerunOnAgentLoss(boolean rerunOnAgentLoss) {
        this.rerunOnAgentLoss = rerunOnAgentLoss;
    }

    /** The replacement agent-loss patterns, or null for the built-in list. */
    @CheckForNull
    public List<String> getAgentLossPatterns() {
        return agentLossPatterns == null ? null : Collections.unmodifiableList(agentLossPatterns);
    }

    @DataBoundSetter
    public void setAgentLossPatterns(List<String> agentLossPatterns) {
        requireValidPatterns("agentLossPatterns", agentLossPatterns);
        this.agentLossPatterns =
                agentLossPatterns == null || agentLossPatterns.equals(RetryOptions.DEFAULT_AGENT_LOSS_PATTERNS)
                        ? null
                        : new ArrayList<>(agentLossPatterns);
    }

    public List<String> getAgentLossPatternsExtra() {
        return Collections.unmodifiableList(agentLossPatternsExtra);
    }

    @DataBoundSetter
    public void setAgentLossPatternsExtra(List<String> agentLossPatternsExtra) {
        requireValidPatterns("agentLossPatternsExtra", agentLossPatternsExtra);
        this.agentLossPatternsExtra =
                agentLossPatternsExtra == null ? new ArrayList<>() : new ArrayList<>(agentLossPatternsExtra);
    }

    public List<String> getNeverRetryPatterns() {
        return Collections.unmodifiableList(neverRetryPatterns);
    }

    /** Log patterns that stop every retry; at most 20, each named by its 1-based index. */
    @DataBoundSetter
    public void setNeverRetryPatterns(List<String> neverRetryPatterns) {
        requireValidPatterns("neverRetryPatterns", neverRetryPatterns);
        if (neverRetryPatterns != null && neverRetryPatterns.size() > RetryOptions.MAX_NEVER_RETRY_PATTERNS) {
            throw new IllegalArgumentException(
                    "buildRetry: " + RetryOptions.tooManyNeverRetryPatterns(neverRetryPatterns.size()));
        }
        this.neverRetryPatterns = neverRetryPatterns == null ? new ArrayList<>() : new ArrayList<>(neverRetryPatterns);
    }

    public boolean isSkipReplays() {
        return skipReplays;
    }

    @DataBoundSetter
    public void setSkipReplays(boolean skipReplays) {
        this.skipReplays = skipReplays;
    }

    public String getGerritCause() {
        return gerritCause;
    }

    @DataBoundSetter
    public void setGerritCause(String gerritCause) {
        this.gerritCause = oneOf("gerritCause", gerritCause, RetryOptions.GERRIT_CAUSE_MODES, "drop");
    }

    public List<String> getDropCauses() {
        return Collections.unmodifiableList(dropCauses);
    }

    @DataBoundSetter
    public void setDropCauses(List<String> dropCauses) {
        this.dropCauses = dropCauses == null ? new ArrayList<>() : new ArrayList<>(dropCauses);
    }

    public Map<String, String> getParameterOverrides() {
        return Collections.unmodifiableMap(parameterOverrides);
    }

    /** Parameter name to value on the retry only; values may be strings, numbers or booleans. */
    @DataBoundSetter
    public void setParameterOverrides(Map<String, Object> parameterOverrides) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (parameterOverrides != null) {
            for (Map.Entry<String, Object> e : parameterOverrides.entrySet()) {
                Object v = e.getValue();
                if (!(v instanceof CharSequence || v instanceof Number || v instanceof Boolean)) {
                    throw new IllegalArgumentException(
                            "buildRetry: parameterOverrides." + e.getKey() + " must be a string, number or boolean");
                }
                if (e.getKey() == null || e.getKey().isBlank()) {
                    throw new IllegalArgumentException("buildRetry: parameterOverrides has a blank parameter name");
                }
                out.put(e.getKey(), String.valueOf(v));
            }
        }
        this.parameterOverrides = out;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    @DataBoundSetter
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public boolean isVerbose() {
        return verbose;
    }

    @DataBoundSetter
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /** The agent-loss patterns as the job page shows them, one per line. */
    public String getAgentLossPatternsText() {
        return String.join(
                "\n", agentLossPatterns == null ? RetryOptions.DEFAULT_AGENT_LOSS_PATTERNS : agentLossPatterns);
    }

    /** The extra agent-loss patterns, one per line. */
    public String getAgentLossPatternsExtraText() {
        return String.join("\n", agentLossPatternsExtra);
    }

    /** The never-retry patterns, one per line. */
    public String getNeverRetryPatternsText() {
        return String.join("\n", neverRetryPatterns);
    }

    /** The cause class names to drop, one per line. */
    public String getDropCausesText() {
        return String.join("\n", dropCauses);
    }

    /** The overrides as NAME=value lines. */
    public String getParameterOverridesText() {
        StringBuilder sb = new StringBuilder();
        parameterOverrides.forEach((k, v) ->
                sb.append(sb.length() == 0 ? "" : "\n").append(k).append('=').append(v));
        return sb.toString();
    }

    // Read from XStream: setters never ran, so fill what older or hand-edited config.xml left out.
    protected Object readResolve() {
        if (regexpForRerun == null) {
            regexpForRerun = "";
        }
        if (regexpScope == null) {
            regexpScope = "build";
        }
        if (rerunIfAborted == null) {
            rerunIfAborted = "never";
        }
        if (gerritCause == null) {
            gerritCause = "drop";
        }
        if (agentLossPatternsExtra == null) {
            agentLossPatternsExtra = new ArrayList<>();
        }
        if (dropCauses == null) {
            dropCauses = new ArrayList<>();
        }
        if (neverRetryPatterns == null) {
            neverRetryPatterns = new ArrayList<>();
        }
        if (parameterOverrides == null) {
            parameterOverrides = new LinkedHashMap<>();
        }
        return this;
    }

    private static String oneOf(String option, String value, List<String> allowed, String fallback) {
        if (value == null) {
            return fallback;
        }
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(
                    "buildRetry: " + option + " must be one of " + String.join(", ", allowed));
        }
        return value;
    }

    // The pattern may hold a secret, so the message names the option and never the text.
    private static void requireValidPattern(String option, String pattern) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("buildRetry: " + option + " is not a valid regular expression ("
                    + e.getDescription() + " near index " + e.getIndex() + ")");
        }
    }

    private static void requireValidPatterns(String option, List<String> patterns) {
        if (patterns == null) {
            return;
        }
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i) == null) {
                throw new IllegalArgumentException("buildRetry: " + option + " #" + (i + 1) + " is empty");
            }
            requireValidPattern(option + " #" + (i + 1), patterns.get(i));
        }
    }

    /** Descriptor for {@link BuildRetryJobProperty}. */
    @Extension
    @Symbol("buildRetry")
    public static class DescriptorImpl extends OptionalJobPropertyDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Retry build after failure";
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return WorkflowJob.class.isAssignableFrom(jobType);
        }

        /** The default delay the job page shows. */
        public RetryDelay getDefaultDelay() {
            return new ProgressiveDelay(300, 10800);
        }

        @Override
        public BuildRetryJobProperty newInstance(StaplerRequest2 req, @NonNull JSONObject formData)
                throws Descriptor.FormException {
            JSONObject f = JSONObject.fromObject(formData);
            Object patterns = f.remove("agentLossPatternsText");
            Object extra = f.remove("agentLossPatternsExtraText");
            Object never = f.remove("neverRetryPatternsText");
            Object drop = f.remove("dropCausesText");
            Object overrides = f.remove("parameterOverridesText");
            try {
                BuildRetryJobProperty p = (BuildRetryJobProperty) super.newInstance(req, f);
                if (p != null) {
                    if (patterns != null) {
                        p.setAgentLossPatterns(FormText.lines(String.valueOf(patterns)));
                    }
                    if (extra != null) {
                        p.setAgentLossPatternsExtra(FormText.lines(String.valueOf(extra)));
                    }
                    if (never != null) {
                        p.setNeverRetryPatterns(FormText.lines(String.valueOf(never)));
                    }
                    if (drop != null) {
                        p.setDropCauses(FormText.lines(String.valueOf(drop)));
                    }
                    if (overrides != null) {
                        p.setParameterOverrides(new LinkedHashMap<>(FormText.overrides(String.valueOf(overrides))));
                    }
                }
                return p;
            } catch (IllegalArgumentException e) {
                throw new Descriptor.FormException(e.getMessage(), "buildRetry");
            }
        }

        @POST
        public ListBoxModel doFillRerunIfAbortedItems(@AncestorInPath Item item) {
            return FormText.items(item, RetryOptions.ABORT_POLICIES);
        }

        @POST
        public ListBoxModel doFillRegexpScopeItems(@AncestorInPath Item item) {
            return FormText.items(item, RetryOptions.REGEXP_SCOPES);
        }

        @POST
        public ListBoxModel doFillGerritCauseItems(@AncestorInPath Item item) {
            return FormText.items(item, RetryOptions.GERRIT_CAUSE_MODES);
        }

        @POST
        public FormValidation doCheckMaxSchedule(@AncestorInPath Item item, @QueryParameter String value) {
            if (!FormText.canConfigure(item)) {
                return FormValidation.ok();
            }
            try {
                return Integer.parseInt(value.trim()) <= 0
                        ? FormValidation.warning("0 or less means unlimited retries")
                        : FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Enter a whole number");
            }
        }

        @POST
        public FormValidation doCheckRegexpForRerun(
                @AncestorInPath Item item, @QueryParameter String value, @QueryParameter boolean checkRegexp) {
            if (!FormText.canConfigure(item)) {
                return FormValidation.ok();
            }
            return FormText.checkPatterns(value == null || value.isEmpty() ? List.of() : List.of(value), checkRegexp);
        }

        @POST
        public FormValidation doCheckRegexpTimeoutMs(@AncestorInPath Item item, @QueryParameter String value) {
            if (!FormText.canConfigure(item) || value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            try {
                return Integer.parseInt(value.trim()) >= 1
                        ? FormValidation.ok()
                        : FormValidation.error("Must be at least 1 ms, or empty for the global default");
            } catch (NumberFormatException e) {
                return FormValidation.error("Enter a whole number of milliseconds");
            }
        }

        @POST
        public FormValidation doCheckAgentLossPatternsText(@AncestorInPath Item item, @QueryParameter String value) {
            return FormText.canConfigure(item)
                    ? FormText.checkPatterns(FormText.lines(value), true)
                    : FormValidation.ok();
        }

        @POST
        public FormValidation doCheckAgentLossPatternsExtraText(
                @AncestorInPath Item item, @QueryParameter String value) {
            return FormText.canConfigure(item)
                    ? FormText.checkPatterns(FormText.lines(value), true)
                    : FormValidation.ok();
        }

        @POST
        public FormValidation doCheckNeverRetryPatternsText(@AncestorInPath Item item, @QueryParameter String value) {
            if (!FormText.canConfigure(item)) {
                return FormValidation.ok();
            }
            List<String> lines = FormText.lines(value);
            if (lines.size() > RetryOptions.MAX_NEVER_RETRY_PATTERNS) {
                return FormValidation.error(RetryOptions.tooManyNeverRetryPatterns(lines.size()));
            }
            return FormText.checkPatterns(lines, true);
        }

        @POST
        public FormValidation doCheckParameterOverridesText(@AncestorInPath Item item, @QueryParameter String value) {
            if (!FormText.canConfigure(item)) {
                return FormValidation.ok();
            }
            try {
                FormText.overrides(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }
    }
}
