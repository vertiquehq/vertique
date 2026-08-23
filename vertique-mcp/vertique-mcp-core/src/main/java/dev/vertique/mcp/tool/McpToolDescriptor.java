// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.tool;

import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The immutable published description of one tool.
 *
 * <p>A descriptor is constructed once during application composition from generated code and never
 * on the request path. Its schemas are canonical UTF-8 JSON strings produced by the shared schema
 * generator; the server parses and compiles them once per owning Vert.x context at startup.
 *
 * @param name the unique protocol name, 1–128 characters matching {@code [A-Za-z0-9_.-]+}
 * @param title the display title, or {@code null} when the tool declares none
 * @param description the non-blank description of at most {@value #MAX_DESCRIPTION_CHARS}
 *     characters
 * @param annotations the advertised behavior hints
 * @param inputSchema the canonical JSON Schema document describing the tool's argument object
 * @param outputSchema the canonical JSON Schema document describing the structured result, or
 *     {@code null} when the tool publishes no structured output
 * @param access the authorization requirement resolved for the tool
 */
public record McpToolDescriptor(
        String name,
        @Nullable String title,
        String description,
        McpToolAnnotations annotations,
        String inputSchema,
        @Nullable String outputSchema,
        McpToolAccess access) {

    /** The inclusive upper bound on a tool description, in characters. */
    public static final int MAX_DESCRIPTION_CHARS = 4096;

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    /**
     * Validates the published bounds and invariants of the descriptor.
     *
     * @throws NullPointerException if a required component is null
     * @throws IllegalArgumentException if the name does not match {@code [A-Za-z0-9_.-]{1,128}}, if
     *     the description is blank or longer than {@value #MAX_DESCRIPTION_CHARS} characters, or if
     *     a present title, the input schema, or a present output schema is blank
     */
    public McpToolDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(annotations, "annotations");
        Objects.requireNonNull(inputSchema, "inputSchema");
        Objects.requireNonNull(access, "access");
        if (!isValidName(name)) {
            throw new IllegalArgumentException("name must match [A-Za-z0-9_.-]{1,128}");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException("description must not exceed " + MAX_DESCRIPTION_CHARS + " characters");
        }
        if (title != null && title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank when present");
        }
        if (inputSchema.isBlank()) {
            throw new IllegalArgumentException("inputSchema must not be blank");
        }
        if (outputSchema != null && outputSchema.isBlank()) {
            throw new IllegalArgumentException("outputSchema must not be blank when present");
        }
    }

    /**
     * Reports whether {@code name} matches the published tool-name grammar, {@code
     * [A-Za-z0-9_.-]{1,128}}.
     *
     * <p>Exposed so a caller outside this record — e.g. {@code McpRequestTerminalEvent}'s compact
     * constructor, which bounds any resolved-tool-identity telemetry it records against this same
     * grammar — can validate a candidate name against the identical rule this constructor enforces,
     * without duplicating the pattern.
     *
     * @param name the candidate name to check; a {@code null} name is never valid
     * @return {@code true} when {@code name} is non-null and matches the grammar
     */
    public static boolean isValidName(@Nullable String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }
}
