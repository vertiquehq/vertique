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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

/** INTERNAL: maps the attribution columns shared by workflow commands, history and definitions. */
public final class PgWorkflowAttribution {

    private static final String ESCAPED_TEXT = "~utf16:";

    private PgWorkflowAttribution() {}

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
        JsonObject encodedSubject = subject == null
                ? null
                : new JsonObject().put("type", subject.type().name()).put("id", encodeText(subject.id()));
        return tuple.addString(kind)
                .addString(encodeText(id))
                .addString(encodeText(reason))
                .addValue(encodedSubject)
                .addString(attribution == null ? null : attribution.status().name());
    }

    // PostgreSQL text and JSONB cannot represent NUL or unpaired UTF-16 surrogates.
    // Escape those values losslessly, including any literal value beginning with the escape prefix.
    private static @Nullable String encodeText(@Nullable String value) {
        if (value == null
                || (!value.startsWith(ESCAPED_TEXT)
                        && value.indexOf(0) < 0
                        && StandardCharsets.UTF_8.newEncoder().canEncode(value))) {
            return value;
        }
        ByteBuffer utf16 = ByteBuffer.allocate(Math.multiplyExact(value.length(), Character.BYTES));
        utf16.asCharBuffer().put(value);
        return ESCAPED_TEXT + HexFormat.of().formatHex(utf16.array());
    }

    private static @Nullable String decodeText(@Nullable String stored) {
        if (stored == null || !stored.startsWith(ESCAPED_TEXT)) {
            return stored;
        }
        byte[] bytes = HexFormat.of().parseHex(stored, ESCAPED_TEXT.length(), stored.length());
        if (bytes.length % Character.BYTES != 0) {
            throw new IllegalArgumentException("Invalid encoded attribution text");
        }
        CharBuffer utf16 = ByteBuffer.wrap(bytes).asCharBuffer();
        return utf16.toString();
    }

    public static @Nullable WorkflowAttribution read(Row row, String prefix, String statusColumn) {
        String kind = row.getString(prefix + "actor_kind");
        String status = row.getString(statusColumn);
        if (kind == null && status == null) {
            return null;
        }
        try {
            WorkflowActor actor =
                    switch (kind) {
                        case "USER" -> new WorkflowActor.User(decodeText(row.getString(prefix + "actor_id")));
                        case "SERVICE" -> new WorkflowActor.Service(decodeText(row.getString(prefix + "actor_id")));
                        case "SYSTEM" -> new WorkflowActor.System(decodeText(row.getString(prefix + "actor_reason")));
                        case "NONE" -> null;
                        case null, default -> throw new IllegalArgumentException("Unknown actor kind");
                    };
            JsonObject encodedSubject = row.getJsonObject(prefix + "on_behalf_of");
            PrincipalRef subject = encodedSubject == null
                    ? null
                    : new PrincipalRef(
                            PrincipalType.valueOf(encodedSubject.getString("type")),
                            decodeText(encodedSubject.getString("id")),
                            Map.of());
            return new WorkflowAttribution(actor, subject, WorkflowAttribution.Status.valueOf(status));
        } catch (IllegalArgumentException | NullPointerException | ClassCastException invalid) {
            return WorkflowAttribution.unavailable();
        }
    }
}
