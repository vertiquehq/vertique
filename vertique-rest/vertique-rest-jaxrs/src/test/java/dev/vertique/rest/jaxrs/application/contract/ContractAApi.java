// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.contract;

import dev.vertique.rest.jaxrs.application.RestApplication;

/** TP-007's application {@code a}: its own annotation-declared contract location, {@code a.yaml}. */
@RestApplication(name = "a", path = "/api/a", resources = ContractAResource.class, openapiPath = "a.yaml")
public interface ContractAApi {}
