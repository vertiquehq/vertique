// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextDecodeWarning;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurablePropagationMetadata;
import dev.vertique.core.context.DurableTarget;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link DurableContextPropagator}.
 *
 * <p>Verifies capture, merge, and bindFrom semantics including collision rules, FR-CTX-141, and
 * FR-CTX-156 (one decoder failure does not block others).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DurableContextPropagatorTest {

    private DefaultContextHolder holder;
    private ContextScopeBinder binder;

    @BeforeEach
    void setUp() {
        holder = new DefaultContextHolder();
        binder = new ContextScopeBinder(holder);
    }

    /** Runs the given task on a duplicated Vert.x context so the holder's write-side guard accepts the bind. */
    private static void runOnDuplicated(Vertx vertx, Handler<Void> task) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(task);
    }

    // --- capture ---

    @Test
    @DisplayName("capture returns only the namespace declared by the encoder for currently bound type")
    void captureReturnsDeclaredKeys(Vertx vertx, VertxTestContext ctx) {
        DurableContextMetadataEncoder<StringCtx> enc = stringEncoder("vertique-locale", "test-locale-key");
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(Set.of(enc), Set.of());
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("en_US"))) {
                DurableMetadata captured = propagator.capture("kafka");
                assertTrue(captured.has("test-locale-key"), "captured metadata must contain the declared namespace");
                assertEquals(
                        "en_US",
                        captured.body("test-locale-key")
                                .map(b -> b.getString("value"))
                                .orElse(null));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("capture skips encoders whose type is not currently bound")
    void captureSkipsUnboundTypes(Vertx vertx, VertxTestContext ctx) {
        DurableContextMetadataEncoder<StringCtx> enc = stringEncoder("vertique-locale", "test-locale-key");
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(Set.of(enc), Set.of());
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        runOnDuplicated(vertx, v -> {
            try {
                // StringCtx is NOT bound — should produce empty metadata
                DurableMetadata captured = propagator.capture("kafka");
                assertTrue(captured.isEmpty());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("F1: an encoder that returns DurableMetadata.empty() for a BOUND type is skipped — "
            + "no merge, no FR-CTX-120 violation thrown")
    void captureSkipsEncoderThatSignalsNothingToEncode(Vertx vertx, VertxTestContext ctx) {
        DurableContextMetadataEncoder<StringCtx> nothingToEncode = new DurableContextMetadataEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "nothing-to-encode-key";
            }

            @Override
            public DurableMetadata encode(StringCtx value, DurableEncodeContext context) {
                // Simulates an encoder whose currently-bound value has nothing legitimate to
                // encode (e.g. a receive-side degradation marker) — the "skip this namespace"
                // signal captureOne must honor rather than treat as a namespace-mismatch violation.
                return DurableMetadata.empty();
            }
        };
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(Set.of(nothingToEncode), Set.of());
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("bound-but-unencodable"))) {
                DurableMetadata captured = propagator.capture("kafka");
                assertTrue(
                        captured.isEmpty(),
                        "an encoder signalling 'nothing to encode' via DurableMetadata.empty() must not "
                                + "contribute a namespace, and capture() must not throw");
                assertFalse(captured.has("nothing-to-encode-key"));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- mergeCaptured ---

    @Test
    @DisplayName("mergeCaptured fails if caller has colliding namespace and type is currently bound (FR-CTX-153)")
    void mergeCapturedCollisionWithBoundTypeFails(Vertx vertx, VertxTestContext ctx) {
        DurableContextMetadataEncoder<StringCtx> enc = stringEncoder("vertique-locale", "test-locale-key");
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(Set.of(enc), Set.of());
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("en_US"))) {
                DurableMetadata callerMeta =
                        DurableMetadata.of("test-locale-key", new JsonObject().put("value", "fr_FR"));
                assertThrows(IllegalStateException.class, () -> propagator.mergeCaptured(callerMeta, "kafka"));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("mergeCaptured passes caller namespace through if type is NOT currently bound (FR-CTX-154)")
    void mergeCapturedPassesThroughWhenTypeNotBound(Vertx vertx, VertxTestContext ctx) {
        DurableContextMetadataEncoder<StringCtx> enc = stringEncoder("vertique-locale", "test-locale-key");
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(Set.of(enc), Set.of());
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        runOnDuplicated(vertx, v -> {
            try {
                // StringCtx is NOT bound — caller namespace should pass through unchanged
                DurableMetadata callerMeta =
                        DurableMetadata.of("test-locale-key", new JsonObject().put("value", "fr_FR"));
                DurableMetadata merged = propagator.mergeCaptured(callerMeta, "kafka");
                assertEquals(
                        "fr_FR",
                        merged.body("test-locale-key")
                                .map(b -> b.getString("value"))
                                .orElse(null));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- bindFrom ---

    @Test
    @DisplayName("bindFrom always binds DurablePropagationMetadata even with zero typed decoders (FR-CTX-141)")
    void bindFromAlwaysBindsDurablePropagationMetadata(Vertx vertx, VertxTestContext ctx) {
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(Set.of(), Set.of());
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        runOnDuplicated(vertx, v -> {
            DurableMetadata metadata = DurableMetadata.of("some-ns", new JsonObject().put("k", "some-value"));
            try (ContextHolder.Scope scope = propagator.bindFrom(metadata, "kafka")) {
                assertTrue(holder.current(DurablePropagationMetadata.class).isPresent());
                DurablePropagationMetadata dpm =
                        holder.current(DurablePropagationMetadata.class).get();
                assertEquals("kafka", dpm.boundary());
                assertEquals(
                        "some-value",
                        dpm.metadata()
                                .body("some-ns")
                                .map(b -> b.getString("k"))
                                .orElse(null));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("bindFrom binds successfully decoded typed contexts")
    void bindFromBindsDecodedTypes(Vertx vertx, VertxTestContext ctx) {
        DurableContextMetadataDecoder<StringCtx> decoder = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "test-locale-key";
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return metadata.body("test-locale-key")
                        .map(b -> ContextDecodeResult.of(new StringCtx(b.getString("value"))))
                        .orElseGet(ContextDecodeResult::empty);
            }
        };
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(Set.of(), Set.of(decoder));
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        runOnDuplicated(vertx, v -> {
            DurableMetadata metadata = DurableMetadata.of("test-locale-key", new JsonObject().put("value", "de_DE"));
            try (ContextHolder.Scope scope = propagator.bindFrom(metadata, "kafka")) {
                assertTrue(holder.current(StringCtx.class).isPresent());
                assertEquals("de_DE", holder.current(StringCtx.class).get().value());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("one decoder failure does not block other decoders from binding (FR-CTX-156)")
    void oneDecoderFailureDoesNotBlockOthers(Vertx vertx, VertxTestContext ctx) {
        // Decoder A (StringCtx) always throws an exception
        DurableContextMetadataDecoder<StringCtx> failingDecoder = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "fail-ns";
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                throw new RuntimeException("simulated decoder failure");
            }
        };
        // Decoder B (IntCtx) succeeds
        DurableContextMetadataDecoder<IntCtx> successDecoder = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<IntCtx> type() {
                return IntCtx.class;
            }

            @Override
            public String namespace() {
                return "success-ns";
            }

            @Override
            public ContextDecodeResult<IntCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return metadata.body("success-ns")
                        .map(b -> ContextDecodeResult.of(new IntCtx(b.getInteger("value"))))
                        .orElseGet(ContextDecodeResult::empty);
            }
        };
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(), Set.of(failingDecoder, successDecoder));
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        runOnDuplicated(vertx, v -> {
            DurableMetadata metadata = DurableMetadata.of("fail-ns", new JsonObject().put("v", "x"))
                    .with("success-ns", new JsonObject().put("value", 42));
            try (ContextHolder.Scope scope = propagator.bindFrom(metadata, "kafka")) {
                // StringCtx not bound (decoder failed)
                assertFalse(holder.current(StringCtx.class).isPresent());
                // IntCtx bound (decoder succeeded)
                assertTrue(holder.current(IntCtx.class).isPresent());
                assertEquals(42, holder.current(IntCtx.class).get().value());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName(
            "encoder-only round-trip: bound type captured; consumer with no decoder still sees DurablePropagationMetadata")
    void encoderOnlyRoundTrip(Vertx vertx, VertxTestContext ctx) {
        DurableContextMetadataEncoder<StringCtx> enc = stringEncoder("vertique-locale", "test-locale-key");
        DurableContextMetadataRegistry producerRegistry = new DurableContextMetadataRegistry(Set.of(enc), Set.of());
        DurableContextPropagator producerPropagator = new DurableContextPropagator(producerRegistry, holder, binder);

        // Consumer side has no decoder for StringCtx
        DurableContextMetadataRegistry consumerRegistry = new DurableContextMetadataRegistry(Set.of(), Set.of());
        DurableContextPropagator consumerPropagator = new DurableContextPropagator(consumerRegistry, holder, binder);

        runOnDuplicated(vertx, v -> {
            try {
                // Producer side: capture metadata
                DurableMetadata capturedMetadata;
                try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("ja_JP"))) {
                    capturedMetadata = producerPropagator.capture("kafka");
                }
                // capturedMetadata contains "test-locale-key" namespace with body value "ja_JP"
                assertEquals(
                        "ja_JP",
                        capturedMetadata
                                .body("test-locale-key")
                                .map(b -> b.getString("value"))
                                .orElse(null));

                // Consumer side: bindFrom — no StringCtx decoder, but DurablePropagationMetadata should be bound
                try (ContextHolder.Scope consumerScope = consumerPropagator.bindFrom(capturedMetadata, "kafka")) {
                    // StringCtx NOT bound (no decoder on consumer)
                    assertFalse(holder.current(StringCtx.class).isPresent());
                    // But DurablePropagationMetadata IS bound
                    assertTrue(holder.current(DurablePropagationMetadata.class).isPresent());
                    DurablePropagationMetadata dpm =
                            holder.current(DurablePropagationMetadata.class).get();
                    assertEquals(
                            "ja_JP",
                            dpm.metadata()
                                    .body("test-locale-key")
                                    .map(b -> b.getString("value"))
                                    .orElse(null));
                }
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("bindFrom scope unwinds DurablePropagationMetadata after close")
    void bindFromScopeUnwindsDurablePropagationMetadata(Vertx vertx, VertxTestContext ctx) {
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(Set.of(), Set.of());
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        runOnDuplicated(vertx, v -> {
            DurableMetadata metadata = DurableMetadata.of("ns", new JsonObject().put("k", "v"));
            ContextHolder.Scope scope = propagator.bindFrom(metadata, "outbox");
            assertTrue(holder.current(DurablePropagationMetadata.class).isPresent());
            scope.close();
            assertFalse(holder.current(DurablePropagationMetadata.class).isPresent());
            ctx.completeNow();
        });
    }

    @Test
    @DisplayName("bindFrom authoritatively clears ambient registered durable types absent from metadata")
    void bindFromClearsAmbientAbsentFromMetadata(Vertx vertx, VertxTestContext ctx) {
        // Two registered durable types: StringCtx (decoded from metadata) and IntCtx (NOT in metadata).
        DurableContextMetadataDecoder<StringCtx> stringDecoder = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "s-ns";
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return metadata.body("s-ns")
                        .map(b -> ContextDecodeResult.of(new StringCtx(b.getString("value"))))
                        .orElseGet(ContextDecodeResult::empty);
            }
        };
        DurableContextMetadataDecoder<IntCtx> intDecoder = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<IntCtx> type() {
                return IntCtx.class;
            }

            @Override
            public String namespace() {
                return "i-ns";
            }

            @Override
            public ContextDecodeResult<IntCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return metadata.body("i-ns")
                        .map(b -> ContextDecodeResult.of(new IntCtx(b.getInteger("value"))))
                        .orElseGet(ContextDecodeResult::empty);
            }
        };
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(), Set.of(stringDecoder, intDecoder));
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        runOnDuplicated(vertx, v -> {
            // Bind an ambient IntCtx (simulating an unrelated request's durable context that
            // happens to be live at the branch-drive site).
            try (ContextHolder.Scope ambientScope = holder.bind(IntCtx.class, new IntCtx(42))) {
                assertEquals(42, holder.current(IntCtx.class).orElseThrow().value());
                // bindFrom with metadata containing only the StringCtx namespace. IntCtx is registered
                // but absent — authoritative bindFrom must clear it for the scope lifetime.
                DurableMetadata metadata = DurableMetadata.of("s-ns", new JsonObject().put("value", "branch-value"));
                try (ContextHolder.Scope scope = propagator.bindFrom(metadata, "workflow")) {
                    assertEquals(
                            "branch-value",
                            holder.current(StringCtx.class).orElseThrow().value());
                    // Authoritative clear — IntCtx must NOT be observable inside the scope.
                    assertFalse(
                            holder.current(IntCtx.class).isPresent(),
                            "ambient IntCtx must be cleared inside bindFrom scope (authoritative)");
                }
                // After scope close, ambient IntCtx is restored.
                assertEquals(
                        42,
                        holder.current(IntCtx.class).orElseThrow().value(),
                        "ambient IntCtx must be restored when bindFrom scope closes");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("bindFrom with empty metadata clears every ambient registered durable type")
    void bindFromEmptyMetadataClearsAllRegisteredAmbient(Vertx vertx, VertxTestContext ctx) {
        DurableContextMetadataDecoder<IntCtx> intDecoder = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<IntCtx> type() {
                return IntCtx.class;
            }

            @Override
            public String namespace() {
                return "i-ns";
            }

            @Override
            public ContextDecodeResult<IntCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return metadata.body("i-ns")
                        .map(b -> ContextDecodeResult.of(new IntCtx(b.getInteger("value"))))
                        .orElseGet(ContextDecodeResult::empty);
            }
        };
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(Set.of(), Set.of(intDecoder));
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope ambientScope = holder.bind(IntCtx.class, new IntCtx(99))) {
                try (ContextHolder.Scope scope = propagator.bindFrom(DurableMetadata.empty(), "workflow")) {
                    assertFalse(
                            holder.current(IntCtx.class).isPresent(),
                            "empty-metadata bindFrom must still clear ambient registered IntCtx");
                    assertTrue(
                            holder.current(DurablePropagationMetadata.class).isPresent(),
                            "empty-metadata bindFrom must still bind DurablePropagationMetadata");
                }
                assertEquals(
                        99,
                        holder.current(IntCtx.class).orElseThrow().value(),
                        "ambient must be restored when bindFrom scope closes");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName(
            "ambient durable context cleared by outer bindFrom is invisible to inner mergeCaptured (leak prevention)")
    void bindFromClearsAmbientForNestedMergeCaptured(Vertx vertx, VertxTestContext ctx) {
        DurableContextMetadataEncoder<StringCtx> stringEncoderInst = stringEncoder("ambient-key", "ambient-key");
        DurableContextMetadataDecoder<StringCtx> stringDecoder = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "ambient-key";
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return metadata.body("ambient-key")
                        .map(b -> ContextDecodeResult.of(new StringCtx(b.getString("value"))))
                        .orElseGet(ContextDecodeResult::empty);
            }
        };
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(stringEncoderInst), Set.of(stringDecoder));
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope ambient = holder.bind(StringCtx.class, new StringCtx("ambient-leak"))) {
                // Sanity: without the bindFrom scope, mergeCaptured(empty) captures the ambient value.
                DurableMetadata withoutScope = propagator.mergeCaptured(DurableMetadata.empty(), "delayed-job");
                assertEquals(
                        "ambient-leak",
                        withoutScope
                                .body("ambient-key")
                                .map(b -> b.getString("value"))
                                .orElse(null));
                // With the bindFrom scope (branch-drive's authoritative bind with EMPTY workflow
                // metadata — the leak-prevention case), mergeCaptured must observe the cleared
                // state: no ambient value, empty merged metadata.
                try (ContextHolder.Scope branchScope = propagator.bindFrom(DurableMetadata.empty(), "workflow")) {
                    DurableMetadata insideScope = propagator.mergeCaptured(DurableMetadata.empty(), "delayed-job");
                    assertFalse(
                            insideScope.has("ambient-key"),
                            "ambient StringCtx must not leak through mergeCaptured inside an authoritative bindFrom scope");
                }
                // After the bindFrom scope closes, the ambient value is restored — mergeCaptured
                // observes it again.
                DurableMetadata afterScope = propagator.mergeCaptured(DurableMetadata.empty(), "delayed-job");
                assertEquals(
                        "ambient-leak",
                        afterScope
                                .body("ambient-key")
                                .map(b -> b.getString("value"))
                                .orElse(null));
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("bindFrom on a non-duplicated Vert.x context throws IllegalStateException (FR-CTX-157b)")
    void bindFromOnNonDuplicatedContextThrows(Vertx vertx, VertxTestContext ctx) {
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(Set.of(), Set.of());
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        // bindFrom is the durable-consumer API for installing holder values. Per FR-CTX-157b it
        // must run on a duplicated Vert.x context; callers that cannot guarantee that (transport
        // relays whose callbacks land on a non-duplicated context) must use
        // decodeToDispatchContext instead. A non-duplicated Vert.x context is treated as a
        // misconfigured caller and the holder's write guard's IllegalStateException propagates.
        vertx.getOrCreateContext().runOnContext(v -> {
            try {
                DurableMetadata metadata = DurableMetadata.of("k", new JsonObject().put("v", "x"));
                assertThrows(
                        IllegalStateException.class,
                        () -> propagator.bindFrom(metadata, "workflow"),
                        "non-duplicated context must propagate the holder's write guard");
                assertFalse(
                        holder.current(DurablePropagationMetadata.class).isPresent(),
                        "no holder values bound after throw");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("decoder returning warning list: DurablePropagationMetadata still bound, type not bound")
    void decoderWithWarningsTypNotBound(Vertx vertx, VertxTestContext ctx) {
        DurableContextMetadataDecoder<StringCtx> warningDecoder = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "warn-ns";
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                String rawValue =
                        metadata.body("warn-ns").map(b -> b.getString("value")).orElse(null);
                return ContextDecodeResult.failure(
                        List.of(new ContextDecodeWarning("warn-ns", rawValue, "invalid format")));
            }
        };
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(Set.of(), Set.of(warningDecoder));
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        runOnDuplicated(vertx, v -> {
            DurableMetadata metadata = DurableMetadata.of("warn-ns", new JsonObject().put("value", "bad-value"));
            try (ContextHolder.Scope scope = propagator.bindFrom(metadata, "kafka")) {
                // DurablePropagationMetadata always bound
                assertTrue(holder.current(DurablePropagationMetadata.class).isPresent());
                // StringCtx type not bound (decode failed)
                assertFalse(holder.current(StringCtx.class).isPresent());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    // --- carrier threading (F5 row binding, PRD-ID-002 §14.6/A9/F5) ---

    @Test
    @DisplayName("mergeCaptured threads the supplied carrier into the DurableEncodeContext (F5 row binding)")
    void mergeCapturedThreadsCarrierIntoEncodeContext(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<DurableEncodeContext> observed = new AtomicReference<>();
        DurableContextMetadataEncoder<StringCtx> recordingEncoder = new DurableContextMetadataEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "rec-ns";
            }

            @Override
            public DurableMetadata encode(StringCtx value, DurableEncodeContext context) {
                observed.set(context);
                return DurableMetadata.of("rec-ns", new JsonObject().put("value", value.value()));
            }
        };
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(recordingEncoder), Set.of());
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);
        DurableCarrierDescriptor carrier = new DurableCarrierDescriptor(
                "carrier-1", new DurableTarget("delayed-job", "handler-addr", Optional.empty()));

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("v"))) {
                propagator.mergeCaptured(DurableMetadata.empty(), "delayed-job", carrier);
                assertNotNull(observed.get(), "encoder must have been invoked");
                assertTrue(observed.get().carrier().isPresent(), "carrier must be threaded into the encode context");
                assertEquals(carrier, observed.get().carrier().orElseThrow());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("mergeCaptured(carrier, fireTime) threads the supplied fireTime into the DurableEncodeContext "
            + "(F5 doomed-expiry detection)")
    void mergeCapturedThreadsFireTimeIntoEncodeContext(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<DurableEncodeContext> observed = new AtomicReference<>();
        DurableContextMetadataEncoder<StringCtx> recordingEncoder = new DurableContextMetadataEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "rec-ns";
            }

            @Override
            public DurableMetadata encode(StringCtx value, DurableEncodeContext context) {
                observed.set(context);
                return DurableMetadata.of("rec-ns", new JsonObject().put("value", value.value()));
            }
        };
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(recordingEncoder), Set.of());
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);
        DurableCarrierDescriptor carrier = new DurableCarrierDescriptor(
                "carrier-1", new DurableTarget("delayed-job", "handler-addr", Optional.empty()));
        java.time.Instant fireTime = java.time.Instant.parse("2026-07-01T10:15:30Z");

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("v"))) {
                propagator.mergeCaptured(DurableMetadata.empty(), "delayed-job", carrier, fireTime);
                assertNotNull(observed.get(), "encoder must have been invoked");
                assertTrue(observed.get().fireTime().isPresent(), "fireTime must be threaded into the encode context");
                assertEquals(fireTime, observed.get().fireTime().orElseThrow());
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("mergeCaptured(carrier) without fireTime leaves the DurableEncodeContext fireTime empty")
    void mergeCapturedWithoutFireTimeLeavesEncodeContextEmpty(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<DurableEncodeContext> observed = new AtomicReference<>();
        DurableContextMetadataEncoder<StringCtx> recordingEncoder = new DurableContextMetadataEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "rec-ns";
            }

            @Override
            public DurableMetadata encode(StringCtx value, DurableEncodeContext context) {
                observed.set(context);
                return DurableMetadata.of("rec-ns", new JsonObject().put("value", value.value()));
            }
        };
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(recordingEncoder), Set.of());
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);
        DurableCarrierDescriptor carrier = new DurableCarrierDescriptor(
                "carrier-1", new DurableTarget("outbox", "handler-addr", Optional.empty()));

        runOnDuplicated(vertx, v -> {
            try (ContextHolder.Scope scope = holder.bind(StringCtx.class, new StringCtx("v"))) {
                propagator.mergeCaptured(DurableMetadata.empty(), "outbox", carrier);
                assertNotNull(observed.get(), "encoder must have been invoked");
                assertTrue(
                        observed.get().fireTime().isEmpty(),
                        "the carrier-only overload must leave fireTime empty (no fixed fire time on this boundary)");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }

    @Test
    @DisplayName("decodeToDispatchContext threads the supplied carrier into the DurableDecodeContext (F5 row binding)")
    void decodeToDispatchContextThreadsCarrierIntoDecodeContext() {
        AtomicReference<DurableDecodeContext> observed = new AtomicReference<>();
        DurableContextMetadataDecoder<StringCtx> recordingDecoder = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return "rec-ns";
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                observed.set(context);
                return ContextDecodeResult.empty();
            }
        };
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(), Set.of(recordingDecoder));
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);
        DurableCarrierDescriptor carrier = new DurableCarrierDescriptor(
                "carrier-2", new DurableTarget("delayed-job", "handler-addr", Optional.empty()));

        // decodeToDispatchContext never touches the holder, so it needs no Vert.x context.
        propagator.decodeToDispatchContext(
                DurableMetadata.of("rec-ns", new JsonObject().put("value", "x")), "delayed-job", carrier);

        assertNotNull(observed.get(), "decoder must have been invoked");
        assertTrue(observed.get().carrier().isPresent(), "carrier must be threaded into the decode context");
        assertEquals(carrier, observed.get().carrier().orElseThrow());
    }

    // --- sanitizeInboundCarrier ---

    @Test
    @DisplayName("sanitizeInboundCarrier strips exactly the namespace of a decoder declaring "
            + "acceptsExplicitCarrier=false, keeping other namespaces")
    void sanitizeInboundCarrierStripsAuthenticatedOnlyNamespaceKeepsOthers() {
        DurableContextMetadataDecoder<StringCtx> authenticatedOnlyDecoder = authenticatedOnlyDecoder("secure-ns");
        DurableContextMetadataDecoder<IntCtx> normalDecoder = new DurableContextMetadataDecoder<>() {
            @Override
            public Class<IntCtx> type() {
                return IntCtx.class;
            }

            @Override
            public String namespace() {
                return "normal-ns";
            }

            @Override
            public ContextDecodeResult<IntCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return ContextDecodeResult.empty();
            }
        };
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(), Set.of(authenticatedOnlyDecoder, normalDecoder));
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        DurableMetadata carrier = DurableMetadata.of("secure-ns", new JsonObject().put("value", "forged"))
                .with("normal-ns", new JsonObject().put("value", "ok"));

        DurableMetadata sanitized = propagator.sanitizeInboundCarrier(carrier);

        assertFalse(sanitized.has("secure-ns"), "authenticated-only namespace must be stripped");
        assertTrue(sanitized.has("normal-ns"), "namespace from a decoder accepting explicit carriers must remain");
    }

    @Test
    @DisplayName("sanitizeInboundCarrier keeps a namespace with no registered decoder (inert, passes through)")
    void sanitizeInboundCarrierKeepsUnknownNamespace() {
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(), Set.of(authenticatedOnlyDecoder("secure-ns")));
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        DurableMetadata carrier = DurableMetadata.of("unknown-ns", new JsonObject().put("value", "whatever"));

        DurableMetadata sanitized = propagator.sanitizeInboundCarrier(carrier);

        assertTrue(sanitized.has("unknown-ns"), "a namespace with no registered decoder is inert and passes through");
    }

    @Test
    @DisplayName("sanitizeInboundCarrier returns the same instance when nothing is stripped")
    void sanitizeInboundCarrierReturnsSameInstanceWhenNothingStripped() {
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(), Set.of(authenticatedOnlyDecoder("secure-ns")));
        DurableContextPropagator propagator = new DurableContextPropagator(registry, holder, binder);

        DurableMetadata carrier = DurableMetadata.of("normal-ns", new JsonObject().put("value", "ok"));

        DurableMetadata sanitized = propagator.sanitizeInboundCarrier(carrier);

        assertSame(carrier, sanitized, "no namespace stripped must avoid a defensive copy on the common path");
    }

    // --- Helpers ---

    /**
     * Creates a decoder declaring {@code acceptsExplicitCarrier() == false} for the given namespace —
     * an authenticated-only namespace per ADR-0147.
     *
     * @param namespace the namespace this decoder owns
     * @return the authenticated-only decoder
     */
    private static DurableContextMetadataDecoder<StringCtx> authenticatedOnlyDecoder(String namespace) {
        return new DurableContextMetadataDecoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return namespace;
            }

            @Override
            public ContextDecodeResult<StringCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return ContextDecodeResult.empty();
            }

            @Override
            public boolean acceptsExplicitCarrier() {
                return false;
            }
        };
    }

    /**
     * Creates a simple {@link StringCtx} encoder that encodes the bound value under the given
     * namespace, storing the string in a {@code "value"} field of the namespace body.
     *
     * @param namespace   the namespace name owned by this encoder
     * @param keyOverride the namespace key to use (same as namespace in these tests)
     * @return the encoder
     */
    private static DurableContextMetadataEncoder<StringCtx> stringEncoder(String namespace, String keyOverride) {
        return new DurableContextMetadataEncoder<>() {
            @Override
            public Class<StringCtx> type() {
                return StringCtx.class;
            }

            @Override
            public String namespace() {
                return keyOverride;
            }

            @Override
            public DurableMetadata encode(StringCtx value, DurableEncodeContext context) {
                return DurableMetadata.of(keyOverride, new JsonObject().put("value", value.value()));
            }
        };
    }
}
