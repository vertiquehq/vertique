// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import jakarta.inject.Qualifier;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Dagger qualifier marking the <em>effective</em> {@link JwtAuthConfig} resolved by
 * {@link JwtAuthModule}.
 *
 * <p>The effective config is the app-bound {@link JwtAuthConfig} when one is provided, otherwise the
 * config-default parsed from the {@code "jwt"} configuration section. The qualifier disambiguates
 * this resolved binding from the optional, app-supplied {@link JwtAuthConfig} override that
 * {@link JwtAuthModule} consumes — without it the two same-typed bindings would collide.
 *
 * @see JwtAuthModule
 * @see JwtAuthConfig
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
public @interface JwtEffective {}
