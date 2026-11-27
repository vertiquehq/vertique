// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * HTTP-agnostic input context types shared by the input processing modules.
 *
 * <p>{@link dev.vertique.core.input.InputValueContext} carries the
 * {@link dev.vertique.core.input.InputLocation}, property path, logical name, and owner type to
 * every input processor.
 *
 * <p>Policies are declared on Java property names, but a transport's intermediate is keyed by
 * whatever its codec published. {@link dev.vertique.core.input.InputFieldNameResolver} is the
 * codec-neutral projection that closes that gap; each codec-backed implementation lives in the
 * module that owns that codec, so this package stays free of any codec dependency.
 *
 * <p>The canonicalization and sanitization annotations, the {@code Canonicalizer} and
 * {@code Sanitizer} contracts, and the built-in implementations live in the {@code sanitization}
 * framework module, not in {@code core}.
 *
 * @see dev.vertique.core.input.InputValueContext
 * @see dev.vertique.core.input.InputLocation
 * @see dev.vertique.core.input.InputFieldNameResolver
 */
package dev.vertique.core.input;
