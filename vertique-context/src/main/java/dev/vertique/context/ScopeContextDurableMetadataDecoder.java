// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextDecodeWarning;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.scope.ScopeContext;
import dev.vertique.core.scope.ScopeKey;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Internal versioned scope decoder with boundary filtering and input-free failure warnings. */
final class ScopeContextDurableMetadataDecoder implements DurableContextMetadataDecoder<ScopeContext> {

    private static final Set<String> SUPPORTED_BOUNDARIES =
            Set.of(DispatchBoundary.WORKFLOW, DispatchBoundary.OUTBOX_SERVICE, DispatchBoundary.DELAYED_JOB);

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
    public ContextDecodeResult<ScopeContext> decode(DurableMetadata metadata, DurableDecodeContext context) {
        if (!SUPPORTED_BOUNDARIES.contains(context.boundary())) {
            return ContextDecodeResult.empty();
        }
        Optional<JsonObject> scopeBody = metadata.body(namespace());
        if (scopeBody.isEmpty()) {
            return ContextDecodeResult.empty();
        }
        JsonObject body = scopeBody.get();
        Object schemaVersion = body.getValue("schemaVersion");
        if (!Integer.valueOf(1).equals(schemaVersion) && !Long.valueOf(1L).equals(schemaVersion)) {
            return failure("unsupported-scope-schema");
        }
        if (!(body.getValue("scopeKey") instanceof String scopeKey)) {
            return failure("scope-key-required");
        }
        try {
            return ContextDecodeResult.of(new ScopeContext(new ScopeKey(scopeKey)));
        } catch (IllegalArgumentException ignored) {
            return failure("invalid-scope-key");
        }
    }

    /** Returns one warning containing only the namespace and a fixed rejection reason. */
    private ContextDecodeResult<ScopeContext> failure(String reason) {
        return ContextDecodeResult.failure(List.of(new ContextDecodeWarning(namespace(), null, reason)));
    }
}
