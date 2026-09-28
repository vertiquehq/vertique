// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.view;

import dev.vertique.rest.jaxrs.application.RestApplication;

/**
 * TP-006's application {@code d}, inactive: its {@code jaxrs.applications.d} entry sets
 * {@code openapiPath} to an explicit JSON {@code null}, which binds absent, so its own
 * annotation-declared {@code openapiPath} {@code "d.yaml"} is the effective location (ANNOTATION).
 */
@RestApplication(name = "d", path = "/api/d", resources = ViewResourceD.class, openapiPath = "d.yaml")
public interface ViewAppD {}
