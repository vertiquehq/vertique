// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import io.vertx.core.json.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Projects a {@link DurableMetadata} document to and from a flat, string-keyed header carrier such
 * as Kafka record headers. Each namespace maps to exactly one reserved header whose name is
 * {@link #RESERVED_PREFIX} + namespace and whose value is the namespace body serialized as a JSON
 * string (UTF-8). Only the durable <em>context</em> is projected; delivery/transport data is never
 * carried by this codec.
 *
 * <p>The {@link #RESERVED_PREFIX} carves out a header sub-keyspace for framework context so it cannot
 * collide with application headers. {@link #mergeForEgress(Map, DurableMetadata)} is the single
 * enforcement point: it rejects any application header that uses the reserved prefix, then overlays
 * the projected context headers — reused by both the direct producer path and the outbox→Kafka relay.
 */
public final class DurableMetadataHeaderCodec {

    /** Reserved header-name prefix for framework durable-context namespaces. */
    public static final String RESERVED_PREFIX = "vertique-";

    private DurableMetadataHeaderCodec() {}

    /**
     * Projects each namespace of {@code context} to a reserved header.
     *
     * @param context the durable context document; must not be {@code null}
     * @return an immutable map of reserved header name → namespace-body JSON
     */
    public static Map<String, String> toHeaders(DurableMetadata context) {
        Objects.requireNonNull(context, "context");
        Map<String, String> headers = new LinkedHashMap<>();
        for (String ns : context.namespaces()) {
            headers.put(RESERVED_PREFIX + ns, context.body(ns).orElseThrow().encode());
        }
        return Map.copyOf(headers);
    }

    /**
     * Reconstructs a durable context document from a header carrier, reading only reserved headers.
     * A reserved header whose value does not parse as a JSON object is skipped (the namespace is
     * absent in the result), so a single malformed namespace cannot poison the others.
     *
     * @param headers the carrier headers; {@code null}/empty yields {@link DurableMetadata#empty()}
     * @return the reconstructed context document
     */
    public static DurableMetadata fromHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return DurableMetadata.empty();
        }
        DurableMetadata result = DurableMetadata.empty();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!isReservedHeader(entry.getKey())) {
                continue;
            }
            String namespace = entry.getKey().substring(RESERVED_PREFIX.length());
            if (namespace.isBlank()) {
                continue;
            }
            JsonObject body = parseObject(entry.getValue());
            if (body == null) {
                continue; // malformed namespace header — skip, do not poison other namespaces
            }
            result = result.with(namespace, body);
        }
        return result;
    }

    /**
     * @param headerName a header name (may be {@code null})
     * @return {@code true} if the name uses the reserved framework prefix
     */
    public static boolean isReservedHeader(String headerName) {
        return headerName != null && headerName.startsWith(RESERVED_PREFIX);
    }

    /**
     * Builds the outbound header set for a durable boundary: application headers with the projected
     * context headers overlaid. This is the single collision-enforcement point.
     *
     * @param appHeaders application/transport headers (may be {@code null})
     * @param context    the durable context to project; must not be {@code null}
     * @return an immutable merged header map
     * @throws IllegalArgumentException if any application header uses the reserved framework prefix
     */
    public static Map<String, String> mergeForEgress(Map<String, String> appHeaders, DurableMetadata context) {
        Objects.requireNonNull(context, "context");
        Map<String, String> merged = new LinkedHashMap<>();
        if (appHeaders != null) {
            for (Map.Entry<String, String> entry : appHeaders.entrySet()) {
                if (isReservedHeader(entry.getKey())) {
                    throw new IllegalArgumentException("Application header uses reserved framework prefix '"
                            + RESERVED_PREFIX + "': " + entry.getKey());
                }
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        merged.putAll(toHeaders(context));
        return Map.copyOf(merged);
    }

    private static JsonObject parseObject(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new JsonObject(value);
        } catch (RuntimeException ex) {
            return null; // not a JSON object — treat as malformed
        }
    }
}
