// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Framework-native, symmetric parameter-conversion stack shared by the JAX-RS inbound path and the
 * REST-client outbound path.
 *
 * <p>{@link dev.vertique.rest.core.convert.ParamConverter} is the per-type conversion SPI
 * ({@code String} ⇄ {@code T}). Application converters are contributed as
 * {@link dev.vertique.rest.core.convert.ParamConverterBinding} entries and assembled — together with
 * the framework built-ins — into a {@link dev.vertique.rest.core.convert.ParamConverterRegistry}, the
 * native, type-keyed lookup table. The registry resolves a target type by exact class, then by the
 * {@code Class.isEnum()} synthesis rule, then reports a miss.
 *
 * <p>{@link dev.vertique.rest.core.convert.ParamSource} enumerates the string-ish transport kinds
 * conversion applies to (path, query, header, cookie, form). A
 * {@link dev.vertique.rest.core.convert.ConversionContext} carries the per-parameter context (name,
 * source, raw/generic/component types, and a lazy annotation supplier) needed by the full conversion
 * chain and by the failure model.
 *
 * <p>Conversion failures surface as
 * {@link dev.vertique.rest.core.convert.ParamConversionException} (a parse failure, mapped to HTTP
 * 400) carrying the parameter name, source, and target type but never the raw value. A request for a
 * declared type that no converter or provider can satisfy surfaces as
 * {@link dev.vertique.rest.core.convert.ParamConverterNotFoundException} (mapped to HTTP 500 when it
 * escapes startup validation).
 */
package dev.vertique.rest.core.convert;
