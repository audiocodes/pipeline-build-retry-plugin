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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Seconds to wait before retry number retryCount + 1: fixed, progressive (as in freestyle) or a list. */
public final class DelaySpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The delay shapes. */
    public enum Type {
        FIXED,
        PROGRESSIVE,
        LIST
    }

    public static final int DEFAULT_INCREMENT = 300;
    public static final int DEFAULT_MAX = 10800;

    private final Type type;
    private final int delay;
    private final int increment;
    private final int max;
    private final ArrayList<Integer> seconds;

    private DelaySpec(Type type, int delay, int increment, int max, List<Integer> seconds) {
        this.type = type;
        this.delay = delay;
        this.increment = increment;
        this.max = max;
        this.seconds = seconds == null ? new ArrayList<>() : new ArrayList<>(seconds);
    }

    /** The same delay before every retry. */
    public static DelaySpec fixed(int delay) {
        return new DelaySpec(Type.FIXED, delay, 0, 0, null);
    }

    /** increment × (n+1)(n+2)/2, capped at max; max ≤ 0 means no cap. */
    public static DelaySpec progressive(int increment, int max) {
        return new DelaySpec(Type.PROGRESSIVE, 0, increment, max, null);
    }

    /** Index = retry number; the last value repeats. */
    public static DelaySpec list(List<Integer> seconds) {
        return new DelaySpec(Type.LIST, 0, 0, 0, seconds);
    }

    /** The freestyle fallback when no delay is configured: progressive 300 / 10800. */
    public static DelaySpec defaultDelay() {
        return progressive(DEFAULT_INCREMENT, DEFAULT_MAX);
    }

    public Type getType() {
        return type;
    }

    public int getDelay() {
        return delay;
    }

    public int getIncrement() {
        return increment;
    }

    public int getMax() {
        return max;
    }

    public List<Integer> getSeconds() {
        return Collections.unmodifiableList(seconds);
    }

    /** The lowercase name used in log lines and options: fixed, progressive or list. */
    public String typeName() {
        return type.name().toLowerCase(Locale.ROOT);
    }

    /** Problems with the values, never thrown; empty when valid. */
    public List<String> errors() {
        List<String> errors = new ArrayList<>();
        switch (type) {
            case FIXED -> {
                if (delay < 0) {
                    errors.add("delay.delay must be at least 0");
                }
            }
            case PROGRESSIVE -> {
                if (increment < 0) {
                    errors.add("delay.increment must be at least 0");
                }
                if (max < 0) {
                    errors.add("delay.max must be at least 0");
                }
            }
            case LIST -> {
                if (seconds.isEmpty()) {
                    errors.add("delay.seconds must be a non-empty list of seconds");
                }
                for (int i = 0; i < seconds.size(); i++) {
                    Integer s = seconds.get(i);
                    if (s == null || s < 0) {
                        errors.add("delay.seconds[" + i + "] must be at least 0");
                    }
                }
            }
        }
        return errors;
    }

    /** Returns the quiet period in seconds for a build that has already been retried retryCount times. */
    public int compute(int retryCount) {
        int n = Math.max(0, retryCount);
        return switch (type) {
            case FIXED -> Math.max(0, delay);
            case LIST -> seconds.isEmpty() ? 0 : asSeconds(seconds.get(Math.min(n, seconds.size() - 1)));
            case PROGRESSIVE -> progressive(Math.max(0, increment), max, n);
        };
    }

    /** Null-safe compute: a null spec uses the freestyle fallback. */
    public static int compute(DelaySpec spec, int retryCount) {
        return (spec == null ? defaultDelay() : spec).compute(retryCount);
    }

    // increment * (n+1)(n+2)/2, saturating at Integer.MAX_VALUE where freestyle's int math wraps.
    private static int progressive(int increment, int max, int n) {
        long triangle = ((long) n + 1L) * ((long) n + 2L) / 2L;
        long result = increment > 0 && triangle > Long.MAX_VALUE / increment ? Long.MAX_VALUE : triangle * increment;
        if (max > 0 && result > max) {
            result = max;
        }
        return (int) Math.min(result, Integer.MAX_VALUE);
    }

    private static int asSeconds(Integer v) {
        return v == null ? 0 : Math.max(0, v);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DelaySpec that)) {
            return false;
        }
        return type == that.type
                && delay == that.delay
                && increment == that.increment
                && max == that.max
                && seconds.equals(that.seconds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, delay, increment, max, seconds);
    }

    @Override
    public String toString() {
        return switch (type) {
            case FIXED -> "fixed " + delay + "s";
            case PROGRESSIVE -> "progressive " + increment + "/" + max;
            case LIST -> "list " + seconds;
        };
    }
}
