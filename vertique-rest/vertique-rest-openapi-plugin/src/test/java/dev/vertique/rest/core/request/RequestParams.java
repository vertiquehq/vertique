// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-only stand-in for the {@code RequestParams} marker in the {@code vertique-rest-core} module,
 * which is deliberately not a dependency of this build-time-only module.
 * {@link dev.vertique.openapi.RequestParamsExtension} matches the marker by fully-qualified class
 * name, so this stub sits at that exact name.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestParams {}
