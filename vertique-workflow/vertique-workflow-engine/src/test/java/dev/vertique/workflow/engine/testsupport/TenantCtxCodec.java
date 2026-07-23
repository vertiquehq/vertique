// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.testsupport;

import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import io.vertx.core.json.JsonObject;
import java.util.Set;

/**
 * Test-only encoder/decoder pair and {@link DurableContextPropagator} factory for {@link TenantCtx}.
 *
 * <p>Modeled on {@code WorkflowTimerRecoveryDurableBindTest.propagatorWithStringEncoder}
 * (vertique-workflow-delayed) — shared across the durable-context test suites in this module so
 * each test class does not re-declare the same anonymous encoder/decoder pair. Also reused by
 * {@code vertique-workflow-postgresql} via this module's test-jar (see {@link TenantCtx}'s
 * javadoc).
 */
public final class TenantCtxCodec {

    /** Durable namespace key under which {@link TenantCtx} is carried. */
    public static final String NAMESPACE = "tenant";

    private TenantCtxCodec() {
        // static factory holder
    }

    /**
     * Returns a {@link DurableContextMetadataEncoder} for {@link TenantCtx} that writes the
     * {@link #NAMESPACE} namespace.
     *
     * @return the tenant encoder
     */
    public static DurableContextMetadataEncoder<TenantCtx> encoder() {
        return new DurableContextMetadataEncoder<>() {
            @Override
            public Class<TenantCtx> type() {
                return TenantCtx.class;
            }

            @Override
            public String namespace() {
                return NAMESPACE;
            }

            @Override
            public DurableMetadata encode(TenantCtx value, DurableEncodeContext context) {
                return DurableMetadata.of(NAMESPACE, new JsonObject().put("tenantId", value.tenantId()));
            }
        };
    }

    /**
     * Returns a {@link DurableContextMetadataDecoder} for {@link TenantCtx} that reads the
     * {@link #NAMESPACE} namespace.
     *
     * @return the tenant decoder
     */
    public static DurableContextMetadataDecoder<TenantCtx> decoder() {
        return new DurableContextMetadataDecoder<>() {
            @Override
            public Class<TenantCtx> type() {
                return TenantCtx.class;
            }

            @Override
            public String namespace() {
                return NAMESPACE;
            }

            @Override
            public ContextDecodeResult<TenantCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return metadata.body(NAMESPACE)
                        .map(body -> body.getString("tenantId"))
                        .map(TenantCtx::new)
                        .map(ContextDecodeResult::of)
                        .orElseGet(ContextDecodeResult::empty);
            }
        };
    }

    /**
     * Builds a {@link DurableContextPropagator} wired with the {@link #encoder()} /
     * {@link #decoder()} pair over the given holder.
     *
     * @param holder the context holder backing the propagator
     * @return a fully-wired propagator that captures/binds {@link TenantCtx} only
     */
    public static DurableContextPropagator propagator(DefaultContextHolder holder) {
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(encoder()), Set.of(decoder()));
        return new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));
    }
}
