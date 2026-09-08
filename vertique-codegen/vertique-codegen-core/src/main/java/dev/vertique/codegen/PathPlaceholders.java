// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * INTERNAL framework seam — processor-authoring substrate consumed by sibling framework modules;
 * not an application contract and outside the maturity promise. An application uses the wiring
 * annotations this module documents and never calls this type.
 *
 * <p>Single source of truth for JAX-RS path-template placeholder extraction. Used by both the
 * rest-client codegen validator and the jaxrs APT validator.
 *
 * <p>Handles regex-constrained placeholders ({@code {id:[0-9]+}}) and regex constraints that
 * contain {@code /} characters (e.g., {@code {id:[^/]+}}) by capturing only the placeholder name
 * before the optional {@code :regex} suffix. The grammar mirrors
 * {@code SimpleUriBuilder.TEMPLATE_NAME_PATTERN} from the runtime.
 */
public final class PathPlaceholders {

    /**
     * Matches a JAX-RS path template placeholder.
     *
     * <p>Grammar (simplified):
     * <pre>
     *   '{' \s* name \s* ( ':' regex-constraint )? '}'
     * </pre>
     * where {@code regex-constraint} may contain {@code /} and nested braces. Group 1 captures
     * the placeholder name without leading/trailing whitespace.
     *
     * <p>Ported from {@code SimpleUriBuilder.TEMPLATE_NAME_PATTERN} in the runtime.
     */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\s*([^:}]+?)\\s*(?::(?:[^{}]|\\{[^}]*\\})*)?\\}");

    private PathPlaceholders() {}

    /**
     * Extracts placeholder names from a JAX-RS path template.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code /users/{id:[0-9]+}/orders/{name}} &rarr; {@code [id, name]}</li>
     *   <li>{@code /users/{id:[^/]+}} &rarr; {@code [id]} (regex constraint contains {@code /})</li>
     *   <li>{@code /items/{a}{b}} &rarr; {@code [a, b]}</li>
     *   <li>{@code /{ id }/{ name : [a-z]+ }} &rarr; {@code [id, name]} (whitespace trimmed)</li>
     * </ul>
     *
     * <p>Leading and trailing whitespace inside the braces is handled by the pattern itself
     * ({@code \s*} around the name group). A defensive {@code .trim()} is retained for any
     * edge cases not covered by the regex.
     *
     * <p>Order-preserving; duplicates are de-duplicated via a {@link LinkedHashSet}.
     *
     * @param path the JAX-RS path template string; must not be {@code null}
     * @return an ordered, deduplicated set of placeholder names (without braces or regex suffixes)
     */
    public static Set<String> extract(String path) {
        Set<String> placeholders = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER_PATTERN.matcher(path);
        while (m.find()) {
            placeholders.add(m.group(1).trim());
        }
        return placeholders;
    }
}
