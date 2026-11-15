package io.jenkins.plugins.pipelinebuildretry.core;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Reads log sources in turn, opening each only when reached and ending each one with a newline. */
public final class ChainedLogReader extends Reader {

    /** Opens one log source; called at most once, and only when the scan reaches it. */
    @FunctionalInterface
    public interface Source {
        Reader open() throws IOException;
    }

    private final Iterator<Source> sources;
    private volatile Reader current;
    private volatile boolean closed = false;
    private boolean newlinePending = false;
    private int lastChar = -1;

    public ChainedLogReader(List<Source> sources) {
        this.sources = new ArrayList<>(sources == null ? List.of() : sources).iterator();
    }

    @Override
    public int read(char[] buf, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        while (!closed) {
            if (newlinePending) {
                newlinePending = false;
                buf[off] = '\n';
                return 1;
            }
            Reader r = current;
            if (r == null) {
                if (!openNext()) {
                    return -1;
                }
                continue;
            }
            int n = r.read(buf, off, len);
            if (n > 0) {
                lastChar = buf[off + n - 1];
                return n;
            }
            closeCurrent();
            newlinePending = lastChar >= 0 && lastChar != '\n';
        }
        return -1;
    }

    @Override
    public void close() {
        closed = true;
        closeCurrent();
    }

    // The scanner may close from another thread, so a source opened after close is closed at once.
    private boolean openNext() throws IOException {
        // A timed-out scan interrupts the reader; a source that swallows that must not open the next one.
        if (!sources.hasNext() || Thread.currentThread().isInterrupted()) {
            return false;
        }
        Source open = sources.next();
        Reader r;
        try {
            r = open.open();
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException(e);
        }
        current = r == null ? new StringReader("") : r;
        lastChar = -1;
        if (closed) {
            closeCurrent();
            return false;
        }
        return true;
    }

    private void closeCurrent() {
        Reader r = current;
        current = null;
        try {
            if (r != null) {
                r.close();
            }
        } catch (IOException ignored) {
            // a source that cannot close is already done for this scan
        }
    }
}
