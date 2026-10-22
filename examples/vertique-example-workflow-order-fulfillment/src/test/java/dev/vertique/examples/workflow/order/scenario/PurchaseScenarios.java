// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import static dev.vertique.examples.workflow.order.scenario.ScenarioNode.await;

import dev.vertique.examples.workflow.order.scenario.ScenarioNode.Pin;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** The purchase-approval workflow of the default scope and Acme's YAML variant, shared by the scenario tests. */
final class PurchaseScenarios {

    static final String DEFAULT = WorkflowScopes.DEFAULT;
    static final String ACME = "acme";
    static final String GLOBEX = "globex";
    static final String PURCHASE_APPROVAL = "purchase-approval";

    private static final Instant LOADED_AT = Instant.parse("2026-10-21T12:00:00Z");

    private PurchaseScenarios() {}

    /** Acme's variant of the purchase approval, kept as a resource. */
    static byte[] acmeDocument() {
        try (InputStream input =
                PurchaseScenarios.class.getResourceAsStream("/scenario/purchase-approval-acme.workflow.yaml")) {
            if (input == null) throw new IllegalStateException("The Acme workflow document is not on the classpath");
            return input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("The Acme workflow document could not be read", e);
        }
    }

    /** Acme's document with a top-level {@code scope} line, which a document may not carry. */
    static byte[] acmeDocumentWithScope(String scope) {
        return (new String(acmeDocument(), StandardCharsets.UTF_8) + "scope: " + scope + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Where a document came from, for the row that records it. */
    static SourceMetadata source(String label) {
        return new SourceMetadata("scenario", "memory:" + label, "revision-" + label, "scenario-test", null, LOADED_AT);
    }

    /** Loads Acme's document into the scope, publishes it and makes the scope run it. */
    static void loadPublishAndActivateAcme(ScenarioNode node) {
        await(node.graph().definitionService().load(ACME, acmeDocument(), DocumentFormat.YAML, source("acme")));
        await(node.graph().definitionService().publish(new DefinitionKey(ACME, PURCHASE_APPROVAL, 1)));
        await(node.graph().definitionService().activate(ACME, PURCHASE_APPROVAL, ACME, 1));
    }

    /** Starts a purchase in the scope with the business key; the client is made from the contract and the id. */
    static WorkflowInstanceId startPurchase(ScenarioNode node, String scope, String purchaseId, String poNumber) {
        return await(purchaseClient(node, scope).start(new StartPurchase(purchaseId, poNumber)));
    }

    static PurchaseApprovalWorkflow purchaseClient(ScenarioNode node, String scope) {
        return node.graph()
                .workflowClientFactory()
                .client(PurchaseApprovalWorkflow.class, PURCHASE_APPROVAL)
                .scope(scope)
                .create();
    }

    /** Completes the finance task of an instance with the approve decision. */
    static void approveFinance(ScenarioNode node, WorkflowInstanceId instance) {
        TaskCompletionCommand approve = new TaskCompletionCommand(
                node.openTask(instance),
                "approve",
                new FinanceDecision("carol"),
                "approve-" + instance.value(),
                new WorkflowActor.User("carol"),
                null);
        await(node.graph().taskService().complete(approve));
    }

    static Pin pin(String startScope, String definitionScope) {
        return new Pin(startScope, definitionScope, PURCHASE_APPROVAL, 1);
    }
}
