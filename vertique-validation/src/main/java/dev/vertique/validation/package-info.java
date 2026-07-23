// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Jakarta Bean Validation integration with Dagger dependency injection.
 *
 * <p>This module bootstraps a {@link jakarta.validation.Validator} backed by Hibernate Validator,
 * with a custom {@link jakarta.validation.ConstraintValidatorFactory} that resolves validators
 * from Dagger multibindings. Three resolution tiers are supported:
 * <ol>
 *   <li>Dagger-managed individual validators (via {@code Set<ConstraintValidator<?,?>>})</li>
 *   <li>Contributed factories (via {@code Set<ConstraintValidatorFactory>})</li>
 *   <li>Reflection fallback for standard validators</li>
 * </ol>
 *
 * <p>Include {@link dev.vertique.validation.ValidationModule} in your Dagger component
 * to enable validation.
 *
 * @see dev.vertique.core.validation.BeanValidator
 * @see dev.vertique.validation.ValidationModule
 */
package dev.vertique.validation;
