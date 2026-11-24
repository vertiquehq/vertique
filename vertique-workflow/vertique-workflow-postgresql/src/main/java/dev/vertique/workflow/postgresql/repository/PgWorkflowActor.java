// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.actor.WorkflowActorMaps;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: maps a {@link WorkflowActor} to and from the {@code {"kind","value"}} JSONB value that every workflow
 * table stores it as: the commands, the history, the definitions, the activations and the tasks.
 *
 * <p>The value is the one {@link WorkflowActorMaps} defines. A column that records who acted has three states: SQL
 * null is a row not attributed yet, {@code {"kind":"NONE"}} is a row attributed to no one, and an actor object names
 * the actor.
 */
public final class PgWorkflowActor {

    private static final Logger log = LoggerFactory.getLogger(PgWorkflowActor.class);

    /** The kind of a row that is attributed to no one. */
    private static final String NONE = "NONE";

    private PgWorkflowActor() {}

    /**
     * Returns the stored form of an actor.
     *
     * @param actor the actor; must not be null
     * @return a new JSON object with the kind and the value
     */
    public static JsonObject toJson(WorkflowActor actor) {
        return new JsonObject(new LinkedHashMap<>(WorkflowActorMaps.toMap(actor)));
    }

    /**
     * Returns the stored form of an actor, or of no one.
     *
     * @param actor the actor, or {@code null} for an action that is attributed to no one
     * @return a new JSON object: the actor, or the kind {@code NONE}
     */
    public static JsonObject toJsonOrNone(@Nullable WorkflowActor actor) {
        return actor == null ? new JsonObject().put("kind", NONE) : toJson(actor);
    }

    /**
     * Reads the stored form of an actor.
     *
     * @param stored the stored value
     * @return the actor; {@code null} for the kind {@code NONE}
     * @throws IllegalArgumentException if the value is not a stored actor; the message holds no stored text
     */
    public static @Nullable WorkflowActor fromJson(JsonObject stored) {
        Object kind = stored.getValue("kind");
        Object value = stored.getValue("value");
        if (!(kind instanceof String kindName) || (value != null && !(value instanceof String))) {
            throw new IllegalArgumentException("The stored actor has no readable kind and value");
        }
        return NONE.equals(kindName) ? null : WorkflowActorMaps.toActor(kindName, (String) value);
    }

    /**
     * Reads the actor of a row for a read that must not fail on one unreadable row.
     *
     * @param row the row
     * @param column the JSONB column that holds the actor
     * @return the actor; {@code null} for a row that records none, or whose stored actor cannot be read, which is
     *     logged with the class name of the failure and no stored text
     */
    public static @Nullable WorkflowActor read(Row row, String column) {
        try {
            JsonObject stored = row.getJsonObject(column);
            return stored == null ? null : fromJson(stored);
        } catch (RuntimeException invalid) {
            // One unreadable row must not fail the page it is on; the failure names no stored value.
            log.warn(
                    "A stored workflow actor could not be read ({})",
                    invalid.getClass().getName());
            return null;
        }
    }
}
