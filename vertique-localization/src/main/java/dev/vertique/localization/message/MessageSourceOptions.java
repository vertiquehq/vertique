// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.message;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Singular;

/**
 * Immutable options record for configuring a {@link MessageSource} instance.
 *
 * <p>Construct via the fluent builder returned by {@link #builder()}:
 * <pre>{@code
 * MessageSourceOptions options = MessageSourceOptions.builder()
 *     .basename("customer-messages")
 *     .fallbackBasename("common-messages")
 *     .fallbackBasename("framework-messages")
 *     .classLoader(getClass().getClassLoader())
 *     .build();
 * }</pre>
 *
 * <h2>Validation invariants (FR-LOC-030..036)</h2>
 * <ul>
 *   <li>{@link #basename} must not be {@code null} or blank (IAE).</li>
 *   <li>{@link #fallbackBasenames} elements must not be {@code null} (IAE) or blank (IAE).</li>
 *   <li>The set of all basenames (primary + fallbacks) must be duplicate-free; order-insensitive
 *       (IAE with "duplicate basename: &lt;name&gt;").</li>
 * </ul>
 *
 * @param basename          the primary resource bundle base name (e.g. {@code "messages"});
 *                          never {@code null} or blank
 * @param fallbackBasenames ordered list of fallback base names tried when a code is absent from
 *                          the primary bundle; never {@code null}; elements must not be
 *                          {@code null} or blank; no duplicates with each other or with
 *                          {@link #basename}
 * @param classLoader       the {@link ClassLoader} used to locate the bundle resources; when
 *                          {@code null} the implementation applies its own default strategy
 * @param caller            optional class hint from the calling module, used as a tie-breaker
 *                          for class-loader selection; may be {@code null}
 */
@Builder(toBuilder = true)
public record MessageSourceOptions(
        String basename,
        @Singular("fallbackBasename") List<String> fallbackBasenames,
        ClassLoader classLoader,
        Class<?> caller) {

    /**
     * Compact constructor that validates and defensively copies the mutable inputs.
     */
    public MessageSourceOptions {
        if (basename == null || basename.isBlank()) {
            throw new IllegalArgumentException("basename must not be blank");
        }
        // Single pass over fallbackBasenames: null/blank/duplicate check + defensive immutable copy.
        if (fallbackBasenames == null) {
            fallbackBasenames = List.of();
        } else {
            Set<String> seen = new LinkedHashSet<>();
            seen.add(basename);
            for (String fb : fallbackBasenames) {
                if (fb == null) {
                    throw new IllegalArgumentException("fallback basename must not be null");
                }
                if (fb.isBlank()) {
                    throw new IllegalArgumentException("fallback basename must not be blank");
                }
                if (!seen.add(fb)) {
                    throw new IllegalArgumentException("duplicate basename: " + fb);
                }
            }
            fallbackBasenames = List.copyOf(fallbackBasenames);
        }
    }
}
