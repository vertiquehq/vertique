// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a config field whose <em>whole</em> value is a secret. It is meant for an open bag whose keys
 * cannot be named in advance (a vendor {@code properties} map, a {@code placeholders} map) and so
 * cannot be recognised by {@link ConfigSecretRenderer#isSensitivePath(String) path}, but it works on
 * any field, record component, accessor or builder parameter of any type.
 *
 * <p>The config mapper behind {@code ConfigParser} writes a non-empty value of a marked property as
 * the string {@link ConfigSecretRenderer#MASK} ({@code "***"}) without descending into it, so a debug
 * dump of the parsed record never shows a key or a value. An empty map, collection, array or string,
 * and a {@code null} or empty {@link java.util.Optional}, are written as given, so "nothing configured"
 * stays visible. The marker takes precedence over the name-based rule for a non-empty value. The record
 * keeps the live value: only its serialized rendering changes.
 *
 * <p>The marker does not cover {@code toString()}: a config record that carries a marked property must
 * redact it there too, for example with {@link ConfigSecretRenderer#redactBag} or by rendering only the
 * keys.
 *
 * <p>Reading the value back is unaffected. A configuration that supplies the literal string
 * {@code "***"} for a marked bag is rejected like any other value of the wrong shape, without echoing it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface ConfigSecret {}
