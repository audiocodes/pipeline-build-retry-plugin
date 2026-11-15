package io.jenkins.plugins.pipelinebuildretry;

import hudson.model.Item;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Job-page text fields to option values, and the permission check every form method shares. */
@Restricted(NoExternalUse.class)
public final class FormText {

    private FormText() {}

    /** True when the user may configure the item, or administer Jenkins when there is no item. */
    public static boolean canConfigure(Item item) {
        return item == null ? Jenkins.get().hasPermission(Jenkins.ADMINISTER) : item.hasPermission(Item.CONFIGURE);
    }

    /** Non-blank lines, without their line endings. */
    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : (text == null ? "" : text).split("\r?\n")) {
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return out;
    }

    /** NAME=value lines to an ordered map; throws on a line without a name. */
    public static Map<String, String> overrides(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : lines(text)) {
            int eq = line.indexOf('=');
            String name = eq < 0 ? "" : line.substring(0, eq).trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Each line must be NAME=value");
            }
            out.put(name, line.substring(eq + 1));
        }
        return out;
    }

    /** The allowed values of a choice option, empty for a user who may not configure. */
    public static ListBoxModel items(Item item, List<String> values) {
        ListBoxModel m = new ListBoxModel();
        if (canConfigure(item)) {
            values.forEach(m::add);
        }
        return m;
    }

    // Names the pattern by its position only: a pattern may hold a secret and the reply may be logged.
    static FormValidation checkPatterns(List<String> patterns, boolean active) {
        for (int i = 0; i < patterns.size(); i++) {
            try {
                Pattern.compile(patterns.get(i));
            } catch (PatternSyntaxException e) {
                String which = patterns.size() == 1 ? "This" : "Pattern #" + (i + 1);
                return FormValidation.error(which + " is not a valid regular expression (" + e.getDescription()
                        + " near index " + e.getIndex() + ")");
            }
        }
        if (!active && !patterns.isEmpty()) {
            return FormValidation.warning("Ignored until \"Only rerun if a regular expression is found\" is on");
        }
        return FormValidation.ok();
    }
}
