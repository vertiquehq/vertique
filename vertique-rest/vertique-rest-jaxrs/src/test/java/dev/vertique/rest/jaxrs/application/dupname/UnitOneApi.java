// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.dupname;

import dev.vertique.rest.jaxrs.application.RestApplication;

/**
 * TP-008's first simulated compilation unit: registers the name {@code api}, the same name
 * {@link UnitTwoApi} registers from a second, independent registration module.
 */
@RestApplication(name = "api", path = "/api/one", resources = UnitOneResource.class)
public interface UnitOneApi {}
