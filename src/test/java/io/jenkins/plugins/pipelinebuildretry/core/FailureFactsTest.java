package io.jenkins.plugins.pipelinebuildretry.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.pipelinebuildretry.core.RunSnapshot.ErrorLine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FailureFactsTest {

    private static final String CLOSED = "java.nio.channels.ClosedChannelException";

    private static RunSnapshot failed() {
        return new RunSnapshot().result("FAILURE");
    }

    private static RunSnapshot aborted() {
        return new RunSnapshot().result("ABORTED");
    }

    private static String cls(RunSnapshot s) {
        return FailureFacts.failureClass(s);
    }

    private static RunSnapshot line(String stage, int order, String text) {
        return failed().stageErrorLines(List.of(new ErrorLine("7", stage, order, text, true)));
    }

    @Test
    void aPassingBuildHasNoFailureClass() {
        assertNull(cls(new RunSnapshot().result("SUCCESS")));
        assertNull(cls(new RunSnapshot()), "no result yet");
    }

    @Test
    void theClassFollowsTheDecisionsOwnPrecedence() {
        String user = RetryDecision.USER_INTERRUPTION;
        String timeout = RetryDecision.TIMEOUT_INTERRUPTION;
        assertEquals("user-abort", cls(aborted().interruptionCauses(List.of(timeout, user))), "user beats timeout");
        assertEquals(
                "superseded",
                cls(aborted().interruptionCauses(List.of(FailureFacts.CANCELLED_CAUSE, timeout))),
                "abortPrevious beats the timeout it interrupted");
        assertEquals(
                "superseded",
                cls(aborted()
                        .interruptionCauses(List.of(
                                "com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.NewPatchSetInterruption"))
                        .retryableCauseSeen(true)),
                "a new patch set beats the agent loss its abort caused");
        assertEquals(
                "superseded",
                cls(aborted()
                        .interruptionCauses(List.of(
                                "org.jenkinsci.plugins.workflow.support.steps.build.BuildTriggerCancelledCause"))),
                "a build its upstream cancelled");
        assertEquals(
                "superseded",
                cls(
                        aborted()
                                .interruptionCauses(
                                        List.of(
                                                "com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.AbandonedPatchsetInterruption"))));
        assertEquals("controller-restart", cls(failed().errorClasses(List.of(FailureFacts.NONRESUMABLE))));
        assertEquals(
                "agent-oom",
                cls(aborted().errorClasses(List.of(CLOSED)).failedAgentReasons(Map.of("pod-1", List.of("OOMKilled")))),
                "an OOM kill beats the closed channel it caused");
        assertEquals(
                "agent-lost",
                cls(aborted().errorClasses(List.of(CLOSED)).failedAgentReasons(Map.of("pod-1", List.of("Error")))));
        assertEquals(
                "agent-lost",
                cls(aborted().retryableCauseSeen(true).interruptionCauses(List.of(timeout))),
                "infra beats timeout");
        assertEquals(
                "agent-lost",
                cls(aborted().logScan(ScanResult.matched(Map.of(ScanPlan.AGENT_LOSS, true)))),
                "agent loss found in the log");
        assertEquals("timeout", cls(aborted().interruptionCauses(List.of(timeout))));
        assertEquals("unstable", cls(new RunSnapshot().result("UNSTABLE")));
        assertEquals("aborted-unknown", cls(aborted()));
        assertEquals("not-built", cls(new RunSnapshot().result("NOT_BUILT")));
        assertEquals("script-failure", cls(failed().errorClasses(List.of("hudson.AbortException"))));
        assertEquals(
                "script-failure",
                cls(failed().terminationReasons(List.of("Completed")).errorClasses(List.of("hudson.AbortException"))),
                "a pod that only finished is no OOM");
        assertEquals("pipeline-error", cls(failed().errorClasses(List.of("groovy.lang.MissingPropertyException"))));
        assertEquals("unknown", cls(failed()));
    }

    private static String fp(RunSnapshot s) {
        FailureFacts.Fingerprint f = FailureFacts.fingerprint(s, null);
        return f == null ? null : f.hash();
    }

    private static FailureFacts.Fingerprint sh(String... log) {
        return FailureFacts.fingerprint(
                line("Test", 3, "ERROR: script returned exit code 1"),
                new FailureFacts.Step("sh", "make all", List.of(log)));
    }

    private static final String[] GCC = {
        "+ make all", "cc -c foo.c", "foo.c:12:5: error: 'bar' undeclared (first use in this function)",
        "   12 |     bar = 1;", "      |     ^~~", "make: *** [Makefile:3: foo.o] Error 1"
    };
    private static final String[] MAVEN = {
        "[INFO] BUILD FAILURE",
        "[INFO] Total time:  12.3 s",
        "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test"
                + " (default-test) on project app: There are test failures.",
        "[ERROR] ",
        "[ERROR] Please refer to /ws/app/target/surefire-reports for the individual test results.",
        "[ERROR] -> [Help 1]"
    };
    private static final String[] CURL = {
        "+ curl -fsS https://repo.example/x.tgz",
        "curl: (28) Failed to connect to repo.example port 443 after" + " 10001 ms: Timeout was reached"
    };
    private static final String[] NPM = {
        "> app@1.0.0 test", "> jest", "npm ERR! code ELIFECYCLE", "npm ERR! errno 1", "npm ERR! app@1.0.0 test: `jest`"
    };
    private static final String[] PYTHON = {
        "Traceback (most recent call last):",
        "  File \"run.py\", line 3, in <module>",
        "    main()",
        "KeyError: 'region'"
    };

    @Test
    void differentRootCausesInOneStepGetDifferentFingerprints() {
        List<String> all = List.of(sh(GCC), sh(MAVEN), sh(CURL), sh(NPM), sh(PYTHON)).stream()
                .map(f -> {
                    assertEquals("log-line", f.basis(), f.toString());
                    return f.hash();
                })
                .toList();
        assertEquals(5, all.stream().distinct().count(), all.toString());
        assertEquals(12, all.get(0).length(), all.get(0));
    }

    @Test
    void theRootCauseLineIsTheFirstOfTheLastErrorBlock() {
        assertEquals(
                "foo.c:12:5: error: 'bar' undeclared (first use in this function)",
                FailureFacts.rootCauseLine(List.of(GCC)),
                "make's own line is generic");
        assertTrue(FailureFacts.rootCauseLine(List.of(MAVEN)).startsWith("[ERROR] Failed to execute goal"));
        assertEquals("KeyError: 'region'", FailureFacts.rootCauseLine(List.of(PYTHON)));
        assertEquals(
                "error: first",
                FailureFacts.rootCauseLine(List.of("error: early", "ok", "error: first", "error: second", "done")),
                "the first line of the last block");
        assertEquals(
                "last words", FailureFacts.rootCauseLine(List.of("[Pipeline] sh", "", "last words", " ")), "no match");
        assertEquals(
                "ERROR: deploy failed",
                FailureFacts.rootCauseLine(List.of("\u001B[31mERROR: deploy failed\u001B[0m")),
                "colour codes");
        assertNull(
                FailureFacts.rootCauseLine(List.of(
                        "+ ./build.sh",
                        "[Pipeline] }",
                        "script returned exit code 2",
                        "\tat Foo.bar(Foo.java:1)",
                        "\t... 3 more",
                        "Finished: FAILURE")),
                "only generic lines");
    }

    @Test
    void numbersPodNamesAndPathsDoNotChangeTheFingerprint() {
        FailureFacts.Fingerprint a = sh("fatal: build-17-x8k2q cannot reach /ws/job@2/cache at 10:01:02");
        FailureFacts.Fingerprint b = sh("fatal:  build-18-p3m7z cannot reach /ws/job@3/cache at 11:22:33");
        assertEquals(a, b);
        assertNotEquals(a, sh("fatal: build-17-x8k2q cannot write /ws/job@2/cache"), "another error");
    }

    @Test
    void stageAndStepArgumentsAreInTheHash() {
        FailureFacts.Fingerprint test = sh(CURL);
        assertNotEquals(
                test.hash(),
                FailureFacts.fingerprint(
                                line("Build", 3, "ERROR: script returned exit code 1"),
                                new FailureFacts.Step("sh", "make all", List.of(CURL)))
                        .hash(),
                "another stage");
        assertNotEquals(
                test.hash(),
                FailureFacts.fingerprint(
                                line("Test", 3, "ERROR: script returned exit code 1"),
                                new FailureFacts.Step("sh", "make test", List.of(CURL)))
                        .hash(),
                "another command");
        assertEquals(
                FailureFacts.fingerprint(
                                line("Test", 3, "x"), new FailureFacts.Step("sh", "make build-17", List.of(CURL)))
                        .hash(),
                FailureFacts.fingerprint(
                                line("Test", 9, "y"), new FailureFacts.Step("sh", "make build-18", List.of(CURL)))
                        .hash(),
                "numbers in the command are masked; the finish line no longer counts");
    }

    @Test
    void withoutAUsableLineTheStepAloneIsHashed() {
        FailureFacts.Fingerprint generic = sh("+ make", "script returned exit code 2");
        assertEquals("step", generic.basis());
        assertEquals(generic, sh(), "an empty log");
        assertEquals(
                generic,
                FailureFacts.fingerprint(
                        line("Test", 3, "ERROR: script returned exit code 1"),
                        new FailureFacts.Step("sh", "make all", null)),
                "no log");
        assertNotEquals(generic, sh(CURL));
    }

    @Test
    void secretsAreMaskedBeforeHashing() {
        // Joined at run time so secret scanners do not read the test input as a real URL credential.
        String userInfo = String.join(":", "bot", "pw42x");
        String m = FailureFacts.mask("error: GET https://" + userInfo + "@host/api?token=abc failed: token=abc"
                + " key='s3cr' password=\"hunter\" at C:\\agent\\ws\\x and /home/bot/.netrc");
        for (String secret : List.of("bot:", "pw42x", "abc", "s3cr", "hunter", "agent", "home")) {
            assertFalse(m.contains(secret), secret + " in " + m);
        }
        assertTrue(m.startsWith("error: GET"), m);
        assertEquals(
                FailureFacts.MAX_LINE_CHARS,
                FailureFacts.mask("error " + "x".repeat(1000)).length(),
                "cut");
    }

    @Test
    void withoutAFailingStepTheFinishLineIsHashed() {
        String a = fp(line("Test", 3, "ERROR: exit code 2 on pod build-17-x8k2q in /ws/job@2"));
        String b = fp(line("Test", 9, "ERROR:  exit code 1 on pod build-18-p3m7z in /ws/job@3"));
        assertEquals(a, b, "same stage, same error");
        assertEquals(12, a.length(), a);
        assertEquals(
                "finish-line",
                FailureFacts.fingerprint(line("Test", 3, "ERROR: x"), null).basis());
        assertNotEquals(a, fp(line("Build", 3, "ERROR: exit code 2 on pod build-17-x8k2q in /ws/job@2")), "stage");
        assertNotEquals(a, fp(line("Test", 3, "ERROR: No such saved stash ‘spike’")), "another error");
        assertNull(
                FailureFacts.fingerprint(failed().errorClasses(List.of("hudson.AbortException")), null),
                "no stage error line, no fingerprint");
        assertNull(
                FailureFacts.fingerprint(
                        failed().errorClasses(List.of("hudson.AbortException")),
                        new FailureFacts.Step("sh", "make", List.of(CURL))),
                "no error stage, no fingerprint");
    }

    @Test
    void theFirstErrorLineNamesTheStage() {
        RunSnapshot s = failed().stageErrorLines(List.of(
                new ErrorLine("9", "Deploy", 20, "ERROR: later", true),
                new ErrorLine("4", "Build", 5, "ERROR: first", true)));
        assertEquals("Build", FailureFacts.errorStage(s));
        assertEquals(fp(line("Build", 1, "ERROR: first")), fp(s));
        assertNull(FailureFacts.errorStage(failed()), "none");
    }
}
