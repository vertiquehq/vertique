// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.dupname;

import dev.vertique.rest.core.application.RestApplication;

/**
 * TP-008's control: the second unit's declaration renamed to {@code api-two}, so it no longer
 * collides with {@link UnitOneApi}'s {@code api}.
 */
@RestApplication(name = "api-two", path = "/api/two", resources = UnitTwoResource.class)
public interface UnitTwoRenamedApi {}
