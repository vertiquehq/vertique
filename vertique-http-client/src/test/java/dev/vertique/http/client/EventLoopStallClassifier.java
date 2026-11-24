// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells an event-loop turn that product code blocked apart from a turn that the host merely stalled.
 *
 * <p>The Vert.x blocked-thread checker measures the wall-clock time since a turn began. On an
 * oversubscribed host the operating system, or a collector pause, can stop the loop thread in the middle of a
 * turn, so the checker then reports a turn that took far longer than the work in it. The loop limit means
 * "product code kept the loop for that long", so a reported turn counts as blocking when the thread either
 * spent at least the limit in CPU time during the turn, was seen blocked, waiting or sleeping during it, or is
 * stopped in a blocking native call (see {@link #isBlockingNativeCall}). A turn that did none of these
 * was stalled by the host and is only recorded.
 *
 * <p>One instance serves one Vert.x instance: samples are keyed by event-loop thread name, which every Vert.x
 * instance in the JVM repeats.
 *
 * <p>A daemon thread samples every event-loop thread's CPU time and state every {@value #SAMPLE_INTERVAL_MS}
 * ms; the classification reads one more CPU time at the moment the checker reports.
 */
final class EventLoopStallClassifier implements AutoCloseable {

    /** How often every event-loop thread's CPU time and state is recorded. */
    static final long SAMPLE_INTERVAL_MS = 10;

    /** Thread-name prefix of Vert.x event-loop threads. */
    private static final String EVENT_LOOP_PREFIX = "vert.x-eventloop-thread-";

    /** How long samples are kept; far above any reported turn, since reports come every check interval. */
    /** How often the set of event-loop threads is looked up again among all threads. */
    private static final long ID_REFRESH_NANOS = TimeUnit.SECONDS.toNanos(1);

    private static final long RETENTION_NANOS = TimeUnit.SECONDS.toNanos(10);

    private static final Pattern BLOCKED_FOR = Pattern.compile("^Thread (\\S+) has been blocked for (\\d+) ms");

    /** One observation of an event-loop thread. */
    record Sample(long nanos, long cpuNanos, Thread.State state) {}

    /** What the checker reported and how it was classified. */
    record Report(String message, boolean blocking, String reason) {}

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final long limitMs;
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Sample>> samples = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> threadIds = new ConcurrentHashMap<>();
    private final java.util.Queue<RuntimeException> failures = new ConcurrentLinkedDeque<>();
    private volatile long[] loopThreadIds = new long[0];
    private volatile long idsRefreshedAt;
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "event-loop-sampler");
        thread.setDaemon(true);
        return thread;
    });

    /** Starts sampling; {@code limitMs} is the event-loop execution limit of the Vert.x instance. */
    EventLoopStallClassifier(long limitMs) {
        this.limitMs = limitMs;
        sampler.scheduleWithFixedDelay(this::sample, 0, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void sample() {
        try {
            long now = System.nanoTime();
            if (now - idsRefreshedAt > ID_REFRESH_NANOS || loopThreadIds.length == 0) {
                loopThreadIds = eventLoopThreadIds();
                idsRefreshedAt = now;
            }
            for (ThreadInfo info : threads.getThreadInfo(loopThreadIds, 0)) {
                if (info == null) {
                    continue;
                }
                long cpu = threads.getThreadCpuTime(info.getThreadId());
                if (cpu < 0) {
                    continue;
                }
                String name = info.getThreadName();
                threadIds.put(name, info.getThreadId());
                ConcurrentLinkedDeque<Sample> series =
                        samples.computeIfAbsent(name, key -> new ConcurrentLinkedDeque<>());
                series.addLast(new Sample(now, cpu, info.getThreadState()));
                Sample oldest = series.peekFirst();
                while (oldest != null && now - oldest.nanos() > RETENTION_NANOS && series.size() > 1) {
                    series.pollFirst();
                    oldest = series.peekFirst();
                }
            }
        } catch (RuntimeException e) {
            // keep sampling: a failed pass only leaves a gap, which classification treats as unobserved
            failures.add(e);
        }
    }

    private long[] eventLoopThreadIds() {
        ThreadInfo[] all = threads.getThreadInfo(threads.getAllThreadIds(), 0);
        return java.util.Arrays.stream(all)
                .filter(info -> info != null && info.getThreadName().startsWith(EVENT_LOOP_PREFIX))
                .mapToLong(ThreadInfo::getThreadId)
                .toArray();
    }

    /**
     * Classifies one blocked-thread report; {@code top} is the innermost frame the checker captured, or null.
     * A message that is not the checker's "has been blocked for" report, or a thread this classifier never
     * sampled, is treated as blocking, so an unrecognised report fails.
     */
    Report classify(String message, StackTraceElement top) {
        Matcher matcher = BLOCKED_FOR.matcher(message);
        if (!matcher.find()) {
            return new Report(message, true, "unrecognised report");
        }
        String name = matcher.group(1);
        long blockedMs = Long.parseLong(matcher.group(2));
        Long id = threadIds.get(name);
        ConcurrentLinkedDeque<Sample> series = samples.get(name);
        if (id == null || series == null) {
            return new Report(message, true, "thread was never sampled");
        }
        long turnStart = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(blockedMs);
        List<Sample> inTurn = new ArrayList<>();
        Sample before = null;
        for (Sample sample : series) {
            if (sample.nanos() >= turnStart) {
                inTurn.add(sample);
            } else {
                before = sample;
            }
        }
        Sample baseline = baselineAt(turnStart, before, inTurn.isEmpty() ? null : inTurn.get(0));
        ThreadInfo live = threads.getThreadInfo(id);
        long cpuNow = threads.getThreadCpuTime(id);
        if (live != null) {
            inTurn.add(new Sample(System.nanoTime(), cpuNow, live.getThreadState()));
        }
        return classify(message, limitMs, blockedMs, baseline, inTurn, cpuNow, top);
    }

    /**
     * The CPU reading at the start of a turn: interpolated between the last sample before it and the first
     * sample inside it, so a sampling gap, however long a starved sampler makes it, neither adds the CPU of
     * earlier turns nor drops the CPU of this one. With only one of the two it is that sample: a turn the
     * sampler never saw inside can only be over-counted, so it is never excused.
     */
    static Sample baselineAt(long turnStart, Sample before, Sample firstInside) {
        if (before == null || firstInside == null) {
            return firstInside != null ? firstInside : before;
        }
        long span = firstInside.nanos() - before.nanos();
        if (span <= 0) {
            return firstInside;
        }
        double fraction = (double) (turnStart - before.nanos()) / span;
        long cpu = before.cpuNanos() + (long) ((firstInside.cpuNanos() - before.cpuNanos()) * fraction);
        return new Sample(turnStart, cpu, firstInside.state());
    }

    /**
     * The decision on the observations of one reported turn: {@code baseline} is the CPU reading the turn's CPU
     * time is counted from, {@code inTurn} the samples taken during the turn, and {@code cpuNowNanos} the
     * thread's CPU time when the report was made.
     */
    static Report classify(
            String message,
            long limitMs,
            long blockedMs,
            Sample baseline,
            List<Sample> inTurn,
            long cpuNowNanos,
            StackTraceElement top) {
        if (baseline == null || cpuNowNanos < 0) {
            return new Report(message, true, "thread was not observed");
        }
        for (Sample sample : inTurn) {
            if (sample.state() == Thread.State.BLOCKED
                    || sample.state() == Thread.State.WAITING
                    || sample.state() == Thread.State.TIMED_WAITING) {
                return new Report(message, true, "thread was " + sample.state() + " during the turn");
            }
        }
        if (isBlockingNativeCall(top)) {
            return new Report(message, true, "thread is in blocking native call " + top);
        }
        long cpuMs = TimeUnit.NANOSECONDS.toMillis(cpuNowNanos - baseline.cpuNanos());
        String measured = "cpu " + cpuMs + " ms of " + blockedMs + " ms wall, limit " + limitMs + " ms"
                + (top == null ? "" : ", innermost frame " + top);
        if (cpuMs >= limitMs) {
            return new Report(message, true, measured);
        }
        return new Report(message, false, "stalled by the host: " + measured);
    }

    /**
     * Whether the innermost frame is a native call that blocks the calling thread: a name lookup or file read
     * of {@code java.net}, {@code java.io} or {@code sun.nio.fs}, a file channel read, waiting for a child
     * process, or {@code sun.nio.ch.Net.poll}, where a blocking socket accept, connect or read waits. A
     * descheduled thread is also in some native method, so only these are listed; the loop's own selector and
     * write calls are not, and neither are other blocking natives, which this guard does not detect.
     */
    static boolean isBlockingNativeCall(StackTraceElement top) {
        if (top == null || !top.isNativeMethod()) {
            return false;
        }
        String owner = top.getClassName();
        return owner.startsWith("java.net.")
                || owner.equals("java.io.FileInputStream")
                || owner.equals("java.io.RandomAccessFile")
                || owner.startsWith("sun.nio.fs.")
                || owner.equals("sun.nio.ch.FileDispatcherImpl")
                || owner.startsWith("java.lang.Process")
                || owner.equals("sun.nio.ch.Net") && top.getMethodName().equals("poll");
    }

    @Override
    public void close() {
        sampler.shutdownNow();
    }
}
