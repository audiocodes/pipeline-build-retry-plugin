package io.jenkins.plugins.pipelinebuildretry.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.util.XStream2;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NeverRetryTest {

    private static final List<String> TWO = List.of("COMPILATION ERROR", "error: cannot find symbol");

    private static RetryOptions opts() {
        return new RetryOptions().neverRetryPatterns(TWO);
    }

    private static RunSnapshot failed(ScanResult scan) {
        return new RunSnapshot().result("FAILURE").logScan(scan);
    }

    private static ScanResult scan(Map<String, Boolean> matched) {
        Map<String, Boolean> all = new LinkedHashMap<>();
        all.put(ScanPlan.never(1), false);
        all.put(ScanPlan.never(2), false);
        all.putAll(matched);
        return ScanResult.matched(all).linesRead(100);
    }

    private static boolean hasDetail(Decision d, String text) {
        return d.getDetails().stream().anyMatch(l -> l.contains(text));
    }

    @Test
    void aMatchStopsTheRetryAndNamesThePatternByIndex() {
        Decision d = RetryDecision.decide(
                opts(), failed(scan(Map.of(ScanPlan.never(2), true)).matchLine(ScanPlan.never(2), 42)));
        assertFalse(d.isWillRetry(), "no retry");
        assertEquals("never-retry-matched: pattern #2 at console line 42", d.getReason());
        assertEquals(2, d.getNeverRetryPattern(), "index");
        assertTrue(d.isNeverRetryChecked(), "checked");
        assertEquals(2, d.toMap().get("neverRetryPattern"), "the decision step returns the index");
        assertEquals(
                "[Build Retry] FAILURE → no retry (never-retry-matched: pattern #2 at console line 42)",
                RetryDecision.summary(d));
        assertFalse(d.getReason().contains("cannot find symbol"), "the pattern text is never in the reason");
    }

    @Test
    void noMatchLetsTheRetryGoAhead() {
        Decision d = RetryDecision.decide(opts(), failed(scan(Map.of())));
        assertTrue(d.isWillRetry(), "retry");
        assertTrue(d.isNeverRetryChecked(), "checked");
        assertNull(d.getNeverRetryPattern(), "no index");
        assertNull(d.toMap().get("neverRetryPattern"), "null in the step result");
    }

    @Test
    void theLowestMatchingIndexIsReported() {
        Decision d = RetryDecision.decide(
                opts(),
                failed(scan(Map.of(ScanPlan.never(1), true, ScanPlan.never(2), true))
                        .matchLine(ScanPlan.never(1), 90)
                        .matchLine(ScanPlan.never(2), 10)));
        assertEquals(1, d.getNeverRetryPattern());
        assertEquals("never-retry-matched: pattern #1 at console line 90", d.getReason());
    }

    @Test
    void neverRetryBeatsEveryRetrySignal() {
        ScanResult all = scan(Map.of(ScanPlan.never(1), true, ScanPlan.AGENT_LOSS, true, ScanPlan.REGEXP, true))
                .matchLine(ScanPlan.never(1), 7);
        RetryOptions o = opts().rerunOnAgentLoss(true).checkRegexp(true).regexpForRerun("x");
        Decision loss = RetryDecision.decide(o, failed(all));
        assertEquals("never-retry-matched: pattern #1 at console line 7", loss.getReason(), "over agent loss");
        assertFalse(loss.isAgentLossOverride(), "the agent-loss override never ran");

        Decision infra = RetryDecision.decide(
                opts().rerunIfAborted("infra"),
                failed(all).result("ABORTED").errorClasses(List.of("hudson.remoting.ChannelClosedException")));
        assertEquals("infra", infra.getAbortKind(), "abort kind still read");
        assertTrue(infra.getReason().startsWith("never-retry-matched"), "over an infra abort");

        Decision unstable =
                RetryDecision.decide(opts().rerunIfUnstable(true), failed(all).result("UNSTABLE"));
        assertTrue(unstable.getReason().startsWith("never-retry-matched"), "UNSTABLE too");
    }

    @Test
    void earlierGatesKeepTheirOwnReasons() {
        ScanResult hit = scan(Map.of(ScanPlan.never(1), true)).matchLine(ScanPlan.never(1), 3);
        assertEquals(
                "success",
                RetryDecision.decide(opts(), failed(hit).result("SUCCESS")).getReason());
        assertEquals(
                "aborted-user-not-enabled",
                RetryDecision.decide(
                                opts(),
                                failed(hit)
                                        .result("ABORTED")
                                        .interruptionCauses(List.of(RetryDecision.USER_INTERRUPTION)))
                        .getReason());
        assertTrue(RetryDecision.decide(opts(), failed(hit).pointOfNoReturn("deploy"))
                .getReason()
                .startsWith("point-of-no-return"));
    }

    @Test
    void aFailedScanDoesNotBlockTheRetry() {
        Decision timedOut = RetryDecision.decide(opts(), failed(scan(Map.of()).timedOut(true)));
        assertTrue(timedOut.isWillRetry(), "timeout: retry anyway");
        assertTrue(hasDetail(timedOut, "never-retry gate: not applied (scan timed out)"), "detail");
        Decision io = RetryDecision.decide(opts(), failed(scan(Map.of()).ioError(true)));
        assertTrue(io.isWillRetry(), "unreadable log: retry anyway");
        assertTrue(hasDetail(io, "never-retry gate: not applied (log unreadable)"), "detail");
        Decision late = RetryDecision.decide(
                opts(),
                failed(scan(Map.of(ScanPlan.never(2), true))
                        .matchLine(ScanPlan.never(2), 5)
                        .timedOut(true)));
        assertTrue(late.getReason().startsWith("never-retry-matched"), "a match found before the timeout counts");
    }

    @Test
    void theLineNumberIsGivenOnlyForTheConsole() {
        ScanResult stages = scan(Map.of(ScanPlan.never(1), true))
                .matchLine(ScanPlan.never(1), 4)
                .scope("failedStages");
        assertEquals(
                "never-retry-matched: pattern #1 in the failed stages' logs",
                RetryDecision.decide(opts(), failed(stages)).getReason());
        ScanResult errorLine = scan(Map.of(ScanPlan.never(1), true))
                .matchLine(ScanPlan.never(1), 100)
                .errorLines(1)
                .consoleLines(99);
        assertEquals(
                "never-retry-matched: pattern #1 in a stage error line",
                RetryDecision.decide(opts(), failed(errorLine)).getReason());
        ScanResult console = scan(Map.of(ScanPlan.never(1), true))
                .matchLine(ScanPlan.never(1), 99)
                .errorLines(1)
                .consoleLines(99);
        assertEquals(
                "never-retry-matched: pattern #1 at console line 99",
                RetryDecision.decide(opts(), failed(console)).getReason());
        ScanResult stopped = scan(Map.of(ScanPlan.never(1), true))
                .matchLine(ScanPlan.never(1), 100)
                .errorLines(1);
        assertEquals(
                "never-retry-matched: pattern #1 at console line 100",
                RetryDecision.decide(opts(), failed(stopped)).getReason(),
                "a console not read to its end holds the match");
    }

    @Test
    void theGateRunsInADryRun() {
        Decision d = RetryDecision.decide(
                opts().dryRun(true),
                failed(scan(Map.of(ScanPlan.never(1), true)).matchLine(ScanPlan.never(1), 1)));
        assertFalse(d.isWouldRetry(), "a dry run would not retry either");
        assertTrue(d.getReason().startsWith("never-retry-matched"));
    }

    @Test
    void theScanPlanHasOneGroupPerPattern() {
        ScanPlan plan = RetryDecision.logScanPlan(opts(), new RunSnapshot().result("FAILURE"));
        assertEquals(List.of("COMPILATION ERROR"), plan.getGroups().get(ScanPlan.never(1)));
        assertEquals(List.of("error: cannot find symbol"), plan.getGroups().get(ScanPlan.never(2)));
        assertFalse(plan.getGroups().containsKey(ScanPlan.REGEXP), "no regexp group without the regexp gate");
        assertTrue(
                RetryDecision.logScanPlan(
                                opts().maxSchedule(1),
                                new RunSnapshot().result("FAILURE").retryCount(1))
                        .isEmpty(),
                "at the limit nothing is scanned");
        assertTrue(
                RetryDecision.logScanPlan(opts(), new RunSnapshot().result("SUCCESS"))
                        .isEmpty(),
                "a passing build is not scanned");
        assertTrue(RetryDecision.logScanPlan(new RetryOptions(), new RunSnapshot().result("FAILURE"))
                .isEmpty());
        assertTrue(ScanPlan.isNever(ScanPlan.never(3)));
        assertFalse(ScanPlan.isNever(ScanPlan.REGEXP));
    }

    @Test
    void atTheLimitTheLimitGateDecides() {
        Decision d = RetryDecision.decide(
                opts().maxSchedule(1), new RunSnapshot().result("FAILURE").retryCount(1));
        assertEquals("max-reached", d.getReason());
        assertFalse(d.isNeverRetryChecked(), "not checked without a scan");
    }

    @Test
    void validationNamesThePatternByIndexAndCapsTheList() {
        RetryOptions bad = new RetryOptions().neverRetryPatterns(List.of("ok", "SECRET[("));
        List<String> errors = bad.validate().errors();
        assertEquals(1, errors.size(), "one error");
        assertTrue(errors.get(0).startsWith("never-retry pattern #2 is not a valid regular expression"), errors.get(0));
        assertFalse(errors.get(0).contains("SECRET"), "never the text");

        List<String> many = new ArrayList<>(Collections.nCopies(RetryOptions.MAX_NEVER_RETRY_PATTERNS + 1, "x"));
        assertEquals(
                List.of("neverRetryPatterns has 21 patterns; at most 20 are allowed"),
                new RetryOptions().neverRetryPatterns(many).validate().errors());
        assertEquals(
                List.of(),
                new RetryOptions()
                        .neverRetryPatterns(many.subList(0, 20))
                        .validate()
                        .errors());
        assertTrue(
                RetryDecision.decide(new RetryOptions().neverRetryPatterns(many), new RunSnapshot().result("FAILURE"))
                        .getReason()
                        .startsWith("invalid-config"));
    }

    @Test
    void copyKeepsTheListAndOldDataReadsAsEmpty() {
        RetryOptions c = opts().copy();
        assertEquals(TWO, c.getNeverRetryPatterns(), "copy");
        XStream2 x = new XStream2();
        String xml = x.toXML(new RetryOptions().maxSchedule(2));
        String before = xml.replaceAll("(?s)\\s*<neverRetryPatterns.*?(</neverRetryPatterns>|/>)", "");
        assertFalse(before.contains("neverRetryPatterns"), before);
        RetryOptions old = (RetryOptions) x.fromXML(before);
        assertEquals(List.of(), old.getNeverRetryPatterns(), "saved before the option existed");
        assertEquals(List.of(), old.copy().getNeverRetryPatterns(), "copy of old data");
        assertEquals(List.of(), old.validate().errors(), "validates");
        assertTrue(
                RetryDecision.logScanPlan(old, new RunSnapshot().result("FAILURE"))
                        .isEmpty(),
                "plans nothing");
        String decisionXml = x.toXML(Decision.noRetry("FAILURE", "retry"));
        assertFalse(decisionXml.contains("neverRetryPattern>"), "a null index is not written: " + decisionXml);
        Decision oldDecision =
                (Decision) x.fromXML(decisionXml.replaceAll("\\s*<neverRetryChecked>.*?</neverRetryChecked>", ""));
        assertNull(oldDecision.getNeverRetryPattern(), "old decision");
        assertNull(oldDecision.toMap().get("neverRetryPattern"));
    }
}
