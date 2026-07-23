// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import dev.vertique.workflow.timer.TimerPurpose;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Typed payload for a {@link dev.vertique.workflow.state.WorkflowEntryType#TIMER_SCHEDULED}
 * history entry.
 *
 * <p>Recorded when the engine schedules a durable timer for a
 * {@link dev.vertique.workflow.plan.TimerNode} step, the timeout branch of a
 * {@link dev.vertique.workflow.plan.WaitSignalNode}, or the due-date or reminder of a
 * {@link dev.vertique.workflow.plan.HumanTaskNode}.
 *
 * <p>The {@code kind} field migrated from the workflow-postgresql-local {@code TimerScheduledKind}
 * enum (cycle-3 values: {@code STANDALONE}, {@code WAIT_TIMEOUT}) to the canonical
 * {@link TimerPurpose} enum (cycle-4 values: {@code STANDALONE}, {@code SIGNAL_TIMEOUT},
 * {@code TASK_DUE}, {@code TASK_REMINDER}). A Jackson custom deserializer scoped only to the
 * {@code kind} field maps the legacy {@code "WAIT_TIMEOUT"} string to
 * {@link TimerPurpose#SIGNAL_TIMEOUT} when deserializing old history rows. Forward serialization
 * writes the canonical {@link TimerPurpose} name (e.g., {@code "SIGNAL_TIMEOUT"}).
 *
 * @param stepId the plan step id that caused the timer to be scheduled
 * @param timerId the stable UUID assigned to the timer
 * @param fireAt the UTC instant at which the timer is scheduled to fire
 * @param kind the canonical purpose of this timer; formerly {@code TimerScheduledKind}
 */
record TimerScheduledHistoryPayload(
        String stepId,
        UUID timerId,
        Instant fireAt,

        @JsonDeserialize(using = TimerScheduledHistoryPayload.TimerPurposeDeserializer.class)
        TimerPurpose kind) {

    // --- Backward-compat deserializer ---

    /**
     * Jackson deserializer scoped only to the {@code kind} field of
     * {@link TimerScheduledHistoryPayload}.
     *
     * <p>Maps the legacy cycle-3 value {@code "WAIT_TIMEOUT"} to
     * {@link TimerPurpose#SIGNAL_TIMEOUT} and delegates all other values to the standard
     * {@link TimerPurpose#valueOf(String)} lookup. The deserializer is NOT registered globally on
     * the {@code ObjectMapper} — it applies only to this field via the {@code @JsonDeserialize}
     * annotation above.
     */
    static final class TimerPurposeDeserializer extends StdDeserializer<TimerPurpose> {

        /** Serial version for {@link java.io.Serializable} compliance. */
        private static final long serialVersionUID = 1L;

        /**
         * Constructs a new deserializer for {@link TimerPurpose}.
         */
        TimerPurposeDeserializer() {
            super(TimerPurpose.class);
        }

        /**
         * Deserializes a {@link TimerPurpose} from a JSON string token.
         *
         * <p>Maps the legacy value {@code "WAIT_TIMEOUT"} to {@link TimerPurpose#SIGNAL_TIMEOUT}.
         * All other values are passed to {@link TimerPurpose#valueOf(String)} directly; an unknown
         * value results in an {@link IllegalArgumentException} propagated as a Jackson exception.
         *
         * @param parser  the JSON parser positioned at the string token
         * @param context the deserialization context
         * @return the deserialized {@link TimerPurpose}
         * @throws IOException if the token cannot be read
         * @throws JacksonException if the value is not a known {@link TimerPurpose} name
         */
        @Override
        public TimerPurpose deserialize(JsonParser parser, DeserializationContext context)
                throws IOException, JacksonException {
            String text = parser.getText();
            // Backward-compat: rows written by cycle-3 used the now-deleted TimerScheduledKind
            // enum with value "WAIT_TIMEOUT". Map to the canonical TimerPurpose equivalent.
            if ("WAIT_TIMEOUT".equals(text)) {
                return TimerPurpose.SIGNAL_TIMEOUT;
            }
            return TimerPurpose.valueOf(text);
        }
    }
}
