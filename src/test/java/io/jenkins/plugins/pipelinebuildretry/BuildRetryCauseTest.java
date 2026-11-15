package io.jenkins.plugins.pipelinebuildretry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class BuildRetryCauseTest {

    @Test
    void describesTheRetry() {
        assertEquals(
                "Build retry 2/3 after build #41 failed", new BuildRetryCause("a/b", 41, 2, 3).getShortDescription());
        assertEquals(
                "Build retry 1/unlimited after build #5 failed",
                new BuildRetryCause("a/b", 5, 1, 0).getShortDescription());
        assertEquals(new BuildRetryCause("a/b", 5, 1, 0), new BuildRetryCause("a/b", 5, 1, 0));
        assertNotEquals(new BuildRetryCause("a/b", 5, 1, 0), new BuildRetryCause("a/b", 6, 1, 0));
    }

    @Test
    void wordsTheParentsEndByResult() {
        assertEquals("Build retry 1/2 after build #7 failed", text("FAILURE", null));
        assertEquals("Build retry 1/2 after build #7 timed out", text("ABORTED", "timeout"));
        assertEquals("Build retry 1/2 after build #7 was aborted", text("ABORTED", "infra"));
        assertEquals("Build retry 1/2 after build #7 was aborted", text("ABORTED", null));
        assertEquals("Build retry 1/2 after build #7 was unstable", text("UNSTABLE", null));
        assertEquals("Build retry 1/2 after build #7 ended NOT_BUILT", text("NOT_BUILT", null));
        assertEquals("Build retry 1/2 after build #7 failed", text(null, null), "a cause saved before the result");
    }

    private static String text(String result, String abortKind) {
        return new BuildRetryCause("a/b", 7, 1, 2, result, abortKind).getShortDescription();
    }
}
