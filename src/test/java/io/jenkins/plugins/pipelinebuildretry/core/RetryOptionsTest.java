package io.jenkins.plugins.pipelinebuildretry.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetryOptionsTest {

    private static List<String> errorsOf(RetryOptions o) {
        return o.validate().errors();
    }

    private static boolean warns(RetryOptions o, String text) {
        return o.validate().warnings().stream().anyMatch(w -> w.contains(text));
    }

    @Test
    void defaultsMatchFreestyle() {
        RetryOptions c = new RetryOptions();
        assertEquals(List.of(), errorsOf(c), "empty config has no errors");
        assertFalse(c.isRerunIfUnstable(), "rerunIfUnstable false");
        assertEquals(DelaySpec.progressive(300, 10800), c.getDelay(), "delay defaults to progressive 300/10800");
        assertEquals(0, c.getMaxSchedule(), "maxSchedule 0");
        assertFalse(c.isCheckRegexp(), "checkRegexp false");
        assertEquals("", c.getRegexpForRerun(), "regexpForRerun blank");
        assertNull(c.getRegexpTimeoutMs(), "regexpTimeoutMs unset until resolved");
        assertEquals("build", c.getRegexpScope(), "regexpScope build");
        assertTrue(c.isBadge(), "badge true");
        assertEquals("never", c.getRerunIfAborted(), "rerunIfAborted never");
        assertFalse(c.isRerunOnAgentLoss(), "rerunOnAgentLoss false");
        assertEquals(
                List.of(
                        "Agent .+ was deleted; cancelling node body",
                        "seems to be removed or offline .*; will wait for",
                        "has been removed or offline for .*; assuming it is not coming back",
                        "Pod .+ was just deleted",
                        "Pod just failed\\.",
                        "Pod was evicted by the Kubernetes Eviction API",
                        "hudson\\.remoting\\.ChannelClosedException",
                        "Containers? \\S+ (was|were) terminated\\."),
                c.getAgentLossPatterns(),
                "agentLossPatterns default list");
        assertEquals(List.of(), c.getAgentLossPatternsExtra(), "agentLossPatternsExtra empty");
        assertTrue(c.isSkipReplays(), "skipReplays true");
        assertEquals("drop", c.getGerritCause(), "gerritCause drop");
        assertEquals(List.of(), c.getDropCauses(), "dropCauses empty");
        assertEquals(Map.of(), c.getParameterOverrides(), "parameterOverrides empty");
        assertFalse(c.isDryRun(), "dryRun false");
        assertFalse(c.isVerbose(), "verbose false");
        assertTrue(warns(c, "maxSchedule=0 means unlimited retries"), "maxSchedule=0 warns about unlimited retries");
    }

    @Test
    void copyIsIndependent() {
        RetryOptions a = new RetryOptions().dropCauses(List.of("A"));
        RetryOptions b = a.copy();
        b.dropCauses(List.of("B")).maxSchedule(9);
        assertNotSame(a, b);
        assertEquals(List.of("A"), a.getDropCauses(), "a copy never changes the original");
        assertEquals(0, a.getMaxSchedule());
    }

    @Test
    void validation() {
        assertEquals(1, errorsOf(new RetryOptions().rerunIfAborted("sometimes")).size(), "bad enum is an error");
        assertEquals(1, errorsOf(new RetryOptions().regexpScope("stages")).size(), "bad regexpScope is an error");
        assertEquals(1, errorsOf(new RetryOptions().gerritCause("maybe")).size(), "bad gerritCause is an error");
        assertEquals(
                List.of(), errorsOf(new RetryOptions().maxSchedule(-1)), "negative maxSchedule allowed (unlimited)");
        assertFalse(warns(new RetryOptions().maxSchedule(3), "unlimited"), "maxSchedule 3 has no unlimited warning");
        assertEquals(1, errorsOf(new RetryOptions().delay(DelaySpec.fixed(-5))).size(), "negative fixed delay");
        assertEquals(
                1,
                errorsOf(new RetryOptions().delay(DelaySpec.progressive(-1, 10)))
                        .size(),
                "negative progressive increment");
        assertEquals(
                1, errorsOf(new RetryOptions().delay(DelaySpec.list(List.of()))).size(), "empty list delay");
        assertEquals(
                1,
                errorsOf(new RetryOptions().delay(DelaySpec.list(List.of(30, -1))))
                        .size(),
                "list delay with a negative is an error");
        assertEquals(
                List.of(30, 90),
                new RetryOptions()
                        .delay(DelaySpec.list(List.of(30, 90)))
                        .getDelay()
                        .getSeconds(),
                "list delay is kept");
        assertEquals(1, errorsOf(new RetryOptions().delay(null)).size(), "a missing delay is an error");
        List<String> badRegex = errorsOf(new RetryOptions().checkRegexp(true).regexpForRerun("SECRET(["));
        assertEquals(1, badRegex.size(), "invalid regexpForRerun is an error");
        assertFalse(badRegex.get(0).contains("SECRET"), "invalid regexp error never echoes the pattern");
        assertEquals(
                1,
                errorsOf(new RetryOptions().agentLossPatternsExtra(List.of("ok", "(")))
                        .size(),
                "invalid agentLossPatterns entry is an error");
        assertEquals(1, errorsOf(new RetryOptions().regexpTimeoutMs(0)).size(), "regexpTimeoutMs must be positive");
        assertEquals(
                1,
                errorsOf(new RetryOptions().parameterOverrides(Map.of(" ", "x")))
                        .size(),
                "a blank override name is an error");
        assertEquals("", new RetryOptions().regexpForRerun(null).getRegexpForRerun(), "null falls back to default");
        assertTrue(warns(new RetryOptions().checkRegexp(true), "every failure retries"), "blank regexp warns");
        assertTrue(warns(new RetryOptions().regexpForRerun("x"), "ignored"), "regexp without checkRegexp warns");
        assertEquals(
                3,
                errorsOf(new RetryOptions().rerunIfAborted("x").regexpScope("y").regexpTimeoutMs(0))
                        .size(),
                "several errors are all reported");
    }

    @Test
    void effectiveTimeout() {
        assertEquals(1000L, new RetryOptions().regexpTimeoutMs(1000).effectiveRegexpTimeoutMs(5000), "explicit wins");
        assertEquals(5000L, new RetryOptions().effectiveRegexpTimeoutMs(5000), "global value next");
        assertEquals(30000L, new RetryOptions().effectiveRegexpTimeoutMs(null), "30000 fallback");
        assertEquals(30000L, new RetryOptions().effectiveRegexpTimeoutMs(0), "a zero global value is ignored");
    }

    @Test
    void boundariesAndMessages() {
        assertEquals(List.of(), errorsOf(new RetryOptions().regexpTimeoutMs(1)), "1 ms is allowed");
        assertEquals(List.of(), errorsOf(new RetryOptions().delay(DelaySpec.fixed(0))), "a 0 s delay is allowed");
        assertEquals(List.of(), errorsOf(new RetryOptions().delay(DelaySpec.progressive(0, 0))), "0/0 is allowed");
        assertEquals(List.of(), errorsOf(new RetryOptions().delay(DelaySpec.list(List.of(0)))), "a 0 in a list");
        assertEquals(
                1,
                errorsOf(new RetryOptions().delay(DelaySpec.progressive(1, -1))).size(),
                "negative max");
        List<String> bad = errorsOf(new RetryOptions().agentLossPatternsExtra(List.of("ok", "(")));
        assertTrue(bad.get(0).startsWith("agent-loss pattern #10 is not a valid regular expression"), bad.get(0));
        assertEquals(
                List.of("rerunIfAborted must be one of 'never', 'infra', 'timeout', 'infraOrTimeout', 'always'"),
                errorsOf(new RetryOptions().rerunIfAborted("x")));
        assertFalse(new RetryOptions().badge(false).isBadge());
        assertEquals(List.of(), errorsOf(new RetryOptions().parameterOverrides(Map.of("A", "1"))), "a named override");
        assertEquals(
                List.of("a"),
                new RetryOptions().agentLossPatternsExtra(List.of("a")).getAgentLossPatternsExtra());
        assertEquals(
                List.of("b"), new RetryOptions().agentLossPatterns(List.of("b")).getAgentLossPatterns());
        assertEquals(
                RetryOptions.DEFAULT_AGENT_LOSS_PATTERNS,
                new RetryOptions().agentLossPatterns(null).getAgentLossPatterns());
        assertEquals(
                Map.of("A", "1"),
                new RetryOptions().parameterOverrides(Map.of("A", "1")).getParameterOverrides());
        assertEquals(
                List.of("b", "c"),
                new RetryOptions()
                        .agentLossPatterns(List.of("b"))
                        .agentLossPatternsExtra(List.of("c"))
                        .allAgentLossPatterns());
        assertTrue(new RetryOptions().checkRegexp(true).regexpForRerun("x").regexpActive());
        assertFalse(new RetryOptions().checkRegexp(true).regexpForRerun(" ").regexpActive());
        assertFalse(new RetryOptions().regexpForRerun("x").regexpActive());
    }
}
