// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.exception.MalformedDurableMetadataException;
import io.vertx.core.json.JsonObject;

/**
 * Structured metadata document stored in the {@code outbox.metadata} JSONB column.
 *
 * <p>The document has two top-level sections:
 * <ul>
 *   <li>{@link #CONTEXT_KEY} — durable propagation context captured at publish time via
 *       {@link DurableMetadata}; used by relay destination handlers to restore context on the
 *       consume side.</li>
 *   <li>{@link #DELIVERY_KEY} — delivery-time control metadata ({@link OutboxDeliveryMetadata}):
 *       {@code delayedJob} (delayed-job scheduling, captured at publish and persisted) and
 *       {@code outbox} (relay control, projected at relay time and never persisted).</li>
 * </ul>
 *
 * <p>Use {@link #toJson()} to write to the database and {@link #fromJson(JsonObject)} to read it
 * back. The persisted JSON shape (relay control is never persisted) is:
 * <pre>{@code
 * {
 *   "context": { "correlation": {...}, "localization": {...} },
 *   "delivery": { "delayedJob": { "queue": "...", "priority": 5, "maxAttempts": 3 } }
 * }
 * }</pre>
 *
 * @param context  the durable propagation context captured at publish time; never {@code null}
 * @param delivery the delivery-time control metadata; never {@code null}
 */
public record OutboxMetadata(DurableMetadata context, OutboxDeliveryMetadata delivery) {

    /** JSON key for the durable propagation context section. */
    public static final String CONTEXT_KEY = "context";

    /** JSON key for the delivery control metadata section. */
    public static final String DELIVERY_KEY = "delivery";

    // --- Factories ---

    /**
     * Returns an empty metadata instance with no durable context and no delivery control.
     *
     * @return an empty {@code OutboxMetadata}
     */
    public static OutboxMetadata empty() {
        return new OutboxMetadata(DurableMetadata.empty(), OutboxDeliveryMetadata.empty());
    }

    // --- Serialization ---

    /**
     * Serializes this metadata to a JSON object suitable for storage in the {@code outbox.metadata}
     * JSONB column.
     *
     * <p>The {@code context} key always contains the bare namespaces document from
     * {@link DurableMetadata#toJson()}. The {@code delivery} key is omitted when the delivery
     * sub-document is empty (i.e., both {@link OutboxDeliveryMetadata#outbox()} and
     * {@link OutboxDeliveryMetadata#delayedJob()} are absent), keeping the document compact.
     *
     * @return the JSON representation; never {@code null}
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject().put(CONTEXT_KEY, context.toJson());
        JsonObject deliveryJson = delivery.toJson();
        if (!deliveryJson.isEmpty()) {
            json.put(DELIVERY_KEY, deliveryJson);
        }
        return json;
    }

    /**
     * Reconstructs an {@code OutboxMetadata} from the persisted JSON object.
     *
     * <p>Returns {@link #empty()} when {@code metadata} is {@code null} or empty. The
     * {@code context} section is read via {@link DurableMetadata#fromJson(JsonObject)}; the
     * {@code delivery} section is read via {@link OutboxDeliveryMetadata#fromJson(JsonObject)}.
     * Missing sections default to their respective {@code empty()} values.
     *
     * <p>Each top-level section is validated to be a JSON object before being handed to its
     * decoder — a raw (DB-tampered) shape is rejected here as a classified decode-time error
     * rather than surfacing as an unclassified {@link ClassCastException}.
     *
     * @param metadata the raw JSONB value from the database column, or {@code null}
     * @return the reconstructed metadata; never {@code null}
     * @throws MalformedDurableMetadataException if the {@link #CONTEXT_KEY} or {@link #DELIVERY_KEY}
     *                                            entry is present but is not a JSON object, or if
     *                                            the {@code context} section is otherwise malformed
     *                                            per {@link DurableMetadata#fromJson(JsonObject)}
     */
    public static OutboxMetadata fromJson(JsonObject metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return empty();
        }
        DurableMetadata context = DurableMetadata.fromJson(requireObjectSection(metadata, CONTEXT_KEY));
        OutboxDeliveryMetadata delivery = OutboxDeliveryMetadata.fromJson(requireObjectSection(metadata, DELIVERY_KEY));
        return new OutboxMetadata(context, delivery);
    }

    /**
     * Reads {@code key} from {@code metadata}, rejecting a present-but-non-object value at decode
     * time rather than deferring to a later unclassified {@link ClassCastException}.
     *
     * @param metadata the enclosing JSON object
     * @param key      the section key to read
     * @return the section value as a {@link JsonObject}, or {@code null} if the key is absent
     * @throws MalformedDurableMetadataException if the key is present but its value is not a JSON
     *                                            object
     */
    private static JsonObject requireObjectSection(JsonObject metadata, String key) {
        if (!metadata.containsKey(key)) {
            return null;
        }
        Object value = metadata.getValue(key);
        if (!(value instanceof JsonObject json)) {
            throw new MalformedDurableMetadataException("outbox metadata key '" + key
                    + "' must be a JSON object, but was "
                    + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        return json;
    }
}
