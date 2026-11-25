// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dev.vertique.rest.core.application.RestApplication;

/**
 * Discovery-mode application fixture, registered by
 * {@link DiscoveryRegistrationModule#discoveryApplicationRegistration}. A sole active {@code
 * discover = true} registration selects every enabled catalog entry and every manual contribution;
 * beside any other registration, active or not, it fails at view build.
 */
@RestApplication(name = "api", path = "/api", discover = true)
public interface DiscoveryApi {}
