// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextDecodeWarning;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationHeaderValidator;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.correlation.CorrelationPropagationMode;
import dev.vertique.core.correlation.CorrelationResponseMode;
import dev.vertique.core.correlation.CorrelationSessionRef;
import dev.vertique.core.correlation.ProtocolCorrelationRef;
import dev.vertique.core.correlation.TraceReference;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Durable metadata decoder for {@link CorrelationContext} (Context contribution model level 4 —
 * bespoke).
 *
 * <p>Reads the {@link CorrelationDurableKeys#CORRELATION} JSON envelope written by
 * {@link CorrelationContextDurableEncoder}, validates schema and required fields, re-validates
 * every protocol header name and value against {@link CorrelationHeaderValidator}, and rebuilds a
 * fresh {@link MutableCorrelationContext} via {@link CorrelationContextFactory#fromSnapshot}.
 *
 * <p>Failure modes emit one {@link ContextDecodeWarning} for the {@code vertique-correlation} key
 * (the substrate's {@code DurableContextPropagator} log-throttles warnings on the decoder's
 * behalf — never throw):
 * <ul>
 *   <li>Malformed JSON → single warning with the parser message in {@code reason}.</li>
 *   <li>Missing or unsupported {@code schemaVersion} → warning, no rebuild.</li>
 *   <li>Missing or blank required ids (requestId/correlationId) → warning, no rebuild.</li>
 *   <li>Unknown enum values ({@code responseMode}/{@code propagationMode}) → warning.</li>
 *   <li>Invalid header name/value on any {@link ProtocolCorrelationRef} → warning.</li>
 * </ul>
 *
 * <p>Unknown OPTIONAL fields in the JSON are ignored (FR-COR-152) via the
 * {@code @JsonIgnoreProperties(ignoreUnknown=true)} on {@link CorrelationEnvelope}.
 */
@Singleton
public final class CorrelationContextDurableDecoder implements DurableContextMetadataDecoder<CorrelationContext> {

    private final CorrelationContextFactory factory;

    @Inject
    public CorrelationContextDurableDecoder(CorrelationContextFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public Class<CorrelationContext> type() {
        return CorrelationContext.class;
    }

    @Override
    public String namespace() {
        return CorrelationDurableKeys.CORRELATION;
    }

    @Override
    public ContextDecodeResult<CorrelationContext> decode(DurableMetadata metadata, DurableDecodeContext context) {
        // Re-encode the namespace body to a JSON string so the bespoke JsonNode validation below is
        // preserved verbatim; absent namespace -> empty (legacy / no-context record).
        String json = metadata == null
                ? null
                : metadata.body(CorrelationDurableKeys.CORRELATION)
                        .map(JsonObject::encode)
                        .orElse(null);
        if (json == null || json.isBlank()) {
            return ContextDecodeResult.empty();
        }

        JsonNode root;
        try {
            root = DatabindCodec.mapper().readTree(json);
        } catch (Exception e) {
            return failure("malformed CorrelationEnvelope JSON: " + e.getMessage(), json);
        }
        if (root == null || !root.isObject()) {
            return failure("CorrelationEnvelope must be a JSON object", json);
        }

        // schemaVersion: required and bounded.
        if (!root.hasNonNull("schemaVersion") || !root.get("schemaVersion").isInt()) {
            return failure("missing required field 'schemaVersion'", json);
        }
        int schemaVersion = root.get("schemaVersion").asInt();
        if (schemaVersion > CorrelationEnvelope.CURRENT_SCHEMA_VERSION) {
            return failure(
                    "unsupported schemaVersion " + schemaVersion + " (max supported is "
                            + CorrelationEnvelope.CURRENT_SCHEMA_VERSION + ")",
                    json);
        }

        // FR-COR-145: every failure mode — internal DecodeFailure or IllegalArgumentException
        // raised by value-record constructors on blank required fields — unwinds into a single
        // ContextDecodeWarning instead of propagating.
        try {
            CorrelationIdentifier requestId = requireIdentifier(root, "requestId");
            CorrelationIdentifier correlationId = requireIdentifier(root, "correlationId");
            CorrelationIdentifier causationId = readIdentifier(root, "causationId");
            TraceReference trace = readTrace(root.get("trace"));
            CorrelationSessionRef session = readSession(root.get("session"));
            List<ProtocolCorrelationRef> protocols = readProtocols(root.get("protocolCorrelations"));
            Map<String, String> attributes = readAttributes(root.get("attributes"));

            CorrelationContext rebuilt =
                    factory.fromSnapshot(new dev.vertique.core.correlation.CorrelationContextSnapshot(
                            requestId, correlationId, causationId, trace, protocols, session, attributes));
            return ContextDecodeResult.of(rebuilt);
        } catch (DecodeFailure e) {
            return failure(e.getMessage(), json);
        } catch (IllegalArgumentException iae) {
            return failure("invalid CorrelationEnvelope field: " + iae.getMessage(), json);
        }
    }

    // --- Field readers ---

    private static CorrelationIdentifier requireIdentifier(JsonNode root, String field) {
        CorrelationIdentifier id = readIdentifier(root, field);
        if (id == null) {
            throw new DecodeFailure("missing required field '" + field + "'");
        }
        return id;
    }

    private static CorrelationIdentifier readIdentifier(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject() || !node.hasNonNull("value") || !node.hasNonNull("source")) {
            throw new DecodeFailure("field '" + field + "' must be an object with non-null 'value' and 'source'");
        }
        String value = node.get("value").asText();
        String source = node.get("source").asText();
        if (value.isBlank() || source.isBlank()) {
            throw new DecodeFailure("field '" + field + "' has blank value or source");
        }
        return new CorrelationIdentifier(value, source);
    }

    private static TraceReference readTrace(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject() || !node.hasNonNull("traceId") || !node.hasNonNull("source")) {
            throw new DecodeFailure("'trace' must carry non-null 'traceId' and 'source'");
        }
        String traceId = node.get("traceId").asText();
        String spanId = node.hasNonNull("spanId") ? node.get("spanId").asText() : null;
        String source = node.get("source").asText();
        return new TraceReference(traceId, spanId, source);
    }

    private static CorrelationSessionRef readSession(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new DecodeFailure("'session' must be a JSON object");
        }
        String id = textField(node, "id");
        String kind = textField(node, "kind");
        String source = textField(node, "source");
        String claimName = node.hasNonNull("claimName") ? node.get("claimName").asText() : null;
        boolean durableSafe =
                node.hasNonNull("durableSafe") && node.get("durableSafe").asBoolean();
        Map<String, String> attrs = readAttributes(node.get("attributes"));
        return new CorrelationSessionRef(id, kind, source, claimName, durableSafe, attrs);
    }

    private static List<ProtocolCorrelationRef> readProtocols(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new DecodeFailure("'protocolCorrelations' must be a JSON array");
        }
        List<ProtocolCorrelationRef> out = new ArrayList<>(node.size());
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (!item.isObject()) {
                throw new DecodeFailure("protocolCorrelations[" + i + "] must be an object");
            }
            String headerName = textField(item, "headerName");
            String value = textField(item, "value");
            String source = textField(item, "source");
            CorrelationResponseMode rm = parseEnum(
                    CorrelationResponseMode.class,
                    textField(item, "responseMode"),
                    "protocolCorrelations[" + i + "].responseMode");
            CorrelationPropagationMode pm = parseEnum(
                    CorrelationPropagationMode.class,
                    textField(item, "propagationMode"),
                    "protocolCorrelations[" + i + "].propagationMode");
            boolean durableSafe =
                    item.hasNonNull("durableSafe") && item.get("durableSafe").asBoolean();
            Map<String, String> attrs = readAttributes(item.get("attributes"));

            // Re-validate header name + value at the boundary so a malicious or corrupted upstream
            // cannot smuggle invalid values into our holder.
            if (!CorrelationHeaderValidator.isValidHeaderName(headerName)) {
                throw new DecodeFailure("protocolCorrelations[" + i + "].headerName failed validator: " + headerName);
            }
            if (!CorrelationHeaderValidator.isValidHeaderValue(value)) {
                throw new DecodeFailure("protocolCorrelations[" + i + "].value failed validator");
            }
            out.add(new ProtocolCorrelationRef(headerName, value, source, rm, pm, durableSafe, attrs));
        }
        return Collections.unmodifiableList(out);
    }

    private static Map<String, String> readAttributes(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new DecodeFailure("'attributes' must be a JSON object");
        }
        Map<String, String> map = new HashMap<>(node.size());
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (v == null || v.isNull()) {
                continue;
            }
            map.put(e.getKey(), v.asText());
        }
        return Map.copyOf(map);
    }

    private static String textField(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            throw new DecodeFailure("missing required field '" + field + "'");
        }
        return node.get(field).asText();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String fieldPath) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException ex) {
            throw new DecodeFailure("unknown enum value '" + raw + "' for field '" + fieldPath + "'");
        }
    }

    private static ContextDecodeResult<CorrelationContext> failure(String reason, String raw) {
        return ContextDecodeResult.failure(
                List.of(new ContextDecodeWarning(CorrelationDurableKeys.CORRELATION, raw, reason)));
    }

    /** Sentinel exception unwound at the public boundary into a {@link ContextDecodeResult#failure}. */
    private static final class DecodeFailure extends RuntimeException {
        DecodeFailure(String message) {
            super(message);
        }
    }
}
