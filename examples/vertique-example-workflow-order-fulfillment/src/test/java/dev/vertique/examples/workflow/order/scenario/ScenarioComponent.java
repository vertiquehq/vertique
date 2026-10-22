// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dagger.Component;
import dev.vertique.examples.workflow.order.GeneratedWorkflowTypeCatalogModule;
import jakarta.inject.Singleton;

/**
 * The scenario node that includes the type catalog the annotation processor generated for the example, so the shipped
 * contracts and the shipped order state are listed under their logical names.
 */
@Singleton
@Component(modules = {ScenarioInfrastructure.class, GeneratedWorkflowTypeCatalogModule.class})
interface ScenarioComponent extends ScenarioGraph {}
