// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.sanitization;

/**
 * Dagger multibinding wrapper that associates a {@link Sanitizer} implementation class with
 * its singleton instance.
 *
 * <p>The {@link #type()} acts as the lookup key when the runtime resolves sanitizers declared
 * in {@link Sanitize#value()}. Contribute instances via:
 * <pre>{@code
 * @Provides @IntoSet
 * static SanitizerBinding mySanitizer(MySanitizer s) {
 *     return new SanitizerBinding(MySanitizer.class, s);
 * }
 * }</pre>
 *
 * @param type     the concrete {@link Sanitizer} implementation class used as the lookup key
 * @param instance the singleton {@link Sanitizer} instance
 */
public record SanitizerBinding(Class<? extends Sanitizer> type, Sanitizer instance) {}
