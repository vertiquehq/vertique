// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The compared facts of one published Parameter Object: {@code name}, {@code in}, the {@code
 * required} member as published, and {@code description}.
 *
 * <p>{@code required} keeps the member's raw value: {@code null} when the member is absent, so a
 * published {@code "required": false} differs from an absent member.
 *
 * @param name        the {@code name} member
 * @param in          the {@code in} member
 * @param required    the {@code required} member, or {@code null} when absent
 * @param description the {@code description} member, or {@code null} when absent
 */
public record PublishedParameter(
        String name,
        String in,
        @Nullable Boolean required,
        @Nullable String description) {

    /**
     * Reads one Parameter Object.
     *
     * @param parameter the published Parameter Object
     * @return its compared facts
     * @throws IllegalArgumentException if a member has an unexpected JSON type
     */
    public static PublishedParameter from(JsonObject parameter) {
        return new PublishedParameter(
                string(parameter, "name"),
                string(parameter, "in"),
                bool(parameter, "required"),
                string(parameter, "description"));
    }

    /**
     * Reads a published {@code parameters} array, in published order.
     *
     * @param parameters the operation's {@code parameters} member, or {@code null} when absent
     * @return the compared facts of each Parameter Object, empty when the member is absent
     */
    public static List<PublishedParameter> fromAll(@Nullable JsonArray parameters) {
        List<PublishedParameter> published = new ArrayList<>();
        if (parameters != null) {
            for (int index = 0; index < parameters.size(); index++) {
                published.add(from(parameters.getJsonObject(index)));
            }
        }
        return published;
    }

    private static @Nullable String string(JsonObject object, String member) {
        Object value = object.getValue(member);
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("member '" + member + "' is not a string: " + value);
        }
        return (String) value;
    }

    private static @Nullable Boolean bool(JsonObject object, String member) {
        Object value = object.getValue(member);
        if (value != null && !(value instanceof Boolean)) {
            throw new IllegalArgumentException("member '" + member + "' is not a boolean: " + value);
        }
        return (Boolean) value;
    }
}
