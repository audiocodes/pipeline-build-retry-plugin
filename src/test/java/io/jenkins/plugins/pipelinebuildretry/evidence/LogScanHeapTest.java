package io.jenkins.plugins.pipelinebuildretry.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogScanHeapTest {

    private final List<Long> pauses = new ArrayList<>();

    private LogScan.HeapWait wait(long timeoutMs, LogScan.Headroom... readings) throws InterruptedException {
        Deque<LogScan.Headroom> left = new ArrayDeque<>(List.of(readings));
        return LogScan.waitForHeap(timeoutMs, () -> left.size() > 1 ? left.poll() : left.peek(), pauses::add);
    }

    @Test
    void noFiguresMeansNoWait() throws InterruptedException {
        assertEquals(0L, LogScan.waitForHeap(30_000L, () -> null, pauses::add).waitedMs());
        assertEquals(List.of(), pauses);
    }

    @Test
    void enoughHeadroomMeansNoWait() throws InterruptedException {
        LogScan.HeapWait w = wait(30_000L, new LogScan.Headroom(3L << 20, 2L << 20));
        assertEquals(0L, w.waitedMs());
        assertEquals(3L, w.freeMiB());
    }

    @Test
    void waitsAQuarterOfTheTimeoutAtMost() throws InterruptedException {
        LogScan.HeapWait w = wait(2_000L, new LogScan.Headroom(1L << 20, 2L << 20));
        assertEquals(500L, w.waitedMs(), "timeout/4");
        assertEquals(List.of(250L, 250L), pauses, "250 ms steps");
    }

    @Test
    void waitsFiveSecondsAtMost() throws InterruptedException {
        assertEquals(5_000L, wait(60_000L, new LogScan.Headroom(0L, 1L)).waitedMs());
    }

    @Test
    void stopsWaitingOnceHeadroomReturns() throws InterruptedException {
        LogScan.HeapWait w =
                wait(30_000L, new LogScan.Headroom(1L << 20, 2L << 20), new LogScan.Headroom(5L << 20, 2L << 20));
        assertEquals(250L, w.waitedMs());
        assertEquals(5L, w.freeMiB());
    }

    @Test
    void lastStepIsCutToTheLimit() throws InterruptedException {
        wait(1_200L, new LogScan.Headroom(0L, 1L));
        assertEquals(List.of(250L, 50L), pauses, "300 ms limit");
    }

    @Test
    void streamingNeedsWorkflowApi1398() {
        assertTrue(LogScan.streamsPerStep("1398.v1"));
        assertTrue(LogScan.streamsPerStep("1413.v2ff1a_5e720fa_"));
        assertFalse(LogScan.streamsPerStep("1397.vabc"));
        assertFalse(LogScan.streamsPerStep("2.38"));
        assertFalse(LogScan.streamsPerStep("unknown"));
        assertFalse(LogScan.streamsPerStep(null));
    }

    @Test
    void heapFiguresComeFromTheJvm() {
        LogScan.Headroom h = LogScan.heapHeadroom();
        if (h != null) {
            assertTrue(h.need() >= 256L << 20, "at least 256 MiB wanted");
            assertTrue(h.free() >= 0L, "free is not negative");
        }
    }
}
