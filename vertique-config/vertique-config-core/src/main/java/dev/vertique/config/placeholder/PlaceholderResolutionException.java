// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.placeholder;

import dev.vertique.core.exception.ConfigurationException;
import java.util.List;

/**
 * Thrown by {@link PlaceholderResolver} when one or more placeholder references could not be
 * resolved after a full tree walk.
 *
 * <h2>Unresolved references</h2>
 * <p>The {@link #unresolvedReferences()} list contains one entry per distinct failure. Each entry
 * is a <em>reference rendering</em> only — never a resolved value:
 * <ul>
 *   <li>A plain key ({@code "db.password"}) when the placeholder was a top-level reference.</li>
 *   <li>A chain rendering ({@code "a -> b -> a"}) when a cyclic or depth-exceeded path led to the
 *       failure.</li>
 * </ul>
 *
 * <p>The list is immutable, lexicographically sorted, and de-duplicated across all failures
 * collected during a single walk. It is never {@code null} and never empty (an exception with no
 * failures is a programming error).
 *
 * <h2>NFR-CONF-002</h2>
 * <p>No resolved config <em>value</em> appears in the exception message or in
 * {@code unresolvedReferences()}. Only keys and chain renderings are exposed.
 *
 * @see PlaceholderResolver
 */
public class PlaceholderResolutionException extends ConfigurationException {

    /** Immutable, sorted, de-duplicated list of unresolved reference renderings (keys only). */
    private final List<String> unresolvedReferences;

    /**
     * Constructs an exception with the given message and unresolved reference list.
     *
     * <p>The {@code unresolvedReferences} list must be non-null, non-empty, lexicographically
     * sorted, and de-duplicated. Entries are reference renderings only (keys or chain strings) —
     * never resolved values.
     *
     * @param message              diagnostic message; must not contain resolved values
     * @param unresolvedReferences immutable, sorted, de-duplicated list of unresolved reference
     *                             renderings
     */
    PlaceholderResolutionException(String message, List<String> unresolvedReferences) {
        super(message);
        this.unresolvedReferences = unresolvedReferences;
    }

    /**
     * Returns the immutable, lexicographically sorted, de-duplicated list of unresolved reference
     * renderings collected during the resolution walk.
     *
     * <p>Each entry is a key name ({@code "some.key"}) or a chain rendering
     * ({@code "a -> b -> a"}) that describes the path that could not be resolved. Entries are
     * reference renderings only — never config values (NFR-CONF-002).
     *
     * @return non-null, non-empty list of unresolved reference strings
     */
    public List<String> unresolvedReferences() {
        return unresolvedReferences;
    }
}
