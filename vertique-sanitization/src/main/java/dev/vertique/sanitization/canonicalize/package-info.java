// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Built-in {@link dev.vertique.core.sanitization.Canonicalizer} implementations.
 *
 * <p>All canonicalizers in this package are stateless, deterministic, and idempotent. They can be
 * referenced by class in {@link dev.vertique.core.sanitization.Canonicalize#value()} annotations.
 * The {@link dev.vertique.sanitization.SanitizationModule} registers all of them as Dagger
 * multibindings so they are available for injection-based resolution.
 *
 * @see dev.vertique.core.sanitization.Canonicalize
 * @see dev.vertique.core.sanitization.Canonicalizer
 */
package dev.vertique.sanitization.canonicalize;
