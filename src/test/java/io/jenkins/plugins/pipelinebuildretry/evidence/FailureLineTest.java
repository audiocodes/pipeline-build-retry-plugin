package io.jenkins.plugins.pipelinebuildretry.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.console.AnnotatedLargeText;
import hudson.model.Result;
import io.jenkins.plugins.pipelinebuildretry.core.FailureFacts;
import io.jenkins.plugins.pipelinebuildretry.core.RunSnapshot;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.framework.io.ByteBuffer;

@WithJenkins
class FailureLineTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    private FailureFacts.Step step(String script) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun r = j.buildAndAssertStatus(Result.FAILURE, p);
        RunSnapshot s = RunSnapshots.of(r, r.getResult());
        return FailureLine.of(r, s.getErrorStepId());
    }

    @Test
    void theFailingShStepGivesItsScriptAndItsLogTail() throws Exception {
        FailureFacts.Step s = step(
                "stage('Build') { node { try { sh 'echo caught; exit 1' } catch (e) { echo 'fine' } } }\n"
                        + "stage('Test') { node { sh 'echo compiling; echo \"fatal: disk quota exceeded\"; exit 3' } }");
        assertEquals("sh", s.function(), s.toString());
        assertTrue(s.arguments().contains("exit 3"), s.arguments());
        assertTrue(
                s.logTail().contains("fatal: disk quota exceeded"), s.logTail().toString());
        assertTrue(s.logTail().stream().noneMatch(l -> l.contains("caught")), "not a caught error in an earlier stage");
    }

    @Test
    void anErrorStepHasNoLogButItsMessage() throws Exception {
        FailureFacts.Step s = step("stage('Test') { error 'boom 7' }");
        assertEquals("error", s.function(), s.toString());
        assertEquals("boom 7", s.arguments());
        assertEquals(List.of(), s.logTail());
    }

    @Test
    void noErrorStageNoStep() throws Exception {
        assertNull(step("error 'outside any stage'"));
    }

    /** Remembers where a read started. */
    private static final class Recording extends AnnotatedLargeText<Object> {
        long start = -1;

        Recording(ByteBuffer b) {
            super(b, StandardCharsets.UTF_8, true, null);
        }

        @Override
        public long writeLogTo(long from, OutputStream out) throws IOException {
            start = from;
            return super.writeLogTo(from, out);
        }
    }

    @Test
    void aHugeLogIsReadOnlyFromItsTail() throws Exception {
        ByteBuffer b = new ByteBuffer();
        byte[] line = "progress line that says nothing\n".getBytes(StandardCharsets.UTF_8);
        while (b.length() < 5_000_000) {
            b.write(line);
        }
        b.write("fatal: out of disk\n".getBytes(StandardCharsets.UTF_8));
        Recording text = new Recording(b);
        List<String> tail = FailureLine.tail(text);
        assertEquals(b.length() - FailureLine.TAIL_BYTES, text.start, "only the last bytes");
        assertEquals(FailureLine.TAIL_LINES, tail.size(), "only the last lines");
        assertEquals("fatal: out of disk", tail.get(tail.size() - 1));
        assertTrue(tail.stream().allMatch(l -> l.equals("progress line that says nothing") || l.startsWith("fatal")));
    }

    @Test
    void aShortLogIsReadWhole() throws Exception {
        ByteBuffer b = new ByteBuffer();
        b.write("one\ntwo\n".getBytes(StandardCharsets.UTF_8));
        Recording text = new Recording(b);
        assertEquals(List.of("one", "two"), FailureLine.tail(text));
        assertEquals(0, text.start);
    }
}
