// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.ACME;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.DEFAULT;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.GLOBEX;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.PURCHASE_APPROVAL;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.acmeDocument;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.acmeDocumentWithScope;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.pin;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.source;
import static dev.vertique.examples.workflow.order.scenario.PurchaseScenarios.startPurchase;
import static dev.vertique.examples.workflow.order.scenario.ScenarioNode.await;
import static dev.vertique.examples.workflow.order.scenario.ScenarioNode.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParseException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Acme runs its own YAML variant of a purchase approval that the default scope runs from code. The variant is loaded as
 * a candidate, published and activated for Acme only; another scope keeps following the default, a restart keeps both,
 * and following the default again returns Acme to the code version.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class TenantVariantScenarioIT {

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("scenario_tenant_variant_it");

    @BeforeAll
    static void migrate() {
        ScenarioNode.migrate(db);
    }

    @Test
    @DisplayName("an Acme YAML variant runs in Acme while other scopes follow the default")
    void acmeYamlVariantRunsInAcmeWhileOtherScopesFollowDefault(Vertx vertx) {
        try (ScenarioNode first = deploy(vertx)) {
            // Given the default scope's code version and Acme's YAML variant loaded as a candidate
            await(first.graph()
                    .definitionService()
                    .load(ACME, acmeDocument(), DocumentFormat.YAML, source("acme-candidate")));
            assertEquals("CANDIDATE", first.definitionStatus(ACME, PURCHASE_APPROVAL, 1));

            // When it is published and activated for Acme
            await(first.graph().definitionService().publish(new DefinitionKey(ACME, PURCHASE_APPROVAL, 1)));
            assertEquals("AVAILABLE", first.definitionStatus(ACME, PURCHASE_APPROVAL, 1));
            await(first.graph().definitionService().activate(ACME, PURCHASE_APPROVAL, ACME, 1));

            // And a purchase starts in Acme and one in globex
            WorkflowInstanceId acme = startPurchase(first, ACME, "p-acme", "PO-1");
            WorkflowInstanceId globex = startPurchase(first, GLOBEX, "p-globex", "PO-1");

            // Then Acme's instance is pinned to Acme's variant and waits at the finance task
            assertEquals(pin(ACME, ACME), first.pin(acme));
            assertEquals("TASK", first.waitType(acme));
            assertEquals("finance-approval", first.step(acme));

            // And globex's instance is pinned to the default scope's version
            assertEquals(pin(GLOBEX, DEFAULT), first.pin(globex));
        }

        try (ScenarioNode second = deploy(vertx)) {
            // When the component restarts, reconciles once and purchases start again in both scopes
            second.reconcile();
            WorkflowInstanceId acme = startPurchase(second, ACME, "p-acme-2", "PO-2");
            WorkflowInstanceId globex = startPurchase(second, GLOBEX, "p-globex-2", "PO-2");

            // Then the same pins hold
            assertEquals(pin(ACME, ACME), second.pin(acme));
            assertEquals(pin(GLOBEX, DEFAULT), second.pin(globex));

            // When Acme follows the default scope again and a purchase starts there
            await(second.graph().definitionService().follow(ACME, PURCHASE_APPROVAL));
            WorkflowInstanceId followed = startPurchase(second, ACME, "p-acme-3", "PO-3");

            // Then it is pinned to the default scope's version
            assertEquals(pin(ACME, DEFAULT), second.pin(followed));

            // When a copy of Acme's document with a scope line is loaded into Acme
            Throwable refusal = failureOf(second.graph()
                    .definitionService()
                    .load(ACME, acmeDocumentWithScope(GLOBEX), DocumentFormat.YAML, source("acme-with-scope")));

            // Then it is rejected with a parse error and stores no row, in globex or anywhere else
            assertNotNull(refusal, "a document with a scope field must be rejected");
            assertInstanceOf(WorkflowDefinitionParseException.class, rootOf(refusal));
            assertEquals(0L, second.definitionRows(GLOBEX, PURCHASE_APPROVAL));
            assertEquals(1L, second.definitionRows(ACME, PURCHASE_APPROVAL));
        }
    }

    private static ScenarioNode deploy(Vertx vertx) {
        return ScenarioNode.on(vertx, db)
                .code(new PurchaseApprovalDefinition())
                .types(ScenarioCatalogs.purchase())
                .start();
    }

    private static Throwable rootOf(Throwable failure) {
        Throwable current = failure;
        while (!(current instanceof WorkflowDefinitionParseException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
