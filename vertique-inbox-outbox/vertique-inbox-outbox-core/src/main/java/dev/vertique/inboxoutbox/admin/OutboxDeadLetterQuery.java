// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import dev.vertique.inboxoutbox.DestinationType;
import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * Filter and paging parameters for listing dead-lettered outbox entries.
 *
 * <p>Results are ordered by failure time then entry ID, both descending. Every filter is optional and
 * an exact match; filters combine with AND. {@code after} continues strictly after a position taken
 * from a previous page. The query never filters on an entry's scope key.
 *
 * <p>Build one with {@link #builder()}. All bounds are checked when the query is constructed.
 *
 * @param entryId         match this entry ID; positive when present
 * @param destinationType match this destination type
 * @param destination     match this destination; 1 to 255 characters, not blank, when present
 * @param eventType       match this event type; 1 to 255 characters, not blank, when present
 * @param aggregateType   match this aggregate type; 1 to 255 characters, not blank, when present
 * @param aggregateId     match this aggregate ID; 1 to 255 characters, not blank, when present
 * @param errorType       match this error type; 1 to 500 characters, not blank, when present
 * @param failedFrom      only entries dead-lettered at or after this time (inclusive)
 * @param failedBefore    only entries dead-lettered before this time (exclusive); after
 *                        {@code failedFrom} when both are set
 * @param createdFrom     only entries created at or after this time (inclusive)
 * @param createdBefore   only entries created before this time (exclusive); after {@code createdFrom}
 *                        when both are set
 * @param limit           maximum number of summaries to return; 1 to 200
 * @param after           position to continue after, or {@code null} for the first page
 */
public record OutboxDeadLetterQuery(
        @Nullable Long entryId,
        @Nullable DestinationType destinationType,
        @Nullable String destination,
        @Nullable String eventType,
        @Nullable String aggregateType,
        @Nullable String aggregateId,
        @Nullable String errorType,
        @Nullable Instant failedFrom,
        @Nullable Instant failedBefore,
        @Nullable Instant createdFrom,
        @Nullable Instant createdBefore,
        int limit,
        @Nullable OutboxDeadLetterKey after) {

    /** Page size used when none is set. */
    public static final int DEFAULT_LIMIT = AdminChecks.DEFAULT_LIMIT;

    /** Largest accepted page size. */
    public static final int MAX_LIMIT = AdminChecks.MAX_LIMIT;

    /** Maximum length of the exact-match string filters other than the error type. */
    public static final int MAX_TEXT_LENGTH = 255;

    /** Maximum length of the error type filter. */
    public static final int MAX_ERROR_TYPE_LENGTH = 500;

    /**
     * Creates a query.
     *
     * @param entryId         entry ID filter; positive when present
     * @param destinationType destination type filter
     * @param destination     destination filter
     * @param eventType       event type filter
     * @param aggregateType   aggregate type filter
     * @param aggregateId     aggregate ID filter
     * @param errorType       error type filter
     * @param failedFrom      inclusive lower bound of the failure time
     * @param failedBefore    exclusive upper bound of the failure time
     * @param createdFrom     inclusive lower bound of the creation time
     * @param createdBefore   exclusive upper bound of the creation time
     * @param limit           maximum number of summaries; 1 to 200
     * @param after           position to continue after
     * @throws dev.vertique.core.exception.ValidationException if any value is outside its bound
     */
    public OutboxDeadLetterQuery {
        if (entryId != null) {
            AdminChecks.positive(entryId, "entryId");
        }
        AdminChecks.optionalText(destination, "destination", MAX_TEXT_LENGTH);
        AdminChecks.optionalText(eventType, "eventType", MAX_TEXT_LENGTH);
        AdminChecks.optionalText(aggregateType, "aggregateType", MAX_TEXT_LENGTH);
        AdminChecks.optionalText(aggregateId, "aggregateId", MAX_TEXT_LENGTH);
        AdminChecks.optionalText(errorType, "errorType", MAX_ERROR_TYPE_LENGTH);
        AdminChecks.ordered(failedFrom, failedBefore, "failedFrom", "failedBefore");
        AdminChecks.ordered(createdFrom, createdBefore, "createdFrom", "createdBefore");
        AdminChecks.limit(limit);
    }

    /**
     * Returns a builder with no filters and the default limit of 50.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link OutboxDeadLetterQuery}; validation runs in {@link #build()}. */
    public static final class Builder {

        private Long entryId;
        private DestinationType destinationType;
        private String destination;
        private String eventType;
        private String aggregateType;
        private String aggregateId;
        private String errorType;
        private Instant failedFrom;
        private Instant failedBefore;
        private Instant createdFrom;
        private Instant createdBefore;
        private int limit = DEFAULT_LIMIT;
        private OutboxDeadLetterKey after;

        private Builder() {}

        /**
         * Sets the entry ID filter.
         *
         * @param entryId positive entry ID, or {@code null} for no filter
         * @return this builder
         */
        public Builder entryId(@Nullable Long entryId) {
            this.entryId = entryId;
            return this;
        }

        /**
         * Sets the destination type filter.
         *
         * @param destinationType destination type, or {@code null} for no filter
         * @return this builder
         */
        public Builder destinationType(@Nullable DestinationType destinationType) {
            this.destinationType = destinationType;
            return this;
        }

        /**
         * Sets the destination filter.
         *
         * @param destination exact destination, or {@code null} for no filter
         * @return this builder
         */
        public Builder destination(@Nullable String destination) {
            this.destination = destination;
            return this;
        }

        /**
         * Sets the event type filter.
         *
         * @param eventType exact event type, or {@code null} for no filter
         * @return this builder
         */
        public Builder eventType(@Nullable String eventType) {
            this.eventType = eventType;
            return this;
        }

        /**
         * Sets the aggregate type filter.
         *
         * @param aggregateType exact aggregate type, or {@code null} for no filter
         * @return this builder
         */
        public Builder aggregateType(@Nullable String aggregateType) {
            this.aggregateType = aggregateType;
            return this;
        }

        /**
         * Sets the aggregate ID filter.
         *
         * @param aggregateId exact aggregate ID, or {@code null} for no filter
         * @return this builder
         */
        public Builder aggregateId(@Nullable String aggregateId) {
            this.aggregateId = aggregateId;
            return this;
        }

        /**
         * Sets the error type filter.
         *
         * @param errorType exact error type, or {@code null} for no filter
         * @return this builder
         */
        public Builder errorType(@Nullable String errorType) {
            this.errorType = errorType;
            return this;
        }

        /**
         * Sets the inclusive lower bound of the failure time.
         *
         * @param failedFrom lower bound, or {@code null} for none
         * @return this builder
         */
        public Builder failedFrom(@Nullable Instant failedFrom) {
            this.failedFrom = failedFrom;
            return this;
        }

        /**
         * Sets the exclusive upper bound of the failure time.
         *
         * @param failedBefore upper bound, or {@code null} for none
         * @return this builder
         */
        public Builder failedBefore(@Nullable Instant failedBefore) {
            this.failedBefore = failedBefore;
            return this;
        }

        /**
         * Sets the inclusive lower bound of the creation time.
         *
         * @param createdFrom lower bound, or {@code null} for none
         * @return this builder
         */
        public Builder createdFrom(@Nullable Instant createdFrom) {
            this.createdFrom = createdFrom;
            return this;
        }

        /**
         * Sets the exclusive upper bound of the creation time.
         *
         * @param createdBefore upper bound, or {@code null} for none
         * @return this builder
         */
        public Builder createdBefore(@Nullable Instant createdBefore) {
            this.createdBefore = createdBefore;
            return this;
        }

        /**
         * Sets the page size.
         *
         * @param limit maximum number of summaries; 1 to 200
         * @return this builder
         */
        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        /**
         * Sets the position to continue after.
         *
         * @param after position from a previous page, or {@code null} for the first page
         * @return this builder
         */
        public Builder after(@Nullable OutboxDeadLetterKey after) {
            this.after = after;
            return this;
        }

        /**
         * Builds the query.
         *
         * @return the validated query
         * @throws dev.vertique.core.exception.ValidationException if any value is outside its bound
         */
        public OutboxDeadLetterQuery build() {
            return new OutboxDeadLetterQuery(
                    entryId,
                    destinationType,
                    destination,
                    eventType,
                    aggregateType,
                    aggregateId,
                    errorType,
                    failedFrom,
                    failedBefore,
                    createdFrom,
                    createdBefore,
                    limit,
                    after);
        }
    }
}
