package io.jenkins.plugins.pipelinebuildretry.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LogScannerTest {

    private static final List<String> LOSS = RetryOptions.DEFAULT_AGENT_LOSS_PATTERNS;

    private static ScanResult scan(String text, Map<String, List<String>> groups) {
        return scan(text, groups, 5000L, Map.of());
    }

    private static ScanResult scan(
            String text, Map<String, List<String>> groups, long timeout, Map<String, List<String>> excludes) {
        return LogScanner.scan(new StringReader(text), groups, timeout, excludes);
    }

    private static boolean regexp(String text, String pattern) {
        return scan(text, Map.of("regexp", List.of(pattern))).isMatched("regexp");
    }

    private static boolean k8s(String line) {
        return scan(
                        line + "\n",
                        Map.of("agentLoss", LOSS),
                        5000L,
                        Map.of("agentLoss", RetryOptions.AGENT_LOSS_EXCLUDES))
                .isMatched("agentLoss");
    }

    private static boolean lossLine(String line) {
        return scan(line + "\n", Map.of("agentLoss", LOSS)).isMatched("agentLoss");
    }

    private static boolean scanThreadAlive() throws InterruptedException {
        long until = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < until && scanThreadRunning()) {
            Thread.sleep(50L);
        }
        return scanThreadRunning();
    }

    private static boolean scanThreadRunning() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals(LogScanner.SCAN_THREAD) && t.isAlive());
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    @Test
    void matchesLineByLine() {
        assertTrue(regexp("a\nConnection reset by peer\nb", "Connection reset"), "finds a substring match on one line");
        assertFalse(regexp("all good\n", "Connection reset"), "reports no match");
        assertFalse(regexp("foo\nbar\n", "foo.*bar"), "never matches across lines");
        assertFalse(regexp("foo\nbar\n", "foo\\nbar"), "a newline in the pattern never matches");
        ScanResult multi =
                scan("Pod ns/agent-1 was just deleted\nboom\n", Map.of("regexp", List.of("boom"), "agentLoss", LOSS));
        assertEquals(Map.of("regexp", true, "agentLoss", true), multi.getMatched(), "several groups in one pass");
    }

    @Test
    void kubernetesReaperLines() {
        assertTrue(k8s("ci/agent-x1 Container jnlp was terminated."), "one terminated container is agent loss");
        assertTrue(
                k8s("ci/agent-x1 Containers jnlp,maven were terminated. Reason: Evicted, Message: The node was low on"
                        + " resource: memory."),
                "several terminated containers are agent loss");
        assertTrue(k8s("ci/agent-x1 Pod just failed. Reason: NodeLost, Message: node gone."), "a failed pod is loss");
        assertFalse(
                k8s("ci/agent-x1 Pod just failed. Reason: DeadlineExceeded, Message: Pod was active on the node longer"
                        + " than the specified deadline."),
                "a pod past its deadline is not agent loss");
        assertTrue(
                k8s("ci/agent-x1 Pod just failed. Reason: DeadlineExceeded. Pod was evicted by the Kubernetes Eviction"
                        + " API."),
                "an eviction on an excluded line still counts");
        assertTrue(k8s("Pod ci/agent-x1 was just deleted"), "a deleted pod is agent loss");
    }

    @Test
    void remotingChannelLines() {
        assertFalse(
                lossLine("Caused by: java.nio.channels.ClosedChannelException"),
                "an application ClosedChannelException is not agent loss");
        assertFalse(
                lossLine("io.example.ChannelClosedException: peer went away"),
                "nor a bare ChannelClosedException from another library");
        assertTrue(
                lossLine("hudson.remoting.ChannelClosedException: Channel \"hudson.remoting.Channel@1:agent-1\":"
                        + " channel is already closed"),
                "the remoting ChannelClosedException is");
        assertTrue(
                lossLine("agent-1 seems to be removed or offline (hudson.remoting.ChannelClosedException: x); will"
                        + " wait for 5 min"),
                "and the agent-offline line that quotes it still matches");
    }

    @Test
    void catastrophicRegexpTimesOut() throws Exception {
        long t0 = System.currentTimeMillis();
        ScanResult slow = scan(repeat('a', 40) + "!\n", Map.of("regexp", List.of("(a+)+\\1$")), 500L, Map.of());
        long took = System.currentTimeMillis() - t0;
        assertTrue(slow.isTimedOut(), "catastrophic regexp times out");
        assertFalse(slow.isMatched("regexp"), "and reports no match");
        assertTrue(took < 5000L, "and returns within 5s");
        assertFalse(scanThreadAlive(), "and the scan thread stops");
    }

    @Test
    void slowAndBrokenReaders() throws Exception {
        Reader sleepy = new Reader() {
            @Override
            public int read(char[] buf, int off, int len) throws IOException {
                try {
                    Thread.sleep(10000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }

            @Override
            public void close() {}
        };
        assertTrue(
                LogScanner.scan(sleepy, Map.of("regexp", List.of("x")), 300L, Map.of())
                        .isTimedOut(),
                "a log that reads too slowly times out");
        assertFalse(scanThreadAlive(), "and its scan thread stops");
        Reader broken = new Reader() {
            @Override
            public int read(char[] buf, int off, int len) throws IOException {
                throw new IOException("log gone");
            }

            @Override
            public void close() {}
        };
        assertTrue(
                LogScanner.scan(broken, Map.of("regexp", List.of("x")), 5000L, Map.of())
                        .isIoError(),
                "an unreadable log is reported");
        assertTrue(scan("x\n", Map.of("regexp", List.of("("))).isPatternError(), "bad pattern is reported");
        assertEquals(0, scan("x\n", Map.of()).getLinesRead(), "no groups means nothing to read");
    }

    @Test
    void ownLinesAreSkipped() {
        assertFalse(
                regexp(
                        "[Build Retry] retry 2/3 of build #40\n12:00:01 [Build Retry] point of no return: x; this"
                                + " build will not be retried\n12:00:02 [Build Retry] retry 3/3 of build #41\n",
                        "retry"),
                "the plugin's own lines are never matched");
        assertTrue(regexp("[Build Retry] retry 1/3\nplease retry later\n", "retry"), "other lines still match");
    }

    @Test
    void longLines() {
        String huge = repeat('x', 300000) + "Connection reset";
        ScanResult big = scan("a\n" + huge + "\nb\n", Map.of("regexp", List.of("Connection reset$")));
        assertEquals(
                List.of(true, 1, 2),
                List.of(big.isMatched("regexp"), big.getCutLines(), big.getLinesRead()),
                "a 300K-char line with the match at its end matches");
        ScanResult two = scan("a\n" + huge + "\nb\n" + huge + "\n", Map.of("regexp", List.of("nomatch")));
        assertEquals(
                List.of(4, 2),
                List.of(two.getLinesRead(), two.getCutLines()),
                "linesRead counts the physical lines read, however long");
        ScanResult across =
                scan(repeat('a', 65530) + "NEEDLE" + repeat('b', 100000) + "\n", Map.of("regexp", List.of("NEEDLE")));
        assertEquals(
                List.of(true, 1), List.of(across.isMatched("regexp"), across.getCutLines()), "a match across a cut");
        assertEquals(
                0,
                scan(repeat('a', 65536) + "\n", Map.of("regexp", List.of("b"))).getCutLines(),
                "a line of exactly 64K chars is not cut");
        String progressText = "step 1%\rstep 50%\rstep 100%\r\ndone\n";
        assertTrue(regexp(progressText, "^step 50%$"), "carriage-return progress output gives separate lines");
        assertEquals(4, scan(progressText, Map.of("regexp", List.of("nomatch"))).getLinesRead());
        String crText = "a\rb\r\nc\n\rd\r\r\ne";
        assertEquals(
                readLineCount(crText),
                scan(crText, Map.of("regexp", List.of("nomatch"))).getLinesRead(),
                "linesRead counts what readLine counts");
        String noteInPiece = repeat('x', 70000) + "\u001B[8mha:AAAA\u001B[0mConnection reset\n";
        assertTrue(regexp(noteInPiece, "xConnection reset"), "a console note is stripped inside a later piece");
        String ownFirst = "[Build Retry] retry 1/3 " + repeat('x', 70000) + "Connection reset\n";
        assertTrue(regexp(ownFirst, "Connection reset"), "the own prefix skips only the piece that holds it");
    }

    @Test
    void endlessLineTimesOut() throws Exception {
        Reader endless = new Reader() {
            @Override
            public int read(char[] buf, int off, int len) {
                Arrays.fill(buf, off, off + len, 'a');
                return len;
            }

            @Override
            public void close() {}
        };
        ScanResult r = LogScanner.scan(endless, Map.of("regexp", List.of("b")), 300L, Map.of());
        assertEquals(List.of(true, false), List.of(r.isTimedOut(), r.isMatched("regexp")), "endless line times out");
        assertFalse(scanThreadAlive(), "and its scan thread stops");
    }

    private static int readLineCount(String t) {
        return (int) new BufferedReader(new StringReader(t)).lines().count();
    }

    // Feeds 1 to 3 chars per read, so a \r and its \n land in different chunks.
    private static Reader trickle(String t, long seed) {
        Random rnd = new Random(seed);
        int[] at = {0};
        return new Reader() {
            @Override
            public int read(char[] buf, int off, int len) {
                if (at[0] >= t.length()) {
                    return -1;
                }
                int n = Math.min(Math.min(len, 1 + rnd.nextInt(3)), t.length() - at[0]);
                t.getChars(at[0], at[0] + n, buf, off);
                at[0] += n;
                return n;
            }

            @Override
            public void close() {}
        };
    }

    private static List<String> cutAll(LogScanner.LineCutter c) {
        List<String> out = new ArrayList<>();
        try {
            String p;
            while ((p = c.next()) != null) {
                out.add(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    @Test
    void lineCutterFuzz() {
        Random fuzz = new Random(7);
        List<String> alphabet = List.of("a", "b", "c", "\r", "\n", "\r\n");
        List<String> lineMismatch = new ArrayList<>();
        List<String> pieceMismatch = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            StringBuilder sb = new StringBuilder();
            int len = fuzz.nextInt(60);
            for (int k = 0; k < len; k++) {
                sb.append(alphabet.get(fuzz.nextInt(alphabet.size())));
            }
            String t = sb.toString();
            List<String> want = new BufferedReader(new StringReader(t)).lines().toList();
            LogScanner.LineCutter wide = new LogScanner.LineCutter(trickle(t, i), 1000, 10);
            List<String> got = cutAll(wide);
            if (!got.equals(want) || wide.getLines() != want.size() || wide.getCutLines() != 0) {
                lineMismatch.add(t);
            }
            LogScanner.LineCutter narrow = new LogScanner.LineCutter(trickle(t, i + 1000), 8, 3);
            List<String> pieces = cutAll(narrow);
            boolean covered = want.stream().allMatch(line -> {
                for (int s0 = 0; s0 <= line.length(); s0++) {
                    for (int e0 = s0; e0 <= Math.min(line.length(), s0 + 4); e0++) {
                        String sub = line.substring(s0, e0);
                        if (pieces.stream().noneMatch(p -> p.contains(sub))) {
                            return false;
                        }
                    }
                }
                return true;
            });
            long longLines = want.stream().filter(l -> l.length() > 8).count();
            if (!covered
                    || narrow.getLines() != want.size()
                    || pieces.stream().anyMatch(p -> p.length() > 8)
                    || narrow.getCutLines() != longLines) {
                pieceMismatch.add(t);
            }
        }
        assertEquals(List.of(), lineMismatch, "with a wide max the cutter gives exactly the readLine lines");
        assertEquals(List.of(), pieceMismatch, "with max 8 and overlap 3 every substring up to 4 chars stays whole");
    }

    private static ChainedLogReader chained(List<String> texts) {
        List<ChainedLogReader.Source> sources = new ArrayList<>();
        for (String t : texts) {
            sources.add(() -> new StringReader(t));
        }
        return new ChainedLogReader(sources);
    }

    private static String readAll(Reader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[64];
        int n;
        while ((n = r.read(buf, 0, buf.length)) >= 0) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    @Test
    void chainedLogReader() throws Exception {
        assertEquals(
                "a\nb\nc\n",
                readAll(chained(List.of("a", "b\n", "", "c"))),
                "sources are joined, each ending with one newline");
        assertTrue(
                LogScanner.scan(
                                chained(List.of("tail without newline", "ERROR: x")),
                                Map.of("regexp", List.of("^ERROR: x$")),
                                5000L,
                                Map.of())
                        .isMatched("regexp"),
                "an unterminated last line still matches on its own");
        AtomicInteger opened = new AtomicInteger();
        ChainedLogReader lazy = new ChainedLogReader(List.of(
                () -> {
                    opened.incrementAndGet();
                    return new StringReader("hit\n");
                },
                () -> {
                    opened.incrementAndGet();
                    return new StringReader("more\n");
                }));
        ScanResult lazyScan = LogScanner.scan(lazy, Map.of("regexp", List.of("hit")), 5000L, Map.of());
        assertEquals(
                List.of(true, 1),
                List.of(lazyScan.isMatched("regexp"), opened.get()),
                "a match in the first source never opens the second");
        ChainedLogReader failing = new ChainedLogReader(List.of(() -> {
            throw new IllegalStateException("node log gone");
        }));
        assertTrue(
                LogScanner.scan(failing, Map.of("regexp", List.of("x")), 5000L, Map.of())
                        .isIoError(),
                "a source that throws is an ioError");
    }

    @Test
    void stuckSourceIsClosedAndNextNeverOpened() throws Exception {
        AtomicBoolean sleepyClosed = new AtomicBoolean();
        Reader sleepySource = new Reader() {
            @Override
            public int read(char[] buf, int off, int len) {
                try {
                    Thread.sleep(10000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }

            @Override
            public void close() {
                sleepyClosed.set(true);
            }
        };
        AtomicInteger openedAfter = new AtomicInteger();
        ChainedLogReader stuck = new ChainedLogReader(List.of(() -> sleepySource, () -> {
            openedAfter.incrementAndGet();
            return new StringReader("x\n");
        }));
        assertTrue(
                LogScanner.scan(stuck, Map.of("regexp", List.of("x")), 300L, Map.of())
                        .isTimedOut(),
                "a stuck source times out");
        assertEquals(
                List.of(true, 0),
                List.of(sleepyClosed.get(), openedAfter.get()),
                "and is closed, and the next source is never opened");
        assertFalse(scanThreadAlive(), "and its scan thread stops");
    }

    @Test
    void stuckReadThatBlocksCloseStillReturns() throws Exception {
        Object readLock = new Object();
        AtomicBoolean released = new AtomicBoolean();
        AtomicBoolean lockedClosed = new AtomicBoolean();
        Reader lockedSource = new Reader() {
            @Override
            public int read(char[] buf, int off, int len) {
                synchronized (readLock) {
                    long end = System.currentTimeMillis() + 8000L;
                    while (!released.get() && System.currentTimeMillis() < end) {
                        try {
                            Thread.sleep(50L);
                        } catch (InterruptedException ignored) {
                            // keep holding the lock, like a stuck remote read
                        }
                    }
                }
                return -1;
            }

            @Override
            public void close() {
                synchronized (readLock) {
                    lockedClosed.set(true);
                }
            }
        };
        long t0 = System.currentTimeMillis();
        ScanResult r = LogScanner.scan(
                new ChainedLogReader(List.of(() -> lockedSource)), Map.of("regexp", List.of("x")), 300L, Map.of());
        long took = System.currentTimeMillis() - t0;
        released.set(true);
        assertEquals(
                List.of(true, true),
                List.of(r.isTimedOut(), took < 3000L),
                "a stuck read that also blocks close() still returns soon after the timeout");
        long until = System.currentTimeMillis() + 5000L;
        while (!lockedClosed.get() && System.currentTimeMillis() < until) {
            Thread.onSpinWait();
        }
        assertTrue(lockedClosed.get(), "and the reader is closed once the stuck read lets go");
    }

    private static final class TrackingReader extends StringReader {
        final AtomicBoolean closed = new AtomicBoolean();

        TrackingReader(String s) {
            super(s);
        }

        @Override
        public void close() {
            closed.set(true);
            super.close();
        }
    }

    @Test
    void everyPathClosesTheReader() {
        TrackingReader none = new TrackingReader("x\n");
        LogScanner.scan(none, Map.of(), 5000L, Map.of());
        assertTrue(none.closed.get(), "no groups");
        TrackingReader bad = new TrackingReader("x\n");
        LogScanner.scan(bad, Map.of("regexp", List.of("(")), 5000L, Map.of());
        assertTrue(bad.closed.get(), "bad pattern");
        TrackingReader badExclude = new TrackingReader("x\n");
        assertTrue(
                LogScanner.scan(badExclude, Map.of("regexp", List.of("x")), 5000L, Map.of("regexp", List.of("[")))
                        .isPatternError(),
                "a bad exclude is a pattern error");
        assertTrue(badExclude.closed.get(), "bad exclude");
        TrackingReader normal = new TrackingReader("x\n");
        assertTrue(LogScanner.scan(normal, Map.of("regexp", List.of("x")), 5000L, Map.of())
                .isMatched("regexp"));
        assertTrue(normal.closed.get(), "normal scan");
    }

    @Test
    void excludesApplyPerGroup() {
        ScanResult r = scan(
                "boom here\n",
                Map.of("regexp", List.of("boom"), "agentLoss", List.of("boom")),
                5000L,
                Map.of("agentLoss", List.of("here")));
        assertEquals(Map.of("regexp", true, "agentLoss", false), r.getMatched());
    }

    @Test
    void consoleNoteAtLineStart() {
        assertTrue(regexp("\u001B[8mha:AAAA\u001B[0mConnection reset\n", "^Connection reset"), "note at index 0");
        assertTrue(regexp("x\u001B[8mha:AAAA\u001B[0my\n", "^xy$"), "note in the middle");
    }

    @Test
    void lineCutterLargeFuzz() {
        Random fuzz = new Random(11);
        StringBuilder sb = new StringBuilder();
        List<String> ends = List.of("\n", "\r", "\r\n");
        while (sb.length() < 300000) {
            int len = fuzz.nextInt(400);
            for (int k = 0; k < len; k++) {
                sb.append((char) ('a' + fuzz.nextInt(26)));
            }
            sb.append(ends.get(fuzz.nextInt(ends.size())));
        }
        String t = sb.toString();
        List<String> want = new BufferedReader(new StringReader(t)).lines().toList();
        LogScanner.LineCutter c = new LogScanner.LineCutter(new StringReader(t), 1000, 10);
        assertEquals(want, cutAll(c), "past the 32K compaction point the lines still match readLine");
        assertEquals(want, cutAll(new LogScanner.LineCutter(trickle(t, 5), 1000, 10)), "and with tiny reads");
    }

    @Test
    void closingTheChainClosesItsOpenSource() throws Exception {
        TrackingReader first = new TrackingReader("abc\n");
        ChainedLogReader r = new ChainedLogReader(List.of(() -> first));
        char[] buf = new char[2];
        assertEquals(2, r.read(buf, 0, 2));
        assertFalse(first.closed.get());
        r.close();
        assertTrue(first.closed.get(), "close() closes the source being read");
        assertEquals(-1, r.read(buf, 0, 2), "and nothing more is read");
        assertEquals(0, new ChainedLogReader(List.of()).read(buf, 0, 0), "a zero-length read reads nothing");
        assertEquals(-1, new ChainedLogReader(null).read(buf, 0, 2), "no sources, no text");
    }

    @Test
    void theFirstMatchingLineOfEachGroupIsKept() {
        ScanResult s = scan(
                "a\nNEEDLE one\nb\nNEEDLE two\nOTHER\n",
                Map.of("g1", List.of("NEEDLE"), "g2", List.of("OTHER"), "g3", List.of("absent")));
        assertEquals(2, s.getMatchLine("g1"), "first match, 1-based");
        assertEquals(5, s.getMatchLine("g2"), "each group its own line");
        assertNull(s.getMatchLine("g3"), "no match, no line");
        ScanResult cut =
                scan("a\n" + repeat('x', 70000) + "NEEDLE" + repeat('y', 10) + "\nb\n", Map.of("g", List.of("NEEDLE")));
        assertEquals(2, cut.getMatchLine("g"), "a match in the second 64K piece is on the line being cut");
        ScanResult early = scan("a\nNEEDLE" + repeat('x', 70000) + "\nb\n", Map.of("g", List.of("NEEDLE")));
        assertEquals(2, early.getMatchLine("g"), "a match in the first 64K piece, before the line ends");
        ScanResult first = scan("NEEDLE\n", Map.of("g", List.of("NEEDLE")));
        assertEquals(1, first.getMatchLine("g"), "the first line is line 1");
        ScanResult noEnd = scan("a\nb\nNEEDLE", Map.of("g", List.of("NEEDLE")));
        assertEquals(3, noEnd.getMatchLine("g"), "a last line with no line end");
        ScanResult cr = scan("a\rb\r\nNEEDLE\n", Map.of("g", List.of("NEEDLE")));
        assertEquals(3, cr.getMatchLine("g"), "carriage returns end lines too");
        ScanResult merged = ScanResult.of(List.of("x")).merge(cut);
        assertEquals(2, merged.getMatchLine("g"), "merge keeps the line");
    }

    @Test
    void theLineCounterCountsLikeTheCutter() throws Exception {
        for (String text : List.of("", "a", "a\n", "a\nb", "a\r\nb\r\n", "a\rb\r", "\n\n", "a\r\n\r\nb")) {
            LineCountingReader counter = new LineCountingReader(new StringReader(text));
            LogScanner.LineCutter cutter = new LogScanner.LineCutter(counter, 65536, 1024);
            while (cutter.next() != null) {
                // read to the end
            }
            assertTrue(counter.isDone(), "done: " + text);
            assertEquals(
                    cutter.getLines(),
                    counter.getLines(),
                    "lines of " + text.replace("\r", "\\r").replace("\n", "\\n"));
        }
        char[] split = new char[1];
        LineCountingReader crlf = new LineCountingReader(new StringReader("a\r\nb"));
        while (crlf.read(split, 0, 1) > 0) {
            // one char at a time, so the \r\n pair is split across reads
        }
        assertEquals(2, crlf.getLines(), "a split \\r\\n is one line end");
        LineCountingReader partial = new LineCountingReader(new StringReader("a\nb\n"));
        partial.read(split, 0, 1);
        assertFalse(partial.isDone(), "not at the end yet");
    }
}
