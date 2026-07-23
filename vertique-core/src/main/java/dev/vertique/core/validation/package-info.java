// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Core validation API — interfaces, records, and annotations shared across the framework.
 *
 * <p>This package contains the portable validation contracts that can be used by any module
 * without depending on the {@code validation} module or any Jakarta Validation runtime:
 * <ul>
 *   <li>{@link dev.vertique.core.validation.BeanValidator} — programmatic validation SPI</li>
 *   <li>{@link dev.vertique.core.validation.ViolationDetail} — HTTP-agnostic violation record</li>
 *   <li>{@link dev.vertique.core.validation.BeanValidationException} — exception carrying violations</li>
 *   <li>{@link dev.vertique.core.validation.ParameterViolation} — violation scoped to a method parameter</li>
 *   <li>{@link dev.vertique.core.validation.ValidateWith} — annotation for specifying validation groups</li>
 *   <li>{@link dev.vertique.core.validation.CharacterPolicy} — character-set validation policy SPI</li>
 *   <li>{@link dev.vertique.core.validation.CharacterPolicyResult} — result of character policy validation</li>
 *   <li>{@link dev.vertique.core.validation.CharacterPolicyBinding} — Dagger multibinding wrapper for character policies</li>
 *   <li>{@link dev.vertique.core.validation.SkipAllowedCharacters} — opt-out annotation for object-level character validation</li>
 * </ul>
 *
 * <p>Implementations are provided by the {@code validation} module (Hibernate Validator backed).
 * The REST layer (in {@code rest-jaxrs}) uses {@link dev.vertique.core.validation.BeanValidator}
 * via an optional Dagger binding — validation is skipped when no implementation is present.
 *
 * @see dev.vertique.core.validation.BeanValidator
 * @see dev.vertique.core.validation.ValidationModule
 */
package dev.vertique.core.validation;
