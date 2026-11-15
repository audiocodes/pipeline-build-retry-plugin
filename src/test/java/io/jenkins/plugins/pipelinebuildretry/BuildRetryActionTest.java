package io.jenkins.plugins.pipelinebuildretry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.util.XStream2;
import org.junit.jupiter.api.Test;

class BuildRetryActionTest {

    @Test
    void anActionSavedBeforeTheRootFieldReadsItsParentAsRoot() {
        String xml = "<" + BuildRetryAction.class.getName() + "><retryCount>2</retryCount>"
                + "<maxRetryCount>3</maxRetryCount><parentBuildNumber>41</parentBuildNumber><badge>true</badge></"
                + BuildRetryAction.class.getName() + ">";
        BuildRetryAction a = (BuildRetryAction) new XStream2().fromXML(xml);
        assertEquals(41, a.getRootBuildNumber(), "falls back to the parent");
        assertEquals(40, new BuildRetryAction(2, 3, 41, true, 40).getRootBuildNumber(), "kept when set");
        assertEquals(41, new BuildRetryAction(1, 3, 41).getRootBuildNumber(), "a first retry's root is its parent");
        assertEquals("Build retry 2/3 after build #41 failed", a.getTooltip(), "saved before the parent's result");
    }

    @Test
    void theTooltipNamesHowTheParentEnded() {
        assertEquals(
                "Build retry 1/1 after build #6 timed out",
                new BuildRetryAction(1, 1, 6, true, 6, "ABORTED", "timeout").getTooltip());
    }
}
