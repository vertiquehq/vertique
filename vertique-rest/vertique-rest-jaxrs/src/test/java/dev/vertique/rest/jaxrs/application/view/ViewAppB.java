// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.view;

import dev.vertique.rest.jaxrs.application.RestApplication;

/**
 * TP-006's application {@code b}: no {@code jaxrs.applications.b} entry, so its own
 * annotation-declared {@code openapiPath} {@code "b.yaml"} is the effective location (ANNOTATION).
 */
@RestApplication(name = "b", path = "/api/b", resources = ViewResourceB.class, openapiPath = "b.yaml")
public interface ViewAppB {}
