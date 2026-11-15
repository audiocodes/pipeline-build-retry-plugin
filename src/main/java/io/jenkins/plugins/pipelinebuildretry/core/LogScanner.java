/*
 * The MIT License
 *
 * Copyright (c) 2026 AudioCodes Ltd. and contributors
 * Portions Copyright (c) 2015-2016 IKEDA Yasuyuki, and the Naginator plugin contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.jenkins.plugins.pipelinebuildretry.core;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** One pass over a log, matching named pattern groups line by line with find(), under a timeout. */
public final class LogScanner {

    // ConsoleNote preamble/postamble: hidden hyperlink blobs that a raw log reader leaves in the text.
    static final Pattern CONSOLE_NOTE = Pattern.compile("\u001B\\[8mha:[^\u001B]*\u001B\\[0m");
    public static final String SCAN_THREAD = "build-retry-log-scan";
    static final String CLOSE_THREAD = "build-retry-log-close";

    private LogScanner() {}

    /** Scans reader for every group; never throws, always closes the reader. */
    public static ScanResult scan(
            Reader reader, Map<String, List<String>> groups, long timeoutMs, Map<String, List<String>> excludes) {
        Map<String, List<String>> named = groups == null ? Map.of() : groups;
        ScanResult result = ScanResult.of(named.keySet());
        if (named.isEmpty()) {
            closeQuietly(reader);
            return result;
        }
        Map<String, List<Pattern>> include = new LinkedHashMap<>();
        Map<String, List<Pattern>> exclude = new LinkedHashMap<>();
        try {
            named.forEach((name, pats) -> include.put(name, compile(pats)));
            if (excludes != null) {
                excludes.forEach((name, pats) -> exclude.put(name, compile(pats)));
            }
        } catch (PatternSyntaxException e) {
            closeQuietly(reader);
            return result.patternError(true);
        }

        Map<String, Boolean> matched = new ConcurrentHashMap<>();
        named.keySet().forEach(k -> matched.put(k, false));
        Map<String, Integer> lineOf = new ConcurrentHashMap<>();
        LineCutter cutter = new LineCutter(reader, RetryOptions.MAX_LINE_CHARS, RetryOptions.LINE_OVERLAP_CHARS);
        FutureTask<Void> task = new FutureTask<>(() -> {
            Map<String, List<Matcher>> inc = matchers(include);
            Map<String, List<Matcher>> exc = matchers(exclude);
            Set<String> pending = new LinkedHashSet<>(inc.keySet());
            String line;
            while (!pending.isEmpty() && !Thread.currentThread().isInterrupted() && (line = cutter.next()) != null) {
                // The plugin's own lines ("retry 2/3", "point of no return") must never feed its decision.
                if (line.contains(RetryDecision.LOG_PREFIX)) {
                    continue;
                }
                if (line.indexOf("\u001B[8mha:") >= 0) {
                    line = CONSOLE_NOTE.matcher(line).replaceAll("");
                }
                CharSequence text = new Interruptible(line);
                for (Iterator<String> it = pending.iterator(); it.hasNext(); ) {
                    String name = it.next();
                    if (any(inc.get(name), text) && !any(exc.getOrDefault(name, List.of()), text)) {
                        lineOf.put(name, cutter.getCurrentLine());
                        matched.put(name, true);
                        it.remove();
                    }
                }
            }
            return null;
        });

        Thread worker = new Thread(task, SCAN_THREAD);
        worker.setDaemon(true);
        worker.start();
        try {
            task.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            result.timedOut(true);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException) {
                result.ioError(true);
            } else {
                result.patternError(true);
            }
        } catch (InterruptedException e) {
            result.timedOut(true);
            Thread.currentThread().interrupt();
        } finally {
            task.cancel(true);
            try {
                worker.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (worker.isAlive()) {
                // A reader may lock close() behind a read that is still stuck, so never wait for it here.
                Thread closer = new Thread(() -> closeQuietly(reader), CLOSE_THREAD);
                closer.setDaemon(true);
                closer.start();
            } else {
                closeQuietly(reader);
            }
        }
        // The worker may still run after a timeout, so the caller gets a copy, never the live map.
        named.keySet().forEach(k -> result.match(k, Boolean.TRUE.equals(matched.get(k))));
        lineOf.forEach((k, line) -> {
            if (result.isMatched(k)) {
                result.matchLine(k, line);
            }
        });
        return result.linesRead(cutter.getLines()).cutLines(cutter.getCutLines());
    }

    private static List<Pattern> compile(List<String> patterns) {
        List<Pattern> out = new ArrayList<>();
        if (patterns != null) {
            for (String p : patterns) {
                out.add(Pattern.compile(p));
            }
        }
        return out;
    }

    private static Map<String, List<Matcher>> matchers(Map<String, List<Pattern>> patterns) {
        Map<String, List<Matcher>> out = new LinkedHashMap<>();
        patterns.forEach((name, pats) -> {
            List<Matcher> ms = new ArrayList<>();
            pats.forEach(p -> ms.add(p.matcher("")));
            out.put(name, ms);
        });
        return out;
    }

    private static boolean any(List<Matcher> matchers, CharSequence text) {
        for (Matcher m : matchers) {
            if (m.reset(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static void closeQuietly(Reader reader) {
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (IOException ignored) {
            // nothing left to do with a log we could not close
        }
    }

    /** Ends lines like readLine (\n, \r\n, lone \r); a line over max comes out in overlapping pieces. */
    public static final class LineCutter {
        private final Reader reader;
        private final int max;
        private final int overlap;
        private final char[] chunk = new char[8192];
        private final StringBuilder buf = new StringBuilder();
        private int pos = 0;
        private int nl = -1;
        private int nlFrom = 0;
        private int cr = -1;
        private int crFrom = 0;
        private boolean eof = false;
        private boolean skipLf = false;
        private boolean cut = false;
        private final AtomicInteger lines = new AtomicInteger();
        private final AtomicInteger cutLines = new AtomicInteger();

        public LineCutter(Reader reader, int max, int overlap) {
            this.reader = reader;
            this.max = max;
            this.overlap = overlap;
        }

        public int getLines() {
            return lines.get();
        }

        public int getCutLines() {
            return cutLines.get();
        }

        /** The 1-based line the last piece came from; a cut line is counted only when it ends. */
        public int getCurrentLine() {
            return lines.get() + (cut ? 1 : 0);
        }

        /** The next line or piece, or null at the end of the text. */
        public String next() throws IOException {
            while (true) {
                if (skipLf) {
                    if (pos < buf.length()) {
                        if (buf.charAt(pos) == '\n') {
                            pos++;
                        }
                        skipLf = false;
                    } else if (eof) {
                        skipLf = false;
                    } else {
                        fill();
                        continue;
                    }
                }
                int end = terminator();
                if (end >= 0 && end - pos <= max) {
                    String piece = endLine(end);
                    pos = end + 1;
                    skipLf = buf.charAt(end) == '\r';
                    compact();
                    return piece;
                } else if (buf.length() - pos > max) {
                    String piece = buf.substring(pos, pos + max);
                    if (!cut) {
                        cutLines.incrementAndGet();
                    }
                    cut = true;
                    pos += max - overlap;
                    compact();
                    return piece;
                } else if (eof) {
                    if (pos >= buf.length()) {
                        return null;
                    }
                    int last = buf.length();
                    String piece = endLine(last);
                    pos = last;
                    return piece;
                } else {
                    fill();
                }
            }
        }

        private String endLine(int end) {
            lines.incrementAndGet();
            cut = false;
            return buf.substring(pos, end);
        }

        private int terminator() {
            if (nl < pos) {
                nl = buf.indexOf("\n", Math.max(pos, nlFrom));
                nlFrom = nl >= 0 ? nl : buf.length();
            }
            if (cr < pos) {
                cr = buf.indexOf("\r", Math.max(pos, crFrom));
                crFrom = cr >= 0 ? cr : buf.length();
            }
            return nl < 0 ? cr : (cr < 0 ? nl : Math.min(nl, cr));
        }

        private void fill() throws IOException {
            int n = reader.read(chunk, 0, chunk.length);
            if (n < 0) {
                eof = true;
            } else {
                buf.append(chunk, 0, n);
            }
        }

        private void compact() {
            if (pos < 32768) {
                return;
            }
            buf.delete(0, pos);
            nl = nl >= pos ? nl - pos : -1;
            cr = cr >= pos ? cr - pos : -1;
            nlFrom = Math.max(0, nlFrom - pos);
            crFrom = Math.max(0, crFrom - pos);
            pos = 0;
        }
    }

    // A regexp stuck in backtracking only notices cancel(true) through charAt, as in freestyle.
    private static final class Interruptible implements CharSequence {
        private final CharSequence wrapped;

        Interruptible(CharSequence wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public char charAt(int index) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException(new InterruptedException("log scan timed out"));
            }
            return wrapped.charAt(index);
        }

        @Override
        public int length() {
            return wrapped.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Interruptible(wrapped.subSequence(start, end));
        }

        @Override
        public String toString() {
            return wrapped.toString();
        }
    }
}
