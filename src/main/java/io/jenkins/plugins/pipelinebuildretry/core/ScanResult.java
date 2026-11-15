package io.jenkins.plugins.pipelinebuildretry.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** What one log scan found: which pattern groups matched, and why a scan may have stopped early. */
public final class ScanResult {

    private final Map<String, Boolean> matched = new LinkedHashMap<>();
    private final Map<String, Integer> matchLines = new LinkedHashMap<>();
    private boolean timedOut;
    private boolean patternError;
    private boolean ioError;
    private int linesRead;
    private int cutLines;
    private String scope = "build";
    private int errorLines;
    private int consoleLines = -1;
    private String storageFallback;
    private long heapWaitMs;
    private long heapFreeMiB;

    /** A result with every named group unmatched. */
    public static ScanResult of(Iterable<String> groups) {
        ScanResult r = new ScanResult();
        for (String g : groups) {
            r.matched.put(g, false);
        }
        return r;
    }

    /** A result built from known flags, for tests and merges. */
    public static ScanResult matched(Map<String, Boolean> matched) {
        ScanResult r = new ScanResult();
        r.matched.putAll(matched);
        return r;
    }

    public boolean isMatched(String group) {
        return Boolean.TRUE.equals(matched.get(group));
    }

    public Map<String, Boolean> getMatched() {
        return matched;
    }

    public ScanResult match(String group, boolean value) {
        matched.put(group, value);
        return this;
    }

    /** Records the 1-based log line of the group's first match. */
    public ScanResult matchLine(String group, int line) {
        matchLines.put(group, line);
        return this;
    }

    /** The 1-based log line of the group's first match, or null. */
    public Integer getMatchLine(String group) {
        return matchLines.get(group);
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    public ScanResult timedOut(boolean v) {
        timedOut = v;
        return this;
    }

    public boolean isPatternError() {
        return patternError;
    }

    public ScanResult patternError(boolean v) {
        patternError = v;
        return this;
    }

    public boolean isIoError() {
        return ioError;
    }

    public ScanResult ioError(boolean v) {
        ioError = v;
        return this;
    }

    public int getLinesRead() {
        return linesRead;
    }

    public ScanResult linesRead(int v) {
        linesRead = v;
        return this;
    }

    public int getCutLines() {
        return cutLines;
    }

    public ScanResult cutLines(int v) {
        cutLines = v;
        return this;
    }

    public String getScope() {
        return scope;
    }

    public ScanResult scope(String v) {
        scope = v;
        return this;
    }

    public int getErrorLines() {
        return errorLines;
    }

    public ScanResult errorLines(int v) {
        errorLines = v;
        return this;
    }

    /** Lines in the console log when it was read to its end, else -1. */
    public int getConsoleLines() {
        return consoleLines;
    }

    public ScanResult consoleLines(int v) {
        consoleLines = v;
        return this;
    }

    public String getStorageFallback() {
        return storageFallback;
    }

    public ScanResult storageFallback(String v) {
        storageFallback = v;
        return this;
    }

    public long getHeapWaitMs() {
        return heapWaitMs;
    }

    public ScanResult heapWaitMs(long v) {
        heapWaitMs = v;
        return this;
    }

    public long getHeapFreeMiB() {
        return heapFreeMiB;
    }

    public ScanResult heapFreeMiB(long v) {
        heapFreeMiB = v;
        return this;
    }

    /** Merges a second scan of other groups into this one. */
    public ScanResult merge(ScanResult other) {
        matched.putAll(other.matched);
        matchLines.putAll(other.matchLines);
        timedOut |= other.timedOut;
        patternError |= other.patternError;
        ioError |= other.ioError;
        linesRead += other.linesRead;
        cutLines += other.cutLines;
        return this;
    }

    @Override
    public String toString() {
        return "ScanResult" + matched + (timedOut ? " timedOut" : "") + (patternError ? " patternError" : "")
                + (ioError ? " ioError" : "") + " lines=" + linesRead + " cut=" + cutLines;
    }
}
