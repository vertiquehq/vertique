// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Shared validators reused across the codegen series during annotation processing.
 *
 * <p>Contains {@link dev.vertique.codegen.validate.InjectConstructorValidator}, the binding-origin
 * check enforcing that a generated Dagger binding targets a type with exactly one {@code @Inject}
 * constructor.
 */
package dev.vertique.codegen.validate;
