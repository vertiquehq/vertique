// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.core.context.ServiceDispatchEncodeContext;
import dev.vertique.core.eventbus.DispatchEnvelope;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Module-local proof for {@link DispatchEnvelopeBuilder}: captured context and caller overrides
 * merge into the envelope metadata, a key collision fails, the reply address threads through, and
 * {@link DispatchEnvelopeBuilder#forTesting()} stays equivalent to a builder over empty registries.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
@DisplayName("DispatchEnvelopeBuilder")
class DispatchEnvelopeBuilderTest {

    private static final ServiceDispatchContextEncoder<StringCtx> LOCALE_ENCODER =
            new ServiceDispatchContextEncoder<>() {
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
                    return value.value().toUpperCase() + "@" + context.boundary();
                }
            };

    private static DispatchEnvelopeBuilder builderWith(ContextHolder holder) {
        return new DispatchEnvelopeBuilder(new ServiceDispatchContextCapturer(
                new ServiceDispatchContextRegistry(Set.of(LOCALE_ENCODER), Set.of()), holder));
    }

    /** Runs the task on a duplicated Vert.x context so the holder's write-side guard accepts the bind. */
    private static void runOnDuplicated(Vertx vertx, Handler<Void> task) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(task);
    }

    @Test
    @DisplayName("build merges captured context with caller overrides and keeps the payload")
    void buildMergesCapturedAndCaller(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DispatchEnvelopeBuilder builder = builderWith(holder);

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("en_US"))) {
                DispatchEnvelope<String> envelope = builder.build("payload", Map.of("caller.key", 7), "svc");

                assertEquals("payload", envelope.payload());
                assertEquals(
                        Map.of("caller.key", 7, "locale.key", "EN_US@svc"),
                        envelope.metadata().dispatchContext());
                assertTrue(envelope.replyAddress().isEmpty());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("build with a reply address threads it into the envelope")
    void buildWithReplyAddress(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DispatchEnvelopeBuilder builder = builderWith(holder);

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("fi"))) {
                DispatchEnvelope<String> envelope = builder.build("p", Map.of(), "svc", "reply.addr");

                assertEquals("reply.addr", envelope.replyAddress().orElseThrow());
                assertEquals(Map.of("locale.key", "FI@svc"), envelope.metadata().dispatchContext());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("a caller override colliding with a captured key fails the build")
    void collisionFails(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DispatchEnvelopeBuilder builder = builderWith(holder);

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("en"))) {
                IllegalStateException failure = assertThrows(
                        IllegalStateException.class, () -> builder.build("p", Map.of("locale.key", "override"), "svc"));
                assertTrue(failure.getMessage().contains("locale.key"), failure.getMessage());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("forTesting() is equivalent to a builder over empty registries: caller map only, never defaults")
    void forTestingEquivalentToEmptyRegistries(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DispatchEnvelopeBuilder reference = new DispatchEnvelopeBuilder(new ServiceDispatchContextCapturer(
                new ServiceDispatchContextRegistry(Set.of(), Set.of()), new DefaultContextHolder()));
        DispatchEnvelopeBuilder encoderBearing = builderWith(holder);
        DispatchEnvelopeBuilder forTesting = DispatchEnvelopeBuilder.forTesting();
        Map<String, Object> caller = Map.of("caller.key", "v");

        // The context slot is shared, so a value bound through any holder is visible to forTesting()'s own
        // holder; only an encoder registered by default could turn it into a captured key.
        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("en_US"))) {
                assertEquals(
                        Map.of("caller.key", "v", "locale.key", "EN_US@svc"),
                        encoderBearing.build("p", caller, "svc").metadata().dispatchContext(),
                        "control: an encoder-bearing builder does capture the bound value");
                assertEquals(
                        caller, forTesting.build("p", caller, "svc").metadata().dispatchContext());
                assertEquals(
                        reference.build("p", caller, "svc").metadata().dispatchContext(),
                        forTesting.build("p", caller, "svc").metadata().dispatchContext());
                assertEquals(
                        Map.of(),
                        forTesting.build("p", Map.of(), "svc").metadata().dispatchContext());
                assertTrue(forTesting.build("p", Map.of(), "svc").replyAddress().isEmpty());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("null overrides, boundary, or reply address are rejected")
    void nullArgumentsRejected() {
        DispatchEnvelopeBuilder builder = DispatchEnvelopeBuilder.forTesting();

        assertThrows(NullPointerException.class, () -> builder.build("p", null, "svc"));
        assertThrows(NullPointerException.class, () -> builder.build("p", Map.of(), null));
        assertThrows(NullPointerException.class, () -> builder.build("p", Map.of(), "svc", null));
    }
}
