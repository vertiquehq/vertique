// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dagger.Component;
import jakarta.inject.Singleton;

/**
 * The scenario node that lists only the type names its wiring gives it. A node that has to name a moved class by the
 * logical name the shipped class also carries cannot include the generated catalog, because both would claim the name.
 */
@Singleton
@Component(modules = ScenarioInfrastructure.class)
interface HandCatalogComponent extends ScenarioGraph {}
