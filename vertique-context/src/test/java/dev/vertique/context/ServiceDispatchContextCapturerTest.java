// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.core.context.ServiceDispatchEncodeContext;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link ServiceDispatchContextCapturer}.
 *
 * <p>Verifies capture keying, skipping of unbound types, collision detection (FR-CTX-063), and
 * null-encode rejection (FR-CTX-050).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ServiceDispatchContextCapturerTest {

    private DefaultContextHolder holder;

    @BeforeEach
    void setUp() {
        holder = new DefaultContextHolder();
    }

    /** Runs the given task on a duplicated Vert.x context so the holder's write-side guard accepts the bind. */
    private static void runOnDuplicated(Vertx vertx, Handler<Void> task) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(task);
    }

    // --- capture keying ---

    @Test
    @DisplayName("capture produces map keyed by encoder.key() for bound types")
    void captureKeyedByEncoderKey(Vertx vertx, VertxTestContext ctx) {
        ServiceDispatchContextEncoder<StringCtx> encoder = new ServiceDispatchContextEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String key() {
                return "locale.key";
            }

            @Override
            public Object encode(StringCtx value, ServiceDispatchEncodeContext context) {
                return value.value().toUpperCase();
            }
        };
        ServiceDispatchContextRegistry registry = new ServiceDispatchContextRegistry(Set.of(encoder), Set.of());
        ServiceDispatchContextCapturer capturer = new ServiceDispatchContextCapturer(registry, holder);

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("en_US"))) {
                Map<String, Object> captured = capturer.capture(new ServiceDispatchEncodeContext("service-dispatch"));
                assertEquals(1, captured.size());
                assertEquals("EN_US", captured.get("locale.key"));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- skip unbound types ---

    @Test
    @DisplayName("capture skips encoders whose type is not currently bound")
    void captureSkipsUnboundTypes(Vertx vertx, VertxTestContext ctx) {
        ServiceDispatchContextEncoder<StringCtx> encoder = new ServiceDispatchContextEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public Object encode(StringCtx value, ServiceDispatchEncodeContext context) {
                return value.value();
            }
        };
        ServiceDispatchContextRegistry registry = new ServiceDispatchContextRegistry(Set.of(encoder), Set.of());
        ServiceDispatchContextCapturer capturer = new ServiceDispatchContextCapturer(registry, holder);

        runOnDuplicated(vertx, v -> {
            try {
                // StringCtx is NOT bound
                Map<String, Object> captured = capturer.capture(new ServiceDispatchEncodeContext("service-dispatch"));
                assertTrue(captured.isEmpty());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- null encode result ---

    @Test
    @DisplayName("null encode result from encoder throws IllegalStateException (FR-CTX-050)")
    void nullEncodeResultThrowsIllegalState(Vertx vertx, VertxTestContext ctx) {
        ServiceDispatchContextEncoder<StringCtx> badEncoder = new ServiceDispatchContextEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public Object encode(StringCtx value, ServiceDispatchEncodeContext context) {
                return null; // contract violation
            }
        };
        ServiceDispatchContextRegistry registry = new ServiceDispatchContextRegistry(Set.of(badEncoder), Set.of());
        ServiceDispatchContextCapturer capturer = new ServiceDispatchContextCapturer(registry, holder);

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("value"))) {
                assertThrows(
                        IllegalStateException.class,
                        () -> capturer.capture(new ServiceDispatchEncodeContext("service-dispatch")));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- mergeCaptured collision ---

    @Test
    @DisplayName("mergeCaptured rejects collision between caller and captured keys (FR-CTX-063)")
    void mergeCapturedRejectsCollision(Vertx vertx, VertxTestContext ctx) {
        ServiceDispatchContextEncoder<StringCtx> encoder = new ServiceDispatchContextEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String key() {
                return "shared.key";
            }

            @Override
            public Object encode(StringCtx value, ServiceDispatchEncodeContext context) {
                return value.value();
            }
        };
        ServiceDispatchContextRegistry registry = new ServiceDispatchContextRegistry(Set.of(encoder), Set.of());
        ServiceDispatchContextCapturer capturer = new ServiceDispatchContextCapturer(registry, holder);

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("bound-value"))) {
                Map<String, Object> callerMap = new HashMap<>();
                callerMap.put("shared.key", "caller-value");
                assertThrows(
                        IllegalStateException.class,
                        () -> capturer.mergeCaptured(callerMap, new ServiceDispatchEncodeContext("service-dispatch")));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("mergeCaptured merges non-colliding keys from both caller and captured")
    void mergeCapturedMergesNonCollidingKeys(Vertx vertx, VertxTestContext ctx) {
        ServiceDispatchContextEncoder<StringCtx> encoder = new ServiceDispatchContextEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String key() {
                return "captured.key";
            }

            @Override
            public Object encode(StringCtx value, ServiceDispatchEncodeContext context) {
                return value.value();
            }
        };
        ServiceDispatchContextRegistry registry = new ServiceDispatchContextRegistry(Set.of(encoder), Set.of());
        ServiceDispatchContextCapturer capturer = new ServiceDispatchContextCapturer(registry, holder);

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("captured-value"))) {
                Map<String, Object> callerMap = new HashMap<>();
                callerMap.put("caller.key", "caller-value");
                Map<String, Object> merged =
                        capturer.mergeCaptured(callerMap, new ServiceDispatchEncodeContext("service-dispatch"));
                assertEquals("caller-value", merged.get("caller.key"));
                assertEquals("captured-value", merged.get("captured.key"));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }
}
