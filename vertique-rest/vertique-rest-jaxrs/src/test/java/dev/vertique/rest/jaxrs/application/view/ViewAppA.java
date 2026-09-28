// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.view;

import dev.vertique.rest.jaxrs.application.RestApplication;

/**
 * TP-006's application {@code a}: its own annotation-declared {@code openapiPath} {@code "a.yaml"}
 * is overridden by the configured {@code jaxrs.applications.a.openapiPath} in the test's row
 * (CONFIGURATION beats ANNOTATION).
 */
@RestApplication(name = "a", path = "/api/a", resources = ViewResourceA.class, openapiPath = "a.yaml")
public interface ViewAppA {}
