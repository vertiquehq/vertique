// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.sanitization;

/**
 * Dagger multibinding wrapper that associates a {@link Canonicalizer} implementation class with
 * its singleton instance.
 *
 * <p>The {@link #type()} acts as the lookup key when the runtime resolves canonicalizers declared
 * in {@link Canonicalize#value()}. Contribute instances via:
 * <pre>{@code
 * @Provides @IntoSet
 * static CanonicalizerBinding myCanonicalizer(MyCanonicalizer c) {
 *     return new CanonicalizerBinding(MyCanonicalizer.class, c);
 * }
 * }</pre>
 *
 * @param type     the concrete {@link Canonicalizer} implementation class used as the lookup key
 * @param instance the singleton {@link Canonicalizer} instance
 */
public record CanonicalizerBinding(Class<? extends Canonicalizer> type, Canonicalizer instance) {}
