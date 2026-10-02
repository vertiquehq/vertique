// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dev.vertique.rest.core.application.RestApplication;

/**
 * TP-002's discovery-mode application fixture, registered by
 * {@link DiscoveryRegistrationModule#discoveryApplicationRegistration}. A sole active {@code
 * discover = true} registration selects every enabled catalog entry and every manual contribution
 * (AC-024.5); beside any other registration, active or not, it fails at view build (FR-024,
 * AC-024.3).
 */
@RestApplication(name = "api", path = "/api", discover = true)
public interface DiscoveryApi {}
