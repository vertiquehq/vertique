// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import io.vertx.core.json.JsonObject;
import java.util.Optional;

/**
 * INTERNAL framework seam — consumed by the inbox-outbox adapters and sibling framework modules; not
 * an application contract and outside the maturity promise. Applications use {@code OutboxService},
 * {@code InboxService}, and the extension points the module document lists.
 *
 * <p>Delivery-time control metadata embedded inside {@link OutboxMetadata}.
 *
 * <p>Contains an optional {@link OutboxRelayControl} (projected at relay time from the row columns,
 * never serialized) and an optional {@link DelayedJobControl} (captured at publish and serialized
 * into the {@code metadata} JSONB column for delayed-job entries).
 *
 * <p>Serialization note: {@link OutboxRelayControl} is a relay-time construct only and is
 * <em>never</em> written to or read from the database. {@link DelayedJobControl} is persisted
 * when non-empty.
 *
 * @param outbox     relay-time relay control values; never persisted
 * @param delayedJob optional delayed-job scheduling control; persisted when present
 */
public record OutboxDeliveryMetadata(Optional<OutboxRelayControl> outbox, Optional<DelayedJobControl> delayedJob) {

    /** JSON key for the {@link DelayedJobControl} sub-document. */
    public static final String DELAYED_JOB_KEY = "delayedJob";

    // --- Factories ---

    /**
     * Returns an empty delivery metadata instance with no relay control or delayed-job control.
     *
     * @return an empty {@code OutboxDeliveryMetadata}
     */
    public static OutboxDeliveryMetadata empty() {
        return new OutboxDeliveryMetadata(Optional.empty(), Optional.empty());
    }

    // --- Serialization ---

    /**
     * Serializes this delivery metadata to a JSON object.
     *
     * <p>{@link OutboxRelayControl} is relay-time only and is never included in the output.
     * Returns an empty {@link JsonObject} when there is no persisted sub-field (i.e., when
     * {@link #delayedJob()} is absent).
     *
     * @return a JSON representation of the persisted delivery control fields; never {@code null}
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        delayedJob.ifPresent(dj -> {
            JsonObject djJson = new JsonObject()
                    .put("queue", dj.queue())
                    .put("priority", dj.priority())
                    .put("maxAttempts", dj.maxAttempts());
            json.put(DELAYED_JOB_KEY, djJson);
        });
        return json;
    }

    /**
     * Reconstructs delivery metadata from a persisted JSON object.
     *
     * <p>{@link OutboxRelayControl} is always absent in the deserialized result — it is only
     * populated at relay time. Returns {@link #empty()} when {@code delivery} is {@code null}
     * or empty.
     *
     * @param delivery the {@code delivery} sub-document from the outbox {@code metadata} column,
     *                 or {@code null}
     * @return the reconstructed delivery metadata; never {@code null}
     */
    public static OutboxDeliveryMetadata fromJson(JsonObject delivery) {
        if (delivery == null || delivery.isEmpty()) {
            return empty();
        }
        Optional<DelayedJobControl> delayedJob = Optional.empty();
        JsonObject djJson = delivery.getJsonObject(DELAYED_JOB_KEY);
        if (djJson != null) {
            delayedJob = Optional.of(new DelayedJobControl(
                    djJson.getString("queue"), djJson.getInteger("priority", 0), djJson.getInteger("maxAttempts", 0)));
        }
        return new OutboxDeliveryMetadata(Optional.empty(), delayedJob);
    }
}
