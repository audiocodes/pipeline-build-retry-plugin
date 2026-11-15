package io.jenkins.plugins.pipelinebuildretry.delay;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.util.FormValidation;
import io.jenkins.plugins.pipelinebuildretry.FormText;
import io.jenkins.plugins.pipelinebuildretry.core.DelaySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/** A delay per retry, in seconds: the first value before retry 1, and so on; the last value repeats. */
public class ListDelay extends RetryDelay {

    private final List<Integer> seconds;

    @DataBoundConstructor
    public ListDelay(List<Integer> seconds) {
        if (seconds == null || seconds.isEmpty()) {
            throw new IllegalArgumentException("listDelay: seconds must be a non-empty list");
        }
        for (Integer s : seconds) {
            if (s == null || s < 0) {
                throw new IllegalArgumentException("listDelay: every value in seconds must be at least 0");
            }
        }
        this.seconds = new ArrayList<>(seconds);
    }

    public List<Integer> getSeconds() {
        return Collections.unmodifiableList(seconds);
    }

    /** The values as the job page shows them: "30, 90, 180". */
    public String getSecondsText() {
        return seconds.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    @Override
    public DelaySpec toSpec() {
        return DelaySpec.list(seconds);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ListDelay that && that.seconds.equals(seconds);
    }

    @Override
    public int hashCode() {
        return seconds.hashCode();
    }

    /** Descriptor for {@link ListDelay}. */
    @Extension
    @Symbol("listDelay")
    public static class DescriptorImpl extends RetryDelayDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return "List";
        }

        @Override
        public RetryDelay newInstance(StaplerRequest2 req, @NonNull JSONObject formData)
                throws Descriptor.FormException {
            JSONObject copy = JSONObject.fromObject(formData);
            Object text = copy.remove("secondsText");
            if (text != null) {
                try {
                    copy.put("seconds", JSONArray.fromObject(DelayForms.parseSeconds(String.valueOf(text))));
                } catch (IllegalArgumentException e) {
                    throw new Descriptor.FormException(e.getMessage(), "secondsText");
                }
            }
            return super.newInstance(req, copy);
        }

        @POST
        public FormValidation doCheckSecondsText(@AncestorInPath Item item, @QueryParameter String value) {
            if (!FormText.canConfigure(item)) {
                return FormValidation.ok();
            }
            try {
                DelayForms.parseSeconds(value);
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }
    }
}
