// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntryState;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Builder;

/**
 * The filters and order of an outbox entry listing.
 *
 * <p>At least one state is required, so a listing never has to scan the whole table, and every other filter is
 * optional and narrows the result. Time ranges are inclusive at the start and exclusive at the end. A text
 * filter matches the whole stored value exactly.
 *
 * <p>The query does not filter on, and results do not carry, an entry's scope key.
 *
 * @param states          the states to list; at least one, held as an unmodifiable set
 * @param destinationType only entries for this destination type, or {@code null} for all
 * @param destination     only entries for this destination, or {@code null} for all; at most 255 characters
 * @param eventType       only entries of this event type, or {@code null} for all; at most 255 characters
 * @param aggregateType   only entries of this aggregate type, or {@code null} for all; at most 255 characters
 * @param aggregateId     only entries of this aggregate, or {@code null} for all; at most 255 characters
 * @param createdFrom     only entries created at or after this instant, or {@code null}
 * @param createdBefore   only entries created before this instant, or {@code null}
 * @param updatedFrom     only entries last changed at or after this instant, or {@code null}
 * @param updatedBefore   only entries last changed before this instant, or {@code null}
 * @param sort            the order; {@code null} reads as {@link OutboxSort#UPDATED_AT_DESC}
 */
@Builder(toBuilder = true)
public record OutboxQuery(
        Set<OutboxEntryState> states,
        @Nullable DestinationType destinationType,
        @Nullable String destination,
        @Nullable String eventType,
        @Nullable String aggregateType,
        @Nullable String aggregateId,
        @Nullable Instant createdFrom,
        @Nullable Instant createdBefore,
        @Nullable Instant updatedFrom,
        @Nullable Instant updatedBefore,
        @Nullable OutboxSort sort) {

    /** Longest accepted text filter, in characters; the width of the stored columns. */
    public static final int MAX_TEXT_LENGTH = 255;

    /**
     * Creates a validated query.
     *
     * @throws ValidationException if no state is given, a state is {@code null}, a text filter is blank, too long
     *                             or holds a control character, an instant is out of range, or a range start is
     *                             not before its end
     */
    public OutboxQuery {
        QueryChecks.required(states, "states");
        if (states.isEmpty()) {
            throw new ValidationException("states must not be empty");
        }
        for (OutboxEntryState state : states) {
            QueryChecks.required(state, "states element");
        }
        states = Collections.unmodifiableSet(EnumSet.copyOf(states));
        QueryChecks.optionalText(destination, "destination", MAX_TEXT_LENGTH);
        QueryChecks.optionalText(eventType, "eventType", MAX_TEXT_LENGTH);
        QueryChecks.optionalText(aggregateType, "aggregateType", MAX_TEXT_LENGTH);
        QueryChecks.optionalText(aggregateId, "aggregateId", MAX_TEXT_LENGTH);
        QueryChecks.instant(createdFrom, "createdFrom");
        QueryChecks.instant(createdBefore, "createdBefore");
        QueryChecks.instant(updatedFrom, "updatedFrom");
        QueryChecks.instant(updatedBefore, "updatedBefore");
        QueryChecks.ordered(createdFrom, createdBefore, "createdFrom", "createdBefore");
        QueryChecks.ordered(updatedFrom, updatedBefore, "updatedFrom", "updatedBefore");
        if (sort == null) {
            sort = OutboxSort.UPDATED_AT_DESC;
        }
    }

    /**
     * Creates a query for the given states with no other filter and the default order.
     *
     * @param states the states to list; at least one
     * @return the query
     * @throws ValidationException if no state is given
     */
    public static OutboxQuery inStates(OutboxEntryState... states) {
        QueryChecks.required(states, "states");
        return new OutboxQuery(
                new LinkedHashSet<>(Arrays.asList(states)), null, null, null, null, null, null, null, null, null, null);
    }
}
