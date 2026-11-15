package io.jenkins.plugins.pipelinebuildretry.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything the decision needs about a finished or finishing build, as plain values. */
public final class RunSnapshot {

    /** An error line Jenkins prints only after the build ends, kept for the regexp scan. */
    public record ErrorLine(String stageId, String stage, int order, String line, boolean abort) {}

    private String result;
    private boolean alreadyScheduled;
    private String pointOfNoReturn;
    private boolean replay;
    private boolean startedByUser;
    private boolean retry;
    private int retryCount;
    private List<String> interruptionCauses = new ArrayList<>();
    private boolean retryableCauseSeen;
    private boolean agentContextLost;
    private List<String> errorClasses = new ArrayList<>();
    private List<String> terminationReasons = new ArrayList<>();
    private Map<String, List<String>> failedAgentReasons = new LinkedHashMap<>();
    private List<String> failedAgents = new ArrayList<>();
    private List<String> failedAgentLabels = new ArrayList<>();
    private ScanResult logScan;
    private List<String> failedStages = new ArrayList<>();
    private List<String> failedStageIds = new ArrayList<>();
    private List<ErrorLine> stageErrorLines = new ArrayList<>();
    private boolean abortPrevious;
    private String flowGraphError;
    private String errorStepId;

    public String getResult() {
        return result == null ? "SUCCESS" : result;
    }

    public RunSnapshot result(String v) {
        result = v;
        return this;
    }

    public boolean isAlreadyScheduled() {
        return alreadyScheduled;
    }

    public RunSnapshot alreadyScheduled(boolean v) {
        alreadyScheduled = v;
        return this;
    }

    public String getPointOfNoReturn() {
        return pointOfNoReturn;
    }

    public RunSnapshot pointOfNoReturn(String v) {
        pointOfNoReturn = v == null || v.isBlank() ? null : v.trim();
        return this;
    }

    public boolean isReplay() {
        return replay;
    }

    public RunSnapshot replay(boolean v) {
        replay = v;
        return this;
    }

    public boolean isStartedByUser() {
        return startedByUser;
    }

    public RunSnapshot startedByUser(boolean v) {
        startedByUser = v;
        return this;
    }

    public boolean isRetry() {
        return retry;
    }

    public RunSnapshot retry(boolean v) {
        retry = v;
        return this;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public RunSnapshot retryCount(int v) {
        retryCount = Math.max(0, v);
        return this;
    }

    public List<String> getInterruptionCauses() {
        return interruptionCauses;
    }

    public RunSnapshot interruptionCauses(List<String> v) {
        interruptionCauses = copy(v);
        return this;
    }

    public boolean isRetryableCauseSeen() {
        return retryableCauseSeen;
    }

    public RunSnapshot retryableCauseSeen(boolean v) {
        retryableCauseSeen = v;
        return this;
    }

    public boolean isAgentContextLost() {
        return agentContextLost;
    }

    public RunSnapshot agentContextLost(boolean v) {
        agentContextLost = v;
        return this;
    }

    public List<String> getErrorClasses() {
        return errorClasses;
    }

    public RunSnapshot errorClasses(List<String> v) {
        errorClasses = copy(v);
        return this;
    }

    public List<String> getTerminationReasons() {
        return terminationReasons;
    }

    public RunSnapshot terminationReasons(List<String> v) {
        terminationReasons = copy(v);
        return this;
    }

    public Map<String, List<String>> getFailedAgentReasons() {
        return failedAgentReasons;
    }

    public RunSnapshot failedAgentReasons(Map<String, List<String>> v) {
        failedAgentReasons = new LinkedHashMap<>();
        if (v != null) {
            v.forEach((k, r) -> failedAgentReasons.put(k, copy(r)));
        }
        return this;
    }

    /** The agents where an error started, as the decision blames them. */
    public List<String> getFailedAgents() {
        return failedAgents;
    }

    public RunSnapshot failedAgents(List<String> v) {
        failedAgents = copy(v);
        return this;
    }

    /** The labels of {@link #getFailedAgents()}, sorted, without each agent's own name. */
    public List<String> getFailedAgentLabels() {
        return failedAgentLabels;
    }

    public RunSnapshot failedAgentLabels(List<String> v) {
        failedAgentLabels = copy(v);
        return this;
    }

    /** The flow node of the step where the error stage's failure started, or null. */
    public String getErrorStepId() {
        return errorStepId;
    }

    public RunSnapshot errorStepId(String v) {
        errorStepId = v;
        return this;
    }

    public ScanResult getLogScan() {
        return logScan;
    }

    public RunSnapshot logScan(ScanResult v) {
        logScan = v;
        return this;
    }

    public List<String> getFailedStages() {
        return failedStages;
    }

    public RunSnapshot failedStages(List<String> v) {
        failedStages = copy(v);
        return this;
    }

    public List<String> getFailedStageIds() {
        return failedStageIds;
    }

    public RunSnapshot failedStageIds(List<String> v) {
        failedStageIds = copy(v);
        return this;
    }

    public List<ErrorLine> getStageErrorLines() {
        return stageErrorLines;
    }

    public RunSnapshot stageErrorLines(List<ErrorLine> v) {
        stageErrorLines = v == null ? new ArrayList<>() : new ArrayList<>(v);
        return this;
    }

    public boolean isAbortPrevious() {
        return abortPrevious;
    }

    public RunSnapshot abortPrevious(boolean v) {
        abortPrevious = v;
        return this;
    }

    public String getFlowGraphError() {
        return flowGraphError;
    }

    public RunSnapshot flowGraphError(String v) {
        flowGraphError = v;
        return this;
    }

    private static List<String> copy(List<String> v) {
        List<String> out = new ArrayList<>();
        if (v != null) {
            for (String s : v) {
                if (s != null) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "RunSnapshot[result=" + getResult() + ", alreadyScheduled=" + alreadyScheduled + ", pointOfNoReturn="
                + pointOfNoReturn + ", replay=" + replay + ", startedByUser=" + startedByUser + ", retry=" + retry
                + ", retryCount=" + retryCount + ", interruptionCauses=" + interruptionCauses + ", retryableCauseSeen="
                + retryableCauseSeen + ", agentContextLost=" + agentContextLost + ", errorClasses=" + errorClasses
                + ", terminationReasons=" + terminationReasons
                + ", failedAgentReasons=" + failedAgentReasons + ", logScan=" + logScan + ", failedStages="
                + failedStages + ", abortPrevious=" + abortPrevious + ", flowGraphError=" + flowGraphError + "]";
    }
}
