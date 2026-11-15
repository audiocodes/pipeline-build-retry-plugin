package io.jenkins.plugins.pipelinebuildretry.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The outcome of one retry decision; recorded on the run when the decision step makes it. */
public final class Decision implements Serializable {

    private static final long serialVersionUID = 1L;

    boolean willRetry;
    boolean wouldRetry;
    String reason;
    String result;
    String abortKind;
    int retryCount;
    Integer nextRetryCount;
    int maxSchedule;
    Integer delaySeconds;
    String delayType;
    boolean regexChecked;
    boolean regexMatched;
    boolean agentLoss;
    boolean agentLossOverride;
    boolean neverRetryChecked;
    Integer neverRetryPattern;
    boolean counterReset;
    boolean dryRun;
    boolean verbose;
    ArrayList<String> warnings = new ArrayList<>();
    ArrayList<String> details = new ArrayList<>();

    /** A no-retry decision with only a reason, for a build the decision could not read. */
    public static Decision noRetry(String result, String reason) {
        Decision d = new Decision();
        d.result = result;
        d.reason = reason;
        return d;
    }

    public boolean isWillRetry() {
        return willRetry;
    }

    public boolean isWouldRetry() {
        return wouldRetry;
    }

    public String getReason() {
        return reason;
    }

    public String getResult() {
        return result;
    }

    public String getAbortKind() {
        return abortKind;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Integer getNextRetryCount() {
        return nextRetryCount;
    }

    public int getMaxSchedule() {
        return maxSchedule;
    }

    public Integer getDelaySeconds() {
        return delaySeconds;
    }

    public String getDelayType() {
        return delayType;
    }

    public boolean isRegexChecked() {
        return regexChecked;
    }

    public boolean isRegexMatched() {
        return regexMatched;
    }

    public boolean isAgentLoss() {
        return agentLoss;
    }

    public boolean isAgentLossOverride() {
        return agentLossOverride;
    }

    public boolean isNeverRetryChecked() {
        return neverRetryChecked;
    }

    /** The 1-based index of the never-retry pattern that stopped the retry, or null. */
    public Integer getNeverRetryPattern() {
        return neverRetryPattern;
    }

    public boolean isCounterReset() {
        return counterReset;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public List<String> getDetails() {
        return details;
    }

    /** Adds a detail line, printed only with verbose. */
    public void addDetail(String line) {
        details.add(line);
    }

    /** Adds a warning line, always printed. */
    public void addWarning(String line) {
        warnings.add(line);
    }

    /** Overrides the outcome after the decision, for example "already-scheduled" or a scheduling failure. */
    public void stop(String why) {
        willRetry = false;
        reason = why;
    }

    /** The value the decision step returns to the Pipeline: plain strings, numbers, booleans and lists. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("willRetry", willRetry);
        m.put("wouldRetry", wouldRetry);
        m.put("reason", reason);
        m.put("result", result);
        m.put("abortKind", abortKind);
        m.put("retryCount", retryCount);
        m.put("nextRetryCount", nextRetryCount);
        m.put("maxSchedule", maxSchedule);
        m.put("delaySeconds", delaySeconds);
        m.put("regexChecked", regexChecked);
        m.put("regexMatched", regexMatched);
        m.put("agentLoss", agentLoss);
        m.put("agentLossOverride", agentLossOverride);
        m.put("counterReset", counterReset);
        m.put("neverRetryPattern", neverRetryPattern);
        m.put("dryRun", dryRun);
        m.put("warnings", new ArrayList<>(warnings));
        return m;
    }

    @Override
    public String toString() {
        return "Decision" + toMap();
    }
}
