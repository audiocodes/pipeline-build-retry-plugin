package io.jenkins.plugins.pipelinebuildretry;

import hudson.model.Cause;

/** Why a retry runs: "Build retry 2/3 after build #41 failed". */
public class BuildRetryCause extends Cause {

    private final String upstreamProject;
    private final int upstreamBuild;
    private final int retryCount;
    private final int maxRetryCount;
    // Null in causes saved before these fields existed.
    private final String upstreamResult;
    private final String upstreamAbortKind;

    public BuildRetryCause(String upstreamProject, int upstreamBuild, int retryCount, int maxRetryCount) {
        this(upstreamProject, upstreamBuild, retryCount, maxRetryCount, null, null);
    }

    public BuildRetryCause(
            String upstreamProject,
            int upstreamBuild,
            int retryCount,
            int maxRetryCount,
            String upstreamResult,
            String upstreamAbortKind) {
        this.upstreamProject = upstreamProject;
        this.upstreamBuild = upstreamBuild;
        this.retryCount = retryCount;
        this.maxRetryCount = maxRetryCount;
        this.upstreamResult = upstreamResult;
        this.upstreamAbortKind = upstreamAbortKind;
    }

    /** The full name of the job that failed; the retry runs in the same job. */
    public String getUpstreamProject() {
        return upstreamProject;
    }

    public int getUpstreamBuild() {
        return upstreamBuild;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    /** The result the failed build was judged with, such as FAILURE; null for an older cause. */
    public String getUpstreamResult() {
        return upstreamResult;
    }

    /** How an ABORTED upstream build was aborted, such as timeout; null otherwise. */
    public String getUpstreamAbortKind() {
        return upstreamAbortKind;
    }

    @Override
    public String getShortDescription() {
        return describe(retryCount, maxRetryCount, upstreamBuild, upstreamResult, upstreamAbortKind);
    }

    static String describe(int count, int max, int build, String result, String abortKind) {
        return "Build retry " + count + "/" + (max > 0 ? String.valueOf(max) : "unlimited") + " after build #" + build
                + " " + ended(result, abortKind);
    }

    private static String ended(String result, String abortKind) {
        if (result == null || "FAILURE".equals(result)) {
            return "failed";
        }
        if ("ABORTED".equals(result)) {
            return "timeout".equals(abortKind) ? "timed out" : "was aborted";
        }
        return "UNSTABLE".equals(result) ? "was unstable" : "ended " + result;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BuildRetryCause c
                && c.upstreamBuild == upstreamBuild
                && c.retryCount == retryCount
                && c.maxRetryCount == maxRetryCount
                && String.valueOf(c.upstreamProject).equals(String.valueOf(upstreamProject));
    }

    @Override
    public int hashCode() {
        return 31 * String.valueOf(upstreamProject).hashCode() + upstreamBuild;
    }
}
