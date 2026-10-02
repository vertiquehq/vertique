// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * INTERNAL composition facts of the declared {@code @RestApplication}s.
 *
 * <p>{@link dev.vertique.rest.jaxrs.application.RestApplications} is the component-scoped {@code
 * @Singleton} view over every declared {@code @RestApplication}, built once per component and
 * provided by {@code RestModule}; {@link dev.vertique.rest.jaxrs.application.ApiDocsInstalled} is
 * the marker {@code RestModule} detects, through {@code @BindsOptionalOf}, to know whether the
 * OpenAPI documentation module is present in the component.
 *
 * <p>Every type here is public only so the composition code in {@code dev.vertique.rest.jaxrs}
 * (the {@code RestModule} wiring, the application composer, and the mount composition validator)
 * and sibling framework modules — the OpenAPI documentation module and the {@code openapi-contract}
 * validation module — can read this composition or bind the marker. It is outside the maturity
 * promise and is not an application contract: an application never depends on this package
 * directly.
 */
package dev.vertique.rest.jaxrs.application;
