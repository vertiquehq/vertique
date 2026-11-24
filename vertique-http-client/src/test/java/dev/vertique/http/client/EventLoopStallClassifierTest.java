// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.http.client.EventLoopStallClassifier.Report;
import dev.vertique.http.client.EventLoopStallClassifier.Sample;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The decision that separates an event-loop turn product code blocked from one the host stalled. */
class EventLoopStallClassifierTest {

    private static final long LIMIT_MS = 200;
    private static final String MESSAGE = "Thread vert.x-eventloop-thread-0 has been blocked for 438 ms";

    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    private static final StackTraceElement JAVA_FRAME =
            new StackTraceElement("dev.vertique.http.client.SseParser", "feed", "SseParser.java", 120);

    private static Report classify(long cpuAtTurnStartMs, long cpuNowMs, Thread.State... states) {
        return classify(JAVA_FRAME, cpuAtTurnStartMs, cpuNowMs, states);
    }

    private static Report classify(
            StackTraceElement top, long cpuAtTurnStartMs, long cpuNowMs, Thread.State... states) {
        Sample[] samples = new Sample[states.length];
        for (int i = 0; i < states.length; i++) {
            samples[i] = new Sample(ms(10L * i), ms(cpuAtTurnStartMs), states[i]);
        }
        return EventLoopStallClassifier.classify(
                MESSAGE, LIMIT_MS, 438, samples.length == 0 ? null : samples[0], List.of(samples), ms(cpuNowMs), top);
    }

    @Test
    @DisplayName("a turn that used little CPU while the host stalled it is not blocking")
    void turnStalledByTheHostIsNotBlocking() {
        Report report = classify(1_000, 1_030, Thread.State.RUNNABLE, Thread.State.RUNNABLE);

        assertThat(report.blocking()).isFalse();
        assertThat(report.reason()).contains("stalled by the host").contains("cpu 30 ms of 438 ms wall");
    }

    @Test
    @DisplayName("a turn that spent the limit in CPU time is blocking even when the host also stalled it")
    void turnThatSpentTheLimitInCpuTimeIsBlocking() {
        assertThat(classify(1_000, 1_200, Thread.State.RUNNABLE).blocking()).isTrue();
        assertThat(classify(1_000, 1_199, Thread.State.RUNNABLE).blocking()).isFalse();
    }

    @Test
    @DisplayName("a turn in which the thread was sleeping, waiting or blocked on a monitor is blocking")
    void turnInWhichTheThreadWaitedIsBlocking() {
        for (Thread.State state : List.of(Thread.State.TIMED_WAITING, Thread.State.WAITING, Thread.State.BLOCKED)) {
            Report report = classify(1_000, 1_001, Thread.State.RUNNABLE, state, Thread.State.RUNNABLE);

            assertThat(report.blocking()).as("%s", state).isTrue();
            assertThat(report.reason()).contains(state.name());
        }
    }

    @Test
    @DisplayName("a turn stopped in a blocking native I/O call is blocking although it used no CPU")
    void turnInBlockingNativeCallIsBlocking() {
        for (String owner :
                List.of("java.net.Inet6AddressImpl", "java.io.FileInputStream", "sun.nio.fs.UnixNativeDispatcher")) {
            StackTraceElement nativeFrame = new StackTraceElement(owner, "call0", owner + ".java", -2);

            assertThat(classify(nativeFrame, 1_000, 1_001, Thread.State.RUNNABLE)
                            .blocking())
                    .as(owner)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a turn waiting in the native poll of a blocking socket call is blocking")
    void turnInSocketPollIsBlocking() {
        StackTraceElement poll = new StackTraceElement("sun.nio.ch.Net", "poll", "Net.java", -2);

        assertThat(classify(poll, 1_000, 1_001, Thread.State.RUNNABLE).blocking())
                .isTrue();
    }

    @Test
    @DisplayName("the CPU reading at the start of a turn is interpolated between the samples around it")
    void baselineIsInterpolated() {
        Sample before = new Sample(ms(0), ms(100), Thread.State.RUNNABLE);
        Sample inside = new Sample(ms(100), ms(200), Thread.State.RUNNABLE);

        assertThat(EventLoopStallClassifier.baselineAt(ms(40), before, inside).cpuNanos())
                .isEqualTo(ms(140));
        assertThat(EventLoopStallClassifier.baselineAt(ms(40), null, inside)).isSameAs(inside);
        assertThat(EventLoopStallClassifier.baselineAt(ms(40), before, null)).isSameAs(before);
        assertThat(EventLoopStallClassifier.baselineAt(ms(40), null, null)).isNull();
    }

    @Test
    @DisplayName("a turn stopped in the loop's own selector or in Java code is not taken for blocking I/O")
    void turnInSelectorOrJavaCodeIsNotBlockingIo() {
        StackTraceElement selector = new StackTraceElement("sun.nio.ch.KQueue", "poll", "KQueue.java", -2);
        StackTraceElement javaInNet = new StackTraceElement("java.net.URI", "parse", "URI.java", 3_100);

        assertThat(classify(selector, 1_000, 1_001, Thread.State.RUNNABLE).blocking())
                .isFalse();
        assertThat(classify(javaInNet, 1_000, 1_001, Thread.State.RUNNABLE).blocking())
                .isFalse();
    }

    @Test
    @DisplayName("a turn the classifier never observed is blocking, so an unseen turn fails")
    void turnWithoutObservationsIsBlocking() {
        assertThat(classify(1_000, 1_000).blocking()).isTrue();
        assertThat(EventLoopStallClassifier.classify(
                                MESSAGE,
                                LIMIT_MS,
                                438,
                                new Sample(0, ms(1), Thread.State.RUNNABLE),
                                List.of(),
                                -1,
                                JAVA_FRAME)
                        .blocking())
                .isTrue();
    }
}
