// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.repository;

import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.actor.WorkflowAttribution;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import jakarta.annotation.Nullable;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: maps the four attribution columns shared by workflow commands, history and definitions: the kind, id and
 * reason of the actor, and the party it acted for. The text is stored as it is; {@link WorkflowAttribution} refuses a
 * value the columns cannot hold.
 */
public final class PgWorkflowAttribution {

    private static final Logger log = LoggerFactory.getLogger(PgWorkflowAttribution.class);

    private PgWorkflowAttribution() {}

    /**
     * Appends the four attribution values. No attribution at all appends four nulls; an attribution with no actor
     * appends the kind {@code NONE}, which marks the row as attributed to no one.
     *
     * @param tuple the parameters so far
     * @param attribution the attribution, or {@code null} when the change was recorded without one
     * @return the tuple
     */
    public static Tuple bind(Tuple tuple, @Nullable WorkflowAttribution attribution) {
        WorkflowActor actor = attribution == null ? null : attribution.actor();
        String kind =
                switch (actor) {
                    case WorkflowActor.User ignored -> "USER";
                    case WorkflowActor.Service ignored -> "SERVICE";
                    case WorkflowActor.System ignored -> "SYSTEM";
                    case null -> attribution == null ? null : "NONE";
                };
        String id =
                switch (actor) {
                    case WorkflowActor.User user -> user.userId();
                    case WorkflowActor.Service service -> service.serviceId();
                    case WorkflowActor.System ignored -> null;
                    case null -> null;
                };
        String reason = actor instanceof WorkflowActor.System system ? system.reason() : null;
        PrincipalRef subject = attribution == null ? null : attribution.onBehalfOf();
        JsonObject storedSubject = subject == null
                ? null
                : new JsonObject().put("type", subject.type().name()).put("id", subject.id());
        return tuple.addString(kind).addString(id).addString(reason).addValue(storedSubject);
    }

    /**
     * Reads the attribution of a row.
     *
     * @param row the row
     * @param prefix the prefix of the column names, empty for commands and history
     * @return the attribution; {@code null} for a row that records none; no actor for a row whose stored attribution
     *     cannot be read, which is logged
     */
    public static @Nullable WorkflowAttribution read(Row row, String prefix) {
        String kind = row.getString(prefix + "actor_kind");
        if (kind == null) {
            return null;
        }
        try {
            WorkflowActor actor =
                    switch (kind) {
                        case "USER" -> new WorkflowActor.User(row.getString(prefix + "actor_id"));
                        case "SERVICE" -> new WorkflowActor.Service(row.getString(prefix + "actor_id"));
                        case "SYSTEM" -> new WorkflowActor.System(row.getString(prefix + "actor_reason"));
                        case "NONE" -> null;
                        default -> throw new IllegalArgumentException("Unknown actor kind");
                    };
            JsonObject storedSubject = row.getJsonObject(prefix + "on_behalf_of");
            PrincipalRef subject = storedSubject == null
                    ? null
                    : new PrincipalRef(
                            PrincipalType.valueOf(storedSubject.getString("type")),
                            storedSubject.getString("id"),
                            Map.of());
            return new WorkflowAttribution(actor, subject);
        } catch (IllegalArgumentException | NullPointerException | ClassCastException invalid) {
            // One unreadable row must not fail the page it is on; the failure names no stored value.
            log.warn(
                    "A stored workflow attribution could not be read ({})",
                    invalid.getClass().getName());
            return WorkflowAttribution.absent();
        }
    }
}
