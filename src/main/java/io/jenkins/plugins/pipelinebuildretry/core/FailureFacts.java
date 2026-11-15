package io.jenkins.plugins.pipelinebuildretry.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** What kind of failure a build had, and a short hash that stays the same when the same error repeats. */
@Restricted(NoExternalUse.class)
public final class FailureFacts {

    static final String CANCELLED_CAUSE =
            "org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty$CancelledCause";
    static final String NONRESUMABLE = "org.jenkinsci.plugins.workflow.steps.SynchronousResumeNotSupportedException";
    static final int FINGERPRINT_LENGTH = 12;
    static final int MAX_LINE_CHARS = 300;

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern TIMESTAMP = Pattern.compile("^\\[?\\d{4}-\\d{2}-\\d{2}[T ][0-9:.,]+Z?]?\\s+");
    // Lines every failure prints: shell trace, Pipeline markers, exit codes, stack frames, make's wrapper.
    private static final Pattern GENERIC =
            Pattern.compile("\\+ .*|\\[Pipeline].*|Finished: .*|.*script returned exit code.*"
                    + "|at \\S+\\(.*\\)|\\.\\.\\. \\d+ more|make(\\[\\d+])?: \\*\\*\\*.*");
    private static final Pattern ERRORISH = Pattern.compile(
            "(?i)error|err!|fail|fatal|exception|traceback|cannot|denied|timed out|timeout|not found|refused");
    private static final Pattern URL = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+");
    private static final Pattern USER_INFO = Pattern.compile("[^\\s/:@]+:[^\\s/@]+@");
    private static final Pattern QUOTED = Pattern.compile("'[^']*'|\"[^\"]*\"|‘[^’]*’|“[^”]*”|`[^`]*`");
    private static final Pattern KEY_VALUE = Pattern.compile("([\\w.-]+)=\\S+");
    private static final Pattern PATH = Pattern.compile("(?<!\\S)(/|[A-Za-z]:\\\\)\\S*");
    private static final Pattern DIGIT_WORD = Pattern.compile("\\S*\\d\\S*");

    private FailureFacts() {}

    /** One of a fixed set of failure classes, or null for a passing build; same precedence as the decision. */
    public static String failureClass(RunSnapshot s) {
        String result = s.getResult();
        if (result.equals("SUCCESS")) {
            return null;
        }
        List<String> causes = s.getInterruptionCauses();
        if (causes.contains(RetryDecision.USER_INTERRUPTION)) {
            return "user-abort";
        }
        if (causes.contains(CANCELLED_CAUSE)
                || causes.stream().anyMatch(RetryDecision.SUPERSEDED_INTERRUPTIONS::contains)) {
            return "superseded";
        }
        if (s.getErrorClasses().contains(NONRESUMABLE)) {
            return "controller-restart";
        }
        if (RetryDecision.k8sIgnored(s) && reasons(s).contains("OOMKilled")) {
            return "agent-oom";
        }
        if (RetryDecision.agentLossEvidence(s)) {
            return "agent-lost";
        }
        if (causes.contains(RetryDecision.TIMEOUT_INTERRUPTION)) {
            return "timeout";
        }
        return switch (result) {
            case "UNSTABLE" -> "unstable";
            case "ABORTED" -> "aborted-unknown";
            case "NOT_BUILT" -> "not-built";
            default ->
                s.getErrorClasses().contains("hudson.AbortException")
                        ? "script-failure"
                        : s.getErrorClasses().isEmpty() ? "unknown" : "pipeline-error";
        };
    }

    /** The stage of the build's first stage error line, or null. */
    public static String errorStage(RunSnapshot s) {
        RunSnapshot.ErrorLine first = first(s);
        return first == null ? null : first.stage();
    }

    /** The failing step's function name, its arguments as Jenkins shows them, and the tail of its log. */
    public record Step(String function, String arguments, List<String> logTail) {}

    /** A short hash of a failure, and what it was built from: log-line, step or finish-line. */
    public record Fingerprint(String hash, String basis) {}

    /** Hashes the error stage, failing step and its root-cause line, falling back as the basis says; or null. */
    public static Fingerprint fingerprint(RunSnapshot s, Step step) {
        RunSnapshot.ErrorLine first = first(s);
        if (first == null) {
            return null;
        }
        if (step == null) {
            return hash("finish-line", first.stage(), mask(first.line()));
        }
        String args = step.arguments() == null ? "" : mask(step.arguments());
        String line = step.logTail() == null ? null : rootCauseLine(step.logTail());
        return line == null
                ? hash("step", first.stage(), step.function(), args)
                : hash("log-line", first.stage(), step.function(), args, mask(line));
    }

    /** The first line of the last block of error-like lines, or the last line left; null when only noise is left. */
    static String rootCauseLine(List<String> tail) {
        List<String> kept = new ArrayList<>();
        for (String raw : tail) {
            String line = TIMESTAMP
                    .matcher(ANSI.matcher(raw).replaceAll(""))
                    .replaceFirst("")
                    .strip();
            if (!line.isEmpty() && !GENERIC.matcher(line).matches()) {
                kept.add(line);
            }
        }
        int last = kept.size() - 1;
        while (last >= 0 && !ERRORISH.matcher(kept.get(last)).find()) {
            last--;
        }
        if (last < 0) {
            return kept.isEmpty() ? null : kept.get(kept.size() - 1);
        }
        while (last > 0 && ERRORISH.matcher(kept.get(last - 1)).find()) {
            last--;
        }
        return kept.get(last);
    }

    /** Masks what changes between runs or may be secret, then cuts the line to {@link #MAX_LINE_CHARS}. */
    static String mask(String line) {
        String m = URL.matcher(line).replaceAll("<url>");
        m = USER_INFO.matcher(m).replaceAll("<auth>@");
        m = QUOTED.matcher(m).replaceAll("<q>");
        m = KEY_VALUE.matcher(m).replaceAll("$1=<v>");
        m = PATH.matcher(m).replaceAll("<path>");
        m = DIGIT_WORD.matcher(m).replaceAll("#");
        m = String.join(" ", m.strip().split("\\s+"));
        return m.length() > MAX_LINE_CHARS ? m.substring(0, MAX_LINE_CHARS) : m;
    }

    private static Fingerprint hash(String basis, String... parts) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
            return new Fingerprint(HexFormat.of().formatHex(hash).substring(0, FINGERPRINT_LENGTH), basis);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static RunSnapshot.ErrorLine first(RunSnapshot s) {
        return s.getStageErrorLines().stream()
                .filter(l -> l.line() != null)
                .min(Comparator.comparingInt(RunSnapshot.ErrorLine::order))
                .orElse(null);
    }

    private static List<String> reasons(RunSnapshot s) {
        return s.getFailedAgentReasons().isEmpty()
                ? s.getTerminationReasons()
                : s.getFailedAgentReasons().values().stream()
                        .flatMap(List::stream)
                        .toList();
    }
}
