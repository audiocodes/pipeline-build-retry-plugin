package io.jenkins.plugins.pipelinebuildretry;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;

/** Reports a waiting retry that someone other than this plugin cancels in the queue. */
@Extension
@Restricted(NoExternalUse.class)
public class CancelledRetryListener extends QueueListener {

    private static final Set<Long> OWN = ConcurrentHashMap.newKeySet();

    /** Cancels a retry item on the plugin's own behalf, which reports it itself. */
    static boolean cancel(Queue queue, Queue.Item item) {
        OWN.add(item.getId());
        boolean done = queue.cancel(item);
        if (!done) {
            OWN.remove(item.getId());
        }
        return done;
    }

    @Override
    public void onLeft(Queue.LeftItem li) {
        if (OWN.remove(li.getId()) || !li.isCancelled() || !(li.task instanceof Job)) {
            return;
        }
        BuildRetryAction a = li.getAction(BuildRetryAction.class);
        if (a != null) {
            // Listeners run as SYSTEM, so the user is unknown; a web request is the best hint left.
            String source = Stapler.getCurrentRequest2() != null ? "web" : "internal";
            BuildRetryEvents.retryCancelledInQueue((Job<?, ?>) li.task, li, a, source);
        }
    }
}
