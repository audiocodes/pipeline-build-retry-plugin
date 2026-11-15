package io.jenkins.plugins.pipelinebuildretry.evidence;

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Why a cloud agent ended, as its cloud reports it; empty when the cloud does not know the agent. */
@Restricted(NoExternalUse.class)
public interface TerminationReasons extends ExtensionPoint {

    /** The reasons for one agent, such as OOMKilled; never throws on purpose, but callers guard anyway. */
    Collection<String> reasons(String agent);

    /** Each agent's sorted reasons, merged over every source; an empty map when no source is installed. */
    static Map<String, List<String>> byAgent(Collection<String> agents) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<TerminationReasons> sources = ExtensionList.lookup(TerminationReasons.class);
        if (sources.isEmpty() || agents == null) {
            return out;
        }
        for (String agent : agents) {
            TreeSet<String> reasons = new TreeSet<>();
            for (TerminationReasons source : sources) {
                try {
                    Collection<String> r = source.reasons(agent);
                    if (r != null) {
                        r.forEach(x -> reasons.add(String.valueOf(x)));
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // one broken source must not hide the others' reasons
                }
            }
            out.put(agent, new ArrayList<>(reasons));
        }
        return out;
    }
}
