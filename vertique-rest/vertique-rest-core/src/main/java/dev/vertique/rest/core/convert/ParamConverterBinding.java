// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.convert;

/**
 * Keyed contribution wiring a {@link ParamConverter} to the exact target type it handles. Application
 * code contributes bindings (typically via Dagger {@code @IntoSet}) that the
 * {@link ParamConverterRegistry} assembles; an application binding for a given type overrides the
 * framework built-in for that same type.
 *
 * @param <T>        the target type the converter handles
 * @param targetType the exact class the converter is keyed on; never {@code null}
 * @param converter  the converter producing and serializing {@code T}; never {@code null}
 */
public record ParamConverterBinding<T>(Class<T> targetType, ParamConverter<T> converter) {}
