// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.rest.core.sse.BufferOverflowPolicy;
import dev.vertique.rest.core.sse.SseEvent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.streams.ReadStream;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link DefaultSseChannel}.
 *
 * <p>Verifies channel lifecycle, event buffering, overflow policies, flow control, and the
 * {@link ReadStream} contract. All async assertions use {@link VertxTestContext} since drain
 * operations are dispatched via {@code context.runOnContext()}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class DefaultSseChannelTest {

    // --- stream() ---

    @Nested
    @DisplayName("stream()")
    class Stream {

        @Test
        @DisplayName("Should return the channel itself as a ReadStream")
        void shouldReturnSelfAsReadStream(Vertx vertx) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            ReadStream<SseEvent> stream = channel.stream();
            assertSame(channel, stream);
        }
    }

    // --- send(SseEvent) ---

    @Nested
    @DisplayName("send(SseEvent)")
    class SendEvent {

        @Test
        @DisplayName("Should buffer event and drain when handler is set")
        void shouldDrainBufferedEventWhenHandlerSet(Vertx vertx, VertxTestContext testContext) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            SseEvent event = SseEvent.of("hello");

            Future<Void> sendResult = channel.send(event);
            assertTrue(sendResult.succeeded());

            channel.handler(received -> {
                testContext.verify(() -> assertEquals(event, received));
                testContext.completeNow();
            });
        }

        @Test
        @DisplayName("Should return failed future after channel is closed")
        void shouldFailAfterClose(Vertx vertx) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            channel.complete();

            Future<Void> result = channel.send(SseEvent.of("data"));
            assertTrue(result.failed());
            assertInstanceOf(IllegalStateException.class, result.cause());
        }

        @Test
        @DisplayName("Should return failed future on buffer overflow with FAIL policy")
        void shouldFailOnBufferOverflowWithFailPolicy(Vertx vertx) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 2, BufferOverflowPolicy.FAIL);

            // Fill buffer (no handler, so events accumulate)
            assertTrue(channel.send(SseEvent.of("1")).succeeded());
            assertTrue(channel.send(SseEvent.of("2")).succeeded());

            // Third event overflows
            Future<Void> result = channel.send(SseEvent.of("3"));
            assertTrue(result.failed());
            assertInstanceOf(IllegalStateException.class, result.cause());
        }

        @Test
        @DisplayName("Should drop oldest event on buffer overflow with DROP_OLDEST policy")
        void shouldDropOldestOnBufferOverflow(Vertx vertx, VertxTestContext testContext) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 2, BufferOverflowPolicy.DROP_OLDEST);

            SseEvent event1 = SseEvent.of("1");
            SseEvent event2 = SseEvent.of("2");
            SseEvent event3 = SseEvent.of("3");

            // Fill buffer
            assertTrue(channel.send(event1).succeeded());
            assertTrue(channel.send(event2).succeeded());
            // Overflow: event1 is dropped, event3 is enqueued
            assertTrue(channel.send(event3).succeeded());

            List<SseEvent> received = new ArrayList<>();
            channel.handler(e -> {
                received.add(e);
                if (received.size() == 2) {
                    testContext.verify(() -> {
                        // event1 was dropped; should receive event2 and event3
                        assertEquals(List.of(event2, event3), received);
                    });
                    testContext.completeNow();
                }
            });
        }
    }

    // --- send(Object) ---

    @Nested
    @DisplayName("send(Object)")
    class SendObject {

        @Test
        @DisplayName("Should wrap data in SseEvent.of() and enqueue it")
        void shouldWrapObjectInSseEvent(Vertx vertx, VertxTestContext testContext) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            String payload = "world";

            channel.send(payload);

            channel.handler(received -> {
                testContext.verify(() -> assertEquals(SseEvent.of(payload), received));
                testContext.completeNow();
            });
        }
    }

    // --- send(String, Object) ---

    @Nested
    @DisplayName("send(String, Object)")
    class SendTyped {

        @Test
        @DisplayName("Should create typed SSE event with given event name and data")
        void shouldCreateTypedEvent(Vertx vertx, VertxTestContext testContext) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);

            channel.send("user-created", "alice");

            channel.handler(received -> {
                testContext.verify(() -> {
                    assertEquals("user-created", received.event());
                    assertEquals("alice", received.data());
                });
                testContext.completeNow();
            });
        }
    }

    // --- complete() ---

    @Nested
    @DisplayName("complete()")
    class Complete {

        @Test
        @DisplayName("Should drain remaining buffered events and fire end handler")
        void shouldDrainAndFireEndHandler(Vertx vertx, VertxTestContext testContext) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            SseEvent event = SseEvent.of("last");

            channel.send(event);
            List<SseEvent> received = new ArrayList<>();
            channel.handler(received::add);
            channel.endHandler(v -> {
                testContext.verify(() -> {
                    assertTrue(received.contains(event));
                });
                testContext.completeNow();
            });

            channel.complete();
        }

        @Test
        @DisplayName("Should mark channel as closed after complete()")
        void shouldMarkChannelClosed(Vertx vertx) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            assertFalse(channel.isClosed());
            channel.complete();
            assertTrue(channel.isClosed());
        }

        @Test
        @DisplayName("Should be idempotent when called multiple times")
        void shouldBeIdempotent(Vertx vertx) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            AtomicBoolean closeFired = new AtomicBoolean(false);
            channel.onClose(() -> {
                if (closeFired.getAndSet(true)) {
                    fail("onClose fired more than once");
                }
            });

            channel.complete();
            channel.complete(); // second call must be a no-op
            assertTrue(channel.isClosed());
        }

        @Test
        @DisplayName("Should fire onClose handlers on complete()")
        void shouldFireOnCloseHandlers(Vertx vertx, VertxTestContext testContext) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            channel.onClose(testContext::completeNow);
            channel.complete();
        }
    }

    // --- fail(Throwable) ---

    @Nested
    @DisplayName("fail(Throwable)")
    class Fail {

        @Test
        @DisplayName("Should fire exception handler with the given cause")
        void shouldFireExceptionHandler(Vertx vertx, VertxTestContext testContext) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            RuntimeException cause = new RuntimeException("oops");

            channel.exceptionHandler(t -> {
                testContext.verify(() -> assertSame(cause, t));
                testContext.completeNow();
            });

            channel.fail(cause);
        }

        @Test
        @DisplayName("Should mark channel as closed after fail()")
        void shouldMarkChannelClosed(Vertx vertx) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            channel.fail(new RuntimeException("error"));
            assertTrue(channel.isClosed());
        }

        @Test
        @DisplayName("Should fire onClose handlers on fail()")
        void shouldFireOnCloseHandlers(Vertx vertx, VertxTestContext testContext) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            channel.onClose(testContext::completeNow);
            channel.fail(new RuntimeException("test"));
        }
    }

    // --- isClosed() ---

    @Nested
    @DisplayName("isClosed()")
    class IsClosed {

        @Test
        @DisplayName("Should return false before any close operation")
        void shouldReturnFalseInitially(Vertx vertx) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            assertFalse(channel.isClosed());
        }

        @Test
        @DisplayName("Should return true after complete()")
        void shouldReturnTrueAfterComplete(Vertx vertx) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            channel.complete();
            assertTrue(channel.isClosed());
        }

        @Test
        @DisplayName("Should return true after fail()")
        void shouldReturnTrueAfterFail(Vertx vertx) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            channel.fail(new RuntimeException("test"));
            assertTrue(channel.isClosed());
        }
    }

    // --- onClose(Runnable) ---

    @Nested
    @DisplayName("onClose(Runnable)")
    class OnClose {

        @Test
        @DisplayName("Should support multiple close callbacks invoked in registration order")
        void shouldSupportMultipleCloseCallbacks(Vertx vertx, VertxTestContext testContext) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            List<String> order = new ArrayList<>();
            channel.onClose(() -> order.add("first"));
            channel.onClose(() -> {
                order.add("second");
                testContext.verify(() -> assertEquals(List.of("first", "second"), order));
                testContext.completeNow();
            });
            channel.complete();
        }
    }

    // --- pause() / resume() ---

    @Nested
    @DisplayName("Flow control")
    class FlowControl {

        @Test
        @DisplayName("Should not drain while paused")
        void shouldNotDrainWhilePaused(Vertx vertx, VertxTestContext testContext) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            AtomicBoolean received = new AtomicBoolean(false);

            channel.handler(e -> received.set(true));
            channel.pause();
            channel.send(SseEvent.of("buffered"));

            // Give runOnContext a chance to fire (it shouldn't while paused)
            vertx.setTimer(50, id -> {
                testContext.verify(() -> assertFalse(received.get(), "Event should not be drained while paused"));
                testContext.completeNow();
            });
        }

        @Test
        @DisplayName("Should drain buffered events on resume()")
        void shouldDrainOnResume(Vertx vertx, VertxTestContext testContext) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            channel.pause();
            channel.send(SseEvent.of("event"));

            channel.handler(e -> {
                testContext.verify(() -> assertEquals("event", e.data()));
                testContext.completeNow();
            });

            channel.resume();
        }
    }

    // --- handler(null) ---

    @Nested
    @DisplayName("handler(null)")
    class HandlerNull {

        @Test
        @DisplayName("Should cancel the channel when handler is set to null")
        void shouldCancelOnNullHandler(Vertx vertx, VertxTestContext testContext) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            channel.onClose(testContext::completeNow);

            channel.handler(e -> {});
            channel.handler(null); // cancel
        }

        @Test
        @DisplayName("Should mark channel as closed after handler(null)")
        void shouldMarkClosedAfterNullHandler(Vertx vertx) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            channel.handler(e -> {});
            channel.handler(null);
            assertTrue(channel.isClosed());
        }
    }

    // --- Multiple buffered events ---

    @Nested
    @DisplayName("Buffered event drain order")
    class DrainOrder {

        @Test
        @DisplayName("Should drain multiple events in FIFO order")
        void shouldDrainInFifoOrder(Vertx vertx, VertxTestContext testContext) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            SseEvent e1 = SseEvent.of("1");
            SseEvent e2 = SseEvent.of("2");
            SseEvent e3 = SseEvent.of("3");

            channel.send(e1);
            channel.send(e2);
            channel.send(e3);

            List<SseEvent> received = new ArrayList<>();
            channel.handler(e -> {
                received.add(e);
                if (received.size() == 3) {
                    testContext.verify(() -> assertEquals(List.of(e1, e2, e3), received));
                    testContext.completeNow();
                }
            });
        }
    }

    // --- onClose return value ---

    @Nested
    @DisplayName("onClose() return value")
    class OnCloseReturnValue {

        @Test
        @DisplayName("Should return this for fluent chaining")
        void shouldReturnSelfForChaining(Vertx vertx) {
            DefaultSseChannel channel = new DefaultSseChannel(vertx, 10, BufferOverflowPolicy.FAIL);
            AtomicReference<Object> ref = new AtomicReference<>();
            // onClose returns SseChannel — verify it's the same instance
            var result = channel.onClose(() -> ref.set(Boolean.TRUE));
            assertSame(channel, result);
        }
    }
}
