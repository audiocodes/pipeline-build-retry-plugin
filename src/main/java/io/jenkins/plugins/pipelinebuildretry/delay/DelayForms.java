package io.jenkins.plugins.pipelinebuildretry.delay;

import hudson.model.Item;
import hudson.util.FormValidation;
import io.jenkins.plugins.pipelinebuildretry.FormText;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Form parsing shared by the delay types. */
@Restricted(NoExternalUse.class)
public final class DelayForms {

    private DelayForms() {}

    /** OK for a whole number of seconds that is 0 or more; OK for a user who may not configure. */
    public static FormValidation nonNegative(Item item, String value) {
        if (!FormText.canConfigure(item)) {
            return FormValidation.ok();
        }
        try {
            if (Integer.parseInt(value == null ? "" : value.trim()) >= 0) {
                return FormValidation.ok();
            }
        } catch (NumberFormatException e) {
            return FormValidation.error("Enter a whole number of seconds");
        }
        return FormValidation.error("Must be 0 or more");
    }

    /** Parses "30, 90 180" into seconds; throws with a short message on a bad value. */
    public static List<Integer> parseSeconds(String text) {
        List<Integer> out = new ArrayList<>();
        for (String part : (text == null ? "" : text).split("[,\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            int v;
            try {
                v = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + part + "' is not a whole number of seconds");
            }
            if (v < 0) {
                throw new IllegalArgumentException("Seconds must be 0 or more");
            }
            out.add(v);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one delay in seconds, such as 30, 90, 180");
        }
        return out;
    }
}
