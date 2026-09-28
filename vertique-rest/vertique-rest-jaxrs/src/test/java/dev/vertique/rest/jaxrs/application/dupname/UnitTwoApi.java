// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.dupname;

import dev.vertique.rest.jaxrs.application.RestApplication;

/**
 * TP-008's second simulated compilation unit: registers the name {@code api}, the same name
 * {@link UnitOneApi} registers from a first, independent registration module.
 */
@RestApplication(name = "api", path = "/api/two", resources = UnitTwoResource.class)
public interface UnitTwoApi {}
