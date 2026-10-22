// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.inboxoutbox.postgresql.PgInboxOutboxRepository;
import dev.vertique.workflow.client.WorkflowClientFactory;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.tasks.TaskService;

/** The parts of a scenario node the tests use. */
interface ScenarioGraph extends VertiqueApplicationComponent {

    /** Returns the workflow operations facade. */
    WorkflowOperations workflowOperations();

    /** Returns the factory of typed clients. */
    WorkflowClientFactory workflowClientFactory();

    /** Returns the service that loads documents and drives publication and activation. */
    WorkflowDefinitionService definitionService();

    /** Returns the registry of the node. */
    WorkflowRegistry workflowRegistry();

    /** Returns the service that completes human tasks. */
    TaskService taskService();

    /** Returns the repository whose pool the tests read and write rows through. */
    PgInboxOutboxRepository pgInboxOutboxRepository();
}
