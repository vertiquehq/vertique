// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.contract;

import dev.vertique.rest.core.application.RestApplication;

/**
 * TP-007's application {@code c}: its own annotation-declared contract location, {@code c.yaml},
 * overridden by the deployment configuration entry {@code jaxrs.applications.c.openapiPath}
 * ({@code c-config.yaml}), which takes precedence (CONFIGURATION over ANNOTATION).
 */
@RestApplication(name = "c", path = "/api/c", resources = ContractCResource.class, openapiPath = "c.yaml")
public interface ContractCApi {}
