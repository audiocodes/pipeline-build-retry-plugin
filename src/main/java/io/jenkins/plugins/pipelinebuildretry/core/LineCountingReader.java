package io.jenkins.plugins.pipelinebuildretry.core;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;

/** Counts lines as LogScanner.LineCutter ends them (\n, \r\n, lone \r), and whether the end was reached. */
public final class LineCountingReader extends FilterReader {

    private volatile int lines;
    private volatile boolean done;
    private boolean lastCr;
    private boolean open;

    public LineCountingReader(Reader in) {
        super(in);
    }

    /** Lines read so far; a last line with no line end counts once the end is reached. */
    public int getLines() {
        return lines + (done && open ? 1 : 0);
    }

    /** True once the wrapped reader reported its end. */
    public boolean isDone() {
        return done;
    }

    @Override
    public int read() throws IOException {
        char[] one = new char[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0];
    }

    @Override
    public int read(char[] buf, int off, int len) throws IOException {
        int n = super.read(buf, off, len);
        if (n < 0) {
            done = true;
            return n;
        }
        int counted = lines;
        for (int i = off; i < off + n; i++) {
            char c = buf[i];
            if (c == '\n') {
                if (!lastCr) {
                    counted++;
                }
                lastCr = false;
                open = false;
            } else if (c == '\r') {
                counted++;
                lastCr = true;
                open = false;
            } else {
                lastCr = false;
                open = true;
            }
        }
        lines = counted;
        return n;
    }
}
