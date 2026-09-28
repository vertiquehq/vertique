// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.view;

import dev.vertique.rest.jaxrs.application.RestApplication;

/**
 * TP-006's application {@code c}, mounted at the root path: its {@code jaxrs.applications.c} entry
 * carries no {@code openapiPath} and its own {@link #openapiPath()} is unset, so the global
 * {@code jaxrs.openapiPath} decides its effective location (GLOBAL).
 */
@RestApplication(name = "c", path = "/", resources = ViewResourceC.class)
public interface ViewAppC {}
