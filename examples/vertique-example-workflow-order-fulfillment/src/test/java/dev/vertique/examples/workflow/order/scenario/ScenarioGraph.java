// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.inboxoutbox.postgresql.PgInboxOutboxRepository;
import dev.vertique.workflow.client.WorkflowClientFactory;
import dev.vertique.workflow.definition.service.WorkflowDefinitionService;
import dev.vertique.workflow.engine.WorkflowDefinitionPropagationPoller;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.tasks.TaskService;
import java.util.Set;

/** The parts of a scenario node the tests use. */
interface ScenarioGraph extends VertiqueApplicationComponent {

    /** Returns the workflow operations facade. */
    WorkflowOperations workflowOperations();

    /** Returns the probe that waits until the engine has run the work recorded for an instance. */
    WorkflowExecutionProbe executionProbe();

    /** Returns the factory of typed clients. */
    WorkflowClientFactory workflowClientFactory();

    /** Returns the service that loads documents and drives publication and activation. */
    WorkflowDefinitionService definitionService();

    /** Returns the poller whose pass brings in the rows and records of the scopes that startup does not read. */
    WorkflowDefinitionPropagationPoller definitionPropagationPoller();

    /** Returns the registry of the node. */
    WorkflowRegistry workflowRegistry();

    /** Returns the service that completes human tasks. */
    TaskService taskService();

    /** Returns the verticle deployments of the node, among them the workflow executor. */
    Set<VerticleDeployment> verticleDeployments();

    /** Returns the repository whose pool the tests read and write rows through. */
    PgInboxOutboxRepository pgInboxOutboxRepository();
}
