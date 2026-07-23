// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Discriminator for the type of destination an outbox entry targets.
 *
 * <p>An <strong>open value type</strong> wrapping a validated string id, not a closed enum: a new
 * outbox destination registers by returning {@code DestinationType.of("its-id")} from its
 * {@link OutboxDestinationHandler}, with no edit to this class. The relay selects the appropriate
 * {@link OutboxDestinationHandler} based on this value when delivering an outbox entry.
 *
 * <p>The three framework destinations ({@link #SERVICE}, {@link #DELAYED_JOB}, {@link #KAFKA}) are
 * provided as canonical built-in constants whose ids equal their former enum names, so the
 * persisted {@code destination_type} strings and the JSON wire form are unchanged.
 *
 * <p><strong>Equality is by id</strong> — never by reference. Two instances obtained from
 * {@code of(...)} with the same id are equal, so a {@code DestinationType} read back from the
 * database matches a built-in constant or a handler-declared type. Built-ins are canonical
 * instances; arbitrary ids accepted through {@link #of(String)} are not interned (each call
 * returns a fresh instance), so untrusted input cannot grow an unbounded intern table.
 *
 * <p>The string id is constrained to {@value #ID_PATTERN_SOURCE} — short and simple enough to fit
 * the {@code destination_type VARCHAR(32)} column and to be safe as a routing key.
 */
public final class DestinationType {

    private static final String ID_PATTERN_SOURCE = "^[A-Za-z0-9_-]{1,32}$";
    private static final Pattern ID_PATTERN = Pattern.compile(ID_PATTERN_SOURCE);

    // --- Built-in constants (canonical instances; ids equal the former enum names) ---

    /**
     * The destination is a Vert.x event bus service address. The relay delivers the payload by
     * dispatching a service call via the services framework, enabling full interceptor and error
     * pipeline support.
     */
    public static final DestinationType SERVICE = new DestinationType("SERVICE");

    /**
     * The destination is a delayed job handler name. The relay enqueues the payload as a delayed
     * job, which is then processed asynchronously by the job execution infrastructure.
     */
    public static final DestinationType DELAYED_JOB = new DestinationType("DELAYED_JOB");

    /**
     * The destination is a Kafka topic. The relay produces a Kafka record with the outbox payload
     * as the message value and the outbox headers as Kafka record headers.
     */
    public static final DestinationType KAFKA = new DestinationType("KAFKA");

    /**
     * Registry of the canonical built-in instances by id, initialised <em>after</em> the constants
     * above so {@link #of(String)} can return the canonical instance for a built-in id rather than
     * a fresh equal one. Unknown-but-valid ids are not added here (no unbounded interning).
     */
    private static final Map<String, DestinationType> BUILT_INS =
            Map.of(SERVICE.id, SERVICE, DELAYED_JOB.id, DELAYED_JOB, KAFKA.id, KAFKA);

    private final String id;

    private DestinationType(String id) {
        this.id = id;
    }

    // --- Factory ---

    /**
     * Returns the destination type with the given id.
     *
     * <p>Validates that {@code id} matches {@value #ID_PATTERN_SOURCE}. A built-in id returns its
     * canonical constant; any other valid id returns a fresh (non-interned) instance. Unlike an
     * enum {@code valueOf}, this does <em>not</em> require the id to be a known/registered type — an
     * unrecognised but well-formed {@code destination_type} read from the database maps cleanly and
     * simply stays unclaimed until a handler for it is registered.
     *
     * @param id the destination type id (e.g. {@code "KAFKA"}); must be non-null and match
     *           {@value #ID_PATTERN_SOURCE}
     * @return the destination type for {@code id}
     * @throws NullPointerException     if {@code id} is {@code null}
     * @throws IllegalArgumentException if {@code id} does not match {@value #ID_PATTERN_SOURCE}
     */
    @JsonCreator
    public static DestinationType of(String id) {
        Objects.requireNonNull(id, "id");
        if (!ID_PATTERN.matcher(id).matches()) {
            // The rejected id is echoed into the message on the untrusted @JsonCreator path. Because it
            // failed the pattern it may contain control characters (CR/LF) — strip them to prevent
            // forged log lines, and bound the length to avoid log amplification. Truncate first so the
            // sanitising scan runs over a small string.
            String shown = id.length() <= 32 ? id : id.substring(0, 32) + "…";
            shown = shown.replaceAll("\\p{Cntrl}", "?");
            throw new IllegalArgumentException(
                    "Invalid destination type id (must match " + ID_PATTERN_SOURCE + "): '" + shown + "'");
        }
        DestinationType builtIn = BUILT_INS.get(id);
        return builtIn != null ? builtIn : new DestinationType(id);
    }

    // --- Accessors ---

    /**
     * Returns the string id of this destination type — the persisted {@code destination_type} value
     * and the JSON wire form.
     *
     * @return the validated id
     */
    @JsonValue
    public String id() {
        return id;
    }

    // --- Identity (by id) ---

    /**
     * Two {@code DestinationType} instances are equal iff their {@link #id()} values are equal.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code DestinationType} with an equal id
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof DestinationType other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /**
     * Returns the id; the string form of a {@code DestinationType} is its id.
     *
     * @return the id
     */
    @Override
    public String toString() {
        return id;
    }
}
