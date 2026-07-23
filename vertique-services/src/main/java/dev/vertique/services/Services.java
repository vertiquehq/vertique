// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Dagger qualifier for the multibinding set of service implementation objects.
 *
 * <p>Usage in a Dagger module:
 * <pre>{@code
 * @Provides @IntoSet @Services
 * static Object userService(UserServiceImpl impl) { return impl; }
 * }</pre>
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface Services {}
