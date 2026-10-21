// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.it.fixture.OrderDocumentSignals;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException;
import dev.vertique.workflow.exception.WorkflowDefinitionLifecycleException.Reason;
import dev.vertique.workflow.postgresql.registry.DefinitionScenario.Boot;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.postgresql.registry.MixedCodeFixtures.Shape;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a node whose deployed code lacks a version that is still live does not start, and starts once that
 * version is retired: a deployment only ever rolls forward, so no node runs with a version marked as not runnable.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class CodeUnavailableStartupIT {

    /** The contract of every code definition of these tests. */
    @WorkflowContract("code-unavailable-c")
    public interface UnavailableContract extends OrderDocumentSignals {}

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_code_unavailable_startup_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @DisplayName("startup fails for a removed class until its version is retired, and for an older deployment")
    void startupFailsForRemovedClassUntilVersionIsRetired(Vertx vertx) {
        String removed = "code-unavailable-removed";
        String rolledBack = "code-unavailable-rolled-back";
        DefinitionKey removedV1 = new DefinitionKey(WorkflowScopes.DEFAULT, removed, 1);
        DefinitionKey rolledBackV2 = new DefinitionKey(WorkflowScopes.DEFAULT, rolledBack, 2);

        try (DefinitionScenario database = new DefinitionScenario(vertx, db)) {
            database.ownRows(removed, rolledBack);

            // Given a store where versions 1 and 2 of one code definition were published
            Boot published = database.node(none(), unobserved(), List.of(version1(removed), version2(removed)));
            long coolingSeconds = published.app().workflowDefinitionsConfig().maxStalenessSeconds();
            published.close();

            // When a restart deploys only version 2
            Boot removedClass = database.attempt(none(), unobserved(), List.of(version2(removed)), null, 10);

            // Then startup fails because version 1 is live without code, naming that version
            assertThat(removedClass.failure())
                    .as("startup without the class of a live version")
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.CODE_UNAVAILABLE);
                        assertThat(failure.key()).isEqualTo(removedV1);
                    });
            removedClass.close();

            // When version 1 is retired, with no instance pinned to it, and the restart is tried again
            Boot retiring = database.node(none(), unobserved(), List.of(version1(removed), version2(removed)));
            await(retiring.app().definitionService().deprecate(removedV1));
            database.ageStatus(removedV1, coolingSeconds + 60);
            await(retiring.app().definitionService().retire(removedV1));
            assertThat(database.stored(removed, 1).status()).isEqualTo("RETIRED");
            retiring.close();
            Boot afterRetirement = database.attempt(none(), unobserved(), List.of(version2(removed)), null, 10);

            // Then the node starts
            assertThat(afterRetirement.failure())
                    .as("startup after the retirement")
                    .isNull();
            afterRetirement.close();

            // Given another store where version 2 of a code definition was published and is live
            database.removeRows(removed);
            Boot newer = database.node(none(), unobserved(), List.of(version1(rolledBack), version2(rolledBack)));
            newer.close();

            // When a node that deploys only version 1 starts, as an older deployment restarting would
            Boot older = database.attempt(none(), unobserved(), List.of(version1(rolledBack)), null, 10);

            // Then startup fails because version 2 is live without code, naming that version
            assertThat(older.failure())
                    .as("startup of an older deployment")
                    .isInstanceOfSatisfying(WorkflowDefinitionLifecycleException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(Reason.CODE_UNAVAILABLE);
                        assertThat(failure.key()).isEqualTo(rolledBackV2);
                    });
            older.close();
        }
    }

    private static WorkflowDefinition<?, ?> version1(String id) {
        return MixedCodeFixtures.code(id, 1, Shape.COMPLETES, UnavailableContract.class);
    }

    private static WorkflowDefinition<?, ?> version2(String id) {
        return MixedCodeFixtures.code(id, 2, Shape.WAITS_FOR_SIGNAL, UnavailableContract.class);
    }

    private static BootObservation unobserved() {
        return new BootObservation("code-unavailable-unused", 1, "unused", false);
    }

    private static WorkflowDefinitionSource none() {
        return List::of;
    }
}
