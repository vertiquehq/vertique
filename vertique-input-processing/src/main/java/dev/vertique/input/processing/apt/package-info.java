// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Compile-time-only annotation-processing adapters for this module's transport-neutral policy
 * resolution.
 *
 * <p>Every type in this package operates on {@code javax.lang.model} elements and therefore
 * requires the JDK {@code java.compiler} module. Nothing here is ever loaded by runtime code: the
 * package exists so annotation processors can resolve the same invocation-level policies the
 * reflective runtime resolves, from the same shared precedence rules, without duplicating the
 * algorithm.
 */
package dev.vertique.input.processing.apt;
