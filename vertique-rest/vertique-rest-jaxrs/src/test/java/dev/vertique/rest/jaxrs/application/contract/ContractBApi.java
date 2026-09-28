// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.contract;

import dev.vertique.rest.jaxrs.application.RestApplication;

/**
 * TP-007's application {@code b}: no annotation-declared and no configured contract location, so
 * its effective location is the global {@code jaxrs.openapiPath}.
 */
@RestApplication(name = "b", path = "/api/b", resources = ContractBResource.class)
public interface ContractBApi {}
