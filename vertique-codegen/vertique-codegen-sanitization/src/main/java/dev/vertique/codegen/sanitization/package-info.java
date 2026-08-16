// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * CG-008 — Annotation processor that generates {@code {DTO}_InputProcessor} walkers for REST
 * request body DTOs annotated with {@code @Sanitize}/{@code @Canonicalize}, replacing reflective
 * Map traversal in {@code DefaultInputObjectProcessor} with direct field-name {@code switch}
 * statements and pre-resolved chain class constants.
 *
 * <p>Discovery is anchored on JAX-RS resource methods (HTTP-verb annotations on methods of
 * {@code @Path}-annotated classes). Body parameter types are walked transitively for
 * sanitization-participating subtrees; the emitted set covers direct body roots unconditionally
 * (so route- and parameter-level policies still flow through the generated path) and nested
 * types only when their subtree carries sanitization annotations.
 *
 * <p>Generated processors implement
 * {@link dev.vertique.input.processing.GeneratedInputProcessor} and live in the same package
 * as the source DTO. The runtime
 * {@link dev.vertique.input.processing.GeneratedInputProcessorDispatcher} resolves them via
 * {@link Class#forName} on the consuming type's classloader, with
 * {@link ClassNotFoundException} as the only cached miss; broken generated classes propagate.
 *
 * <p>This module emits no Dagger module: the registry self-populates classloader-driven, so
 * CG-008 is a transparent optimization rather than a new registration path.
 */
package dev.vertique.codegen.sanitization;
