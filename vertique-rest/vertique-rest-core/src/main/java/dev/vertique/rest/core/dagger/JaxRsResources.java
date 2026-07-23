// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.dagger;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;

/**
 * Dagger qualifier for the Set of JAX-RS resource instances.
 */
@Qualifier
@Retention(RUNTIME)
public @interface JaxRsResources {}
