// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.policy;

import dev.vertique.rest.core.application.RestApplication;

/**
 * The single REST application of the typed access-policy proof application. It discovers the
 * resources of the test compilation unit only; the shipped example's resources are not part of it.
 */
@RestApplication(name = "policy", path = "/", discover = true)
public interface PolicyTestApi {}
