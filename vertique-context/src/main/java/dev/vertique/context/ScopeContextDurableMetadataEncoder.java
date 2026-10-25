// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.scope.ScopeContext;
import io.vertx.core.json.JsonObject;
import java.util.Set;

/** Internal durable encoder for an explicitly bound scope at supported producer boundaries. */
final class ScopeContextDurableMetadataEncoder implements DurableContextMetadataEncoder<ScopeContext> {

    private static final Set<String> SUPPORTED_BOUNDARIES =
            Set.of(DispatchBoundary.WORKFLOW, DispatchBoundary.OUTBOX, DispatchBoundary.DELAYED_JOB);

    /** {@inheritDoc} */
    @Override
    public Class<ScopeContext> type() {
        return ScopeContext.class;
    }

    /** {@inheritDoc} */
    @Override
    public String namespace() {
        return "scope";
    }

    /** {@inheritDoc} */
    @Override
    public DurableMetadata encode(ScopeContext value, DurableEncodeContext context) {
        if (!SUPPORTED_BOUNDARIES.contains(context.boundary())) {
            return DurableMetadata.empty();
        }
        return DurableMetadata.of(
                namespace(),
                new JsonObject()
                        .put("schemaVersion", 1)
                        .put("scopeKey", value.scopeKey().value()));
    }
}
