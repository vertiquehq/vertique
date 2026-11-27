// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * HTTP-agnostic input context types shared by the string input processing modules.
 *
 * <p>{@link dev.vertique.core.sanitization.InputValueContext} carries the
 * {@link dev.vertique.core.sanitization.InputLocation}, property path, logical name, and owner type
 * to every canonicalizer and sanitizer.
 *
 * <p>Policies are declared on Java property names, but a transport's intermediate is keyed by
 * whatever its codec published. {@link dev.vertique.core.sanitization.InputFieldNameResolver} is the
 * codec-neutral projection that closes that gap; each codec-backed implementation lives in the
 * module that owns that codec, so this package stays free of any codec dependency.
 *
 * <p>The canonicalization and sanitization annotations, the {@code Canonicalizer} and
 * {@code Sanitizer} contracts, and the built-in implementations live in the {@code sanitization}
 * framework module, not in {@code core}.
 *
 * @see dev.vertique.core.sanitization.InputValueContext
 * @see dev.vertique.core.sanitization.InputLocation
 * @see dev.vertique.core.sanitization.InputFieldNameResolver
 */
package dev.vertique.core.sanitization;
