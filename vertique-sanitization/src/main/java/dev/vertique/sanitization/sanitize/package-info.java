// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Built-in {@link dev.vertique.sanitization.Sanitizer} implementations.
 *
 * <p>All sanitizers in this package are stateless, deterministic, and idempotent. They can be
 * referenced by class in {@link dev.vertique.sanitization.Sanitize#value()} annotations.
 * The {@link dev.vertique.sanitization.SanitizationModule} registers them as Dagger
 * multibindings so they are available for injection-based resolution.
 *
 * @see dev.vertique.sanitization.Sanitize
 * @see dev.vertique.sanitization.Sanitizer
 */
package dev.vertique.sanitization.sanitize;
