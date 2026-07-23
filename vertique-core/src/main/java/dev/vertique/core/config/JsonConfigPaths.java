// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.config;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;

/**
 * Shared helpers for navigating hierarchical {@link JsonObject} configuration trees.
 *
 * <p>Two lookup modes are provided:
 * <ul>
 *   <li>{@link #navigateObject(JsonObject, String...)} for tolerant subtree traversal that
 *       returns an empty object when any segment is missing.</li>
 *   <li>{@link #resolve(JsonObject, String)} for strict dotted-path resolution that distinguishes
 *       missing paths from invalid shapes where traversal crosses a non-object node.</li>
 * </ul>
 */
public final class JsonConfigPaths {

    private JsonConfigPaths() {}

    /**
     * Result status for a strict dotted-path lookup.
     */
    public enum LookupStatus {
        PRESENT,
        MISSING,
        INVALID_SHAPE
    }

    /**
     * Outcome of a strict dotted-path lookup.
     *
     * @param status the lookup status
     * @param value the resolved value when {@link LookupStatus#PRESENT}; otherwise {@code null}
     * @param path the original dotted path
     * @param failingSegment the missing or invalid segment when applicable; otherwise {@code null}
     */
    public record LookupResult(LookupStatus status, Object value, String path, String failingSegment) {}

    /**
     * Navigates a nested {@link JsonObject} path. Returns an empty object when any segment key is
     * absent — this preserves the "section may be missing" contract that
     * {@link JsonObject#getJsonObject(String, JsonObject)} provides for the default-value case
     * (which returns {@code def} only when {@link java.util.Map#containsKey} is {@code false}).
     * Blank or {@code null} path segments are skipped to support optional hierarchy parts (e.g.
     * an empty service-type slot).
     *
     * <p>Throws {@link ConfigurationException} when a segment key is present but bound to any
     * value that is not a {@link JsonObject} — this includes explicit JSON {@code null} (the old
     * lookup chain would have NPE'd on the next dereference) as well as scalars or arrays. The
     * path of the traversed segments is included in the message so the operator sees exactly
     * where the malformed value lives. Callers that need to keep walking past such a shape (e.g.
     * to batch-collect violations) should catch the exception locally.
     *
     * @param root the root configuration object
     * @param segments ordered path segments
     * @return the nested object at the end of the path, or an empty object if any segment key is
     *     absent
     * @throws ConfigurationException when an intermediate or terminal segment key is present but
     *     its value is not a {@link JsonObject}
     */
    public static JsonObject navigateObject(JsonObject root, String... segments) {
        JsonObject current = root;
        StringBuilder traversed = null;
        for (String segment : segments) {
            if (current == null) {
                return new JsonObject();
            }
            if (segment == null || segment.isBlank()) {
                continue;
            }
            if (traversed == null) {
                traversed = new StringBuilder(segment);
            } else {
                traversed.append('.').append(segment);
            }
            if (!current.containsKey(segment)) {
                return new JsonObject();
            }
            Object next = current.getValue(segment);
            if (!(next instanceof JsonObject nested)) {
                throw new ConfigurationException("Config path '" + traversed
                        + "' must be a JSON object, got "
                        + (next == null ? "null" : next.getClass().getSimpleName()));
            }
            current = nested;
        }
        return current != null ? current : new JsonObject();
    }

    /**
     * Resolves a dotted path against a hierarchical {@link JsonObject}.
     *
     * <p>An intermediate segment that is absent ({@code !containsKey}) returns
     * {@link LookupStatus#MISSING}. An intermediate segment that is present but is not itself a
     * {@link JsonObject} — including an explicit-{@code null} value — returns
     * {@link LookupStatus#INVALID_SHAPE}, since traversal cannot continue into a non-object node.
     * A present leaf returns {@link LookupStatus#PRESENT} with whatever value is bound, including
     * an explicit {@code null}; callers decide whether objects, arrays, or {@code null} leaves are
     * acceptable.
     *
     * <p>The dotted path itself must be syntactically well-formed: a non-empty sequence of
     * non-empty segments separated by single dots. Empty input ({@code ""}), paths consisting
     * solely of separators ({@code "."}, {@code ".."}), or paths with leading, trailing, or
     * consecutive dots ({@code ".a"}, {@code "a."}, {@code "a..b"}) all throw
     * {@link IllegalArgumentException}. This catches caller-side typos like {@code "mode."} that
     * {@link String#split(String)} would otherwise silently normalize to {@code "mode"} by
     * dropping trailing empty tokens.
     *
     * @param root the root configuration object
     * @param dottedPath the dotted path to resolve
     * @return the lookup result
     * @throws IllegalArgumentException if {@code dottedPath} is {@code null}, empty, or contains
     *     an empty segment between dots
     */
    public static LookupResult resolve(JsonObject root, String dottedPath) {
        if (dottedPath == null || dottedPath.isEmpty()) {
            throw new IllegalArgumentException("Dotted path must be a non-empty string");
        }
        // Use limit=-1 so split preserves trailing empty tokens; otherwise "a." would silently
        // become ["a"] and a typoed path could read the wrong property.
        String[] parts = dottedPath.split("\\.", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("Dotted path '" + dottedPath + "' has an empty segment");
            }
        }

        if (root == null) {
            return new LookupResult(LookupStatus.MISSING, null, dottedPath, parts[0]);
        }

        JsonObject current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String segment = parts[i];
            if (!current.containsKey(segment)) {
                return new LookupResult(LookupStatus.MISSING, null, dottedPath, segment);
            }
            Object next = current.getValue(segment);
            if (!(next instanceof JsonObject nested)) {
                return new LookupResult(LookupStatus.INVALID_SHAPE, null, dottedPath, segment);
            }
            current = nested;
        }

        String leaf = parts[parts.length - 1];
        return current.containsKey(leaf)
                ? new LookupResult(LookupStatus.PRESENT, current.getValue(leaf), dottedPath, null)
                : new LookupResult(LookupStatus.MISSING, null, dottedPath, leaf);
    }
}
