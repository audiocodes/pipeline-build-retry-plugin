package io.jenkins.plugins.pipelinebuildretry.evidence;

import hudson.PluginWrapper;
import io.jenkins.plugins.pipelinebuildretry.core.ChainedLogReader;
import io.jenkins.plugins.pipelinebuildretry.core.LineCountingReader;
import io.jenkins.plugins.pipelinebuildretry.core.LogScanner;
import io.jenkins.plugins.pipelinebuildretry.core.RetryOptions;
import io.jenkins.plugins.pipelinebuildretry.core.RunSnapshot;
import io.jenkins.plugins.pipelinebuildretry.core.RunSnapshot.ErrorLine;
import io.jenkins.plugins.pipelinebuildretry.core.ScanPlan;
import io.jenkins.plugins.pipelinebuildretry.core.ScanResult;
import java.io.StringReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Scans a build's console log, or only its failed stages' step logs, for the plan's patterns. */
@Restricted(NoExternalUse.class)
public final class LogScan {

    static final int STREAMING_WORKFLOW_API = 1398;
    private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d{1,9})");

    /** Old-generation bytes free after the last collection, and the bytes a scan wants. */
    record Headroom(long free, long need) {}

    /** Supplies heap headroom, or null when the JVM does not say. */
    @FunctionalInterface
    interface HeadroomSource {
        Headroom get();
    }

    /** Waits between heap checks. */
    @FunctionalInterface
    interface Pauser {
        void pause(long ms) throws InterruptedException;
    }

    /** How long a scan waited for heap, and the free MiB when it stopped waiting. */
    record HeapWait(long waitedMs, long freeMiB) {}

    private record StageLog(String stage, String id, int order, boolean failed, boolean housekeeping, LogAction log) {}

    private record StageSources(List<ChainedLogReader.Source> sources, int errorLines) {}

    private LogScan() {}

    /** Scans as the plan says; the interrupt of an aborting caller is passed on as an exception. */
    public static ScanResult scan(WorkflowRun run, ScanPlan plan, long timeoutMs, RunSnapshot snapshot)
            throws InterruptedException {
        Map<String, List<String>> groups = plan.getGroups();
        Map<String, List<String>> excludes = plan.getExcludes();
        List<ErrorLine> errors = snapshot.getStageErrorLines();
        Set<String> ids = new LinkedHashSet<>(snapshot.getFailedStageIds());
        Map<String, List<String>> stageGroups = new LinkedHashMap<>();
        groups.forEach((name, pats) -> {
            if (name.equals(ScanPlan.REGEXP) || ScanPlan.isNever(name)) {
                stageGroups.put(name, pats);
            }
        });
        boolean wantStages = "failedStages".equals(plan.getRegexpScope()) && !stageGroups.isEmpty() && !ids.isEmpty();
        String storage = wantStages ? storageFallback(run) : null;
        HeapWait heap = waitForHeap(timeoutMs, LogScan::heapHeadroom, Thread::sleep);
        long budget = Math.max(1L, timeoutMs - heap.waitedMs());
        StageSources stage = wantStages && storage == null ? stageSources(run, ids, errors) : null;
        ScanResult result;
        if (stage != null) {
            long started = System.currentTimeMillis();
            Map<String, List<String>> stageExcludes = new LinkedHashMap<>(excludes);
            for (String name : stageGroups.keySet()) {
                List<String> ex = new ArrayList<>(excludes.getOrDefault(name, List.of()));
                ex.addAll(RetryOptions.STACK_FRAME_EXCLUDES);
                stageExcludes.put(name, ex);
            }
            result = LogScanner.scan(new ChainedLogReader(stage.sources()), stageGroups, budget, stageExcludes);
            result.errorLines(stage.errorLines());
            Map<String, List<String>> rest = new LinkedHashMap<>(groups);
            stageGroups.keySet().forEach(rest::remove);
            if (!rest.isEmpty()) {
                failIfInterrupted();
                long left = budget - (System.currentTimeMillis() - started);
                ScanResult whole = left > 0
                        ? LogScanner.scan(wholeLog(run::getLogReader, errors), rest, left, excludes)
                        : ScanResult.of(rest.keySet()).timedOut(true);
                result.merge(whole).errorLines(errors.size());
            }
            result.scope("failedStages");
        } else {
            AtomicReference<LineCountingReader> console = new AtomicReference<>();
            ChainedLogReader.Source counted = () -> {
                LineCountingReader r = new LineCountingReader(run.getLogReader());
                console.set(r);
                return r;
            };
            result = LogScanner.scan(wholeLog(counted, errors), groups, budget, excludes)
                    .scope("build")
                    .errorLines(errors.size());
            LineCountingReader read = console.get();
            if (read != null && read.isDone()) {
                result.consoleLines(read.getLines());
            }
        }
        if (storage != null) {
            result.storageFallback(storage);
        }
        if (heap.waitedMs() > 0) {
            result.heapWaitMs(heap.waitedMs()).heapFreeMiB(heap.freeMiB());
        }
        failIfInterrupted();
        return result;
    }

    private static ChainedLogReader wholeLog(ChainedLogReader.Source console, List<ErrorLine> errors) {
        List<ChainedLogReader.Source> sources = new ArrayList<>();
        sources.add(console);
        if (!errors.isEmpty()) {
            String text = errors.stream().map(ErrorLine::line).collect(Collectors.joining("\n"));
            sources.add(() -> new StringReader(text));
        }
        return new ChainedLogReader(sources);
    }

    // Never System.gc(): waits at most min(5 s, timeout/4) for headroom, then scans anyway.
    static HeapWait waitForHeap(long timeoutMs, HeadroomSource source, Pauser pauser) throws InterruptedException {
        Headroom h = source.get();
        if (h == null) {
            return new HeapWait(0L, 0L);
        }
        long limit = Math.min(RetryOptions.HEAP_WAIT_MAX_MS, timeoutMs / 4L);
        long waited = 0L;
        while (waited < limit && h.free() < h.need()) {
            long step = Math.min(RetryOptions.HEAP_POLL_MS, limit - waited);
            pauser.pause(step);
            waited += step;
            Headroom next = source.get();
            h = next == null ? h : next;
        }
        return new HeapWait(waited, h.free() >> 20);
    }

    static Headroom heapHeadroom() {
        try {
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                boolean old = pool.getType() == MemoryType.HEAP
                        && (pool.getName().contains("Old") || pool.getName().contains("Tenured"));
                MemoryUsage after = old ? pool.getCollectionUsage() : null;
                if (after == null) {
                    continue;
                }
                long max = after.getMax() > 0
                        ? after.getMax()
                        : Runtime.getRuntime().maxMemory();
                long share = max * RetryOptions.HEAP_MIN_HEADROOM_PERCENT / 100;
                return new Headroom(max - after.getUsed(), Math.max(RetryOptions.HEAP_MIN_HEADROOM_BYTES, share));
            }
        } catch (RuntimeException | LinkageError ignored) {
            // no figures: scan without waiting
        }
        return null;
    }

    // Other storages, or workflow-api before 1398, may read a whole log per step.
    static String storageFallback(WorkflowRun run) {
        String storage;
        boolean streams = false;
        try {
            Class<?> k = Class.forName(ClassNames.LOG_STORAGE, true, FlowExecution.class.getClassLoader());
            Method of = null;
            for (Method m : k.getMethods()) {
                if (m.getName().equals("of") && m.getParameterCount() == 1 && Modifier.isStatic(m.getModifiers())) {
                    of = m;
                }
            }
            Object s = of == null ? null : of.invoke(null, run.asFlowExecutionOwner());
            storage = s == null ? "unknown" : s.getClass().getName();
            streams = ClassNames.isA(s, ClassNames.FILE_LOG_STORAGE) || ClassNames.isA(s, ClassNames.OTEL_LOG_STORAGE);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            storage = "unreadable (" + cause.getClass().getName() + ")";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            storage = "unreadable (" + e.getClass().getName() + ")";
        }
        String version = pluginVersion("workflow-api");
        return streams && streamsPerStep(version)
                ? null
                : "log storage " + storage + " on workflow-api " + (version == null ? "unknown" : version);
    }

    static boolean streamsPerStep(String workflowApiVersion) {
        Matcher m = workflowApiVersion == null ? null : LEADING_NUMBER.matcher(workflowApiVersion);
        return m != null && m.find() && Integer.parseInt(m.group(1)) >= STREAMING_WORKFLOW_API;
    }

    private static String pluginVersion(String name) {
        try {
            PluginWrapper p = Jenkins.get().getPluginManager().getPlugin(name);
            return p == null ? null : p.getVersion();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    // Pass 1 per failed stage: its error-carrying log nodes, then its Abort lines; pass 2: the rest.
    private static StageSources stageSources(WorkflowRun run, Set<String> ids, List<ErrorLine> errors) {
        FlowExecution execution = run.getExecution();
        if (execution == null) {
            return null;
        }
        List<StageLog> logs = new ArrayList<>();
        Set<String> failedBlocks = new HashSet<>();
        for (FlowNode node : new DepthFirstScanner().allNodes(execution)) {
            BlockStartNode start = node instanceof BlockEndNode<?> end ? end.getStartNode() : null;
            if (start != null && node.getError() != null) {
                failedBlocks.add(start.getId());
                // A failed body end marks the block step around it, whose start node holds its log.
                List<? extends BlockStartNode> around = start.getEnclosingBlocks();
                if (!around.isEmpty()) {
                    failedBlocks.add(around.get(0).getId());
                }
            }
            LogAction log = node.getPersistentAction(LogAction.class);
            if (log == null) {
                continue;
            }
            FlowNode stage = FlowEvidence.outerStage(start != null ? start : node);
            if (stage == null || !ids.contains(stage.getId())) {
                continue;
            }
            logs.add(new StageLog(
                    stage.getId(),
                    node.getId(),
                    FlowEvidence.order(node),
                    node.getError() != null,
                    housekeeping(node),
                    log));
        }
        if (logs.isEmpty()) {
            return null;
        }
        List<StageLog> sorted = new ArrayList<>(logs);
        sorted.sort(Comparator.comparingInt(StageLog::order));
        List<String> stageOrder = new ArrayList<>(ids);
        stageOrder.sort(Comparator.comparingInt(LogScan::idOrder));
        List<ChainedLogReader.Source> sources = new ArrayList<>();
        int added = 0;
        for (String sid : stageOrder) {
            for (StageLog l : sorted) {
                if (l.stage().equals(sid) && (l.failed() || failedBlocks.contains(l.id()))) {
                    sources.add(() -> l.log().getLogText().readAll());
                }
            }
            List<ErrorLine> lines = errors.stream()
                    .filter(e -> e.abort() && sid.equals(e.stageId()))
                    .sorted(Comparator.comparingInt(ErrorLine::order))
                    .toList();
            for (ErrorLine e : lines) {
                sources.add(() -> new StringReader(e.line()));
                added++;
            }
        }
        for (StageLog l : sorted) {
            if (!l.failed() && !failedBlocks.contains(l.id()) && !l.housekeeping()) {
                sources.add(() -> l.log().getLogText().readAll());
            }
        }
        return new StageSources(sources, added);
    }

    // Metasteps such as archiveArtifacts run as "step"; their display function name is the symbol.
    private static boolean housekeeping(FlowNode node) {
        return RetryOptions.HOUSEKEEPING_STEPS.contains(FlowEvidence.functionName(node))
                || RetryOptions.HOUSEKEEPING_STEPS.contains(node.getDisplayFunctionName());
    }

    private static int idOrder(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static void failIfInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException(io.jenkins.plugins.pipelinebuildretry.core.RetryDecision.LOG_PREFIX
                    + "interrupted while scanning the log");
        }
    }
}
