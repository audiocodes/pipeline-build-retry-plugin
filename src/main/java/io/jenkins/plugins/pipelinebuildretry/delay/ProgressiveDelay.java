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
package io.jenkins.plugins.pipelinebuildretry.delay;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.util.FormValidation;
import io.jenkins.plugins.pipelinebuildretry.core.DelaySpec;
import java.util.Objects;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/** increment × (n+1)(n+2)/2 seconds before retry n+1, capped at max; the freestyle default is 300/10800. */
public class ProgressiveDelay extends RetryDelay {

    private final int increment;
    private final int max;

    @DataBoundConstructor
    public ProgressiveDelay(int increment, int max) {
        if (increment < 0 || max < 0) {
            throw new IllegalArgumentException("progressiveDelay: increment and max must be at least 0");
        }
        this.increment = increment;
        this.max = max;
    }

    public int getIncrement() {
        return increment;
    }

    public int getMax() {
        return max;
    }

    @Override
    public DelaySpec toSpec() {
        return DelaySpec.progressive(increment, max);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProgressiveDelay that && that.increment == increment && that.max == max;
    }

    @Override
    public int hashCode() {
        return Objects.hash(increment, max);
    }

    /** Descriptor for {@link ProgressiveDelay}. */
    @Extension
    @Symbol("progressiveDelay")
    public static class DescriptorImpl extends RetryDelayDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Progressive";
        }

        @POST
        public FormValidation doCheckIncrement(@AncestorInPath Item item, @QueryParameter String value) {
            return DelayForms.nonNegative(item, value);
        }

        @POST
        public FormValidation doCheckMax(@AncestorInPath Item item, @QueryParameter String value) {
            return DelayForms.nonNegative(item, value);
        }
    }
}
