// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.LEAK_MARKER;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.failureOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.exception.WorkflowInstanceNotFoundException;
import dev.vertique.workflow.exception.WorkflowResultTypeMismatchException;
import dev.vertique.workflow.exception.WorkflowResultUnavailableException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.AnnotatedReceipt;
import dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.Invoice;
import dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.MapperSwitch;
import dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.Mode;
import dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.OutputRow;
import dev.vertique.workflow.postgresql.executor.DeclaredOutputFixtures.Receipt;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.Definition;
import dev.vertique.workflow.postgresql.executor.RecordedSignalIT.SignalFixture;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Tuple;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the typed read of a declared output, {@code result(id, Type)}: what it refuses before an instance has
 * completed or when no output was declared, how it matches the requested class against the stored type name, and that
 * it reads the row alone, so a node that does not hold the code and an archived row serve it.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class DeclaredOutputResultIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_declared_output_result_it")
            .withMigration("classpath:db/migration/workflow");

    private static final String REF = LEAK_MARKER + "-result";

    @Test
    @DisplayName("result on a waiting instance is refused as not completed, with its status")
    void resultOnAWaitingInstanceIsNotCompleted() {
        MapperSwitch mapper = new MapperSwitch(Mode.GOOD);
        try (SignalFixture f = fixture(DeclaredOutputFixtures.signalThenComplete("declared-output-waiting", mapper))) {
            // Given an instance waiting for a signal, in a definition that declares an output
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, REF);

            // When the result is asked for
            Throwable failure = failureOf(node.engine.result(id, Receipt.class), IDLE);

            // Then it is refused because the instance has not completed, and the refusal names the status
            assertThat(failure).isInstanceOfSatisfying(WorkflowResultUnavailableException.class, unavailable -> {
                assertThat(unavailable.reason()).isEqualTo(WorkflowResultUnavailableException.Reason.NOT_COMPLETED);
                assertThat(unavailable.status()).isEqualTo(WorkflowStatus.WAITING);
                assertThat(unavailable.instanceId()).isEqualTo(id);
            });
        }
    }

    @Test
    @DisplayName("result on a completed instance of a definition without an output is refused as no output")
    void resultWithoutADeclaredOutputIsNoOutput() {
        try (SignalFixture f = fixture(DeclaredOutputFixtures.instantCompleteWithoutOutput("declared-output-none"))) {
            // Given a completed instance of a definition that declares no output
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, REF);
            assertThat(DeclaredOutputFixtures.outputRowOf(f, id).status()).isEqualTo("COMPLETED");

            // When the result is asked for
            Throwable failure = failureOf(node.engine.result(id, Receipt.class), IDLE);

            // Then it is refused because the definition stored no output
            assertThat(failure).isInstanceOfSatisfying(WorkflowResultUnavailableException.class, unavailable -> {
                assertThat(unavailable.reason()).isEqualTo(WorkflowResultUnavailableException.Reason.NO_OUTPUT);
                assertThat(unavailable.status()).isEqualTo(WorkflowStatus.COMPLETED);
            });
        }
    }

    @Test
    @DisplayName("result asks for the stored class and gets the decoded value; another class is refused without it")
    void resultReturnsTheValueForTheStoredClassAndRefusesAnotherClass() {
        try (SignalFixture f =
                fixture(DeclaredOutputFixtures.instantComplete("declared-output-typed", new MapperSwitch(Mode.GOOD)))) {
            // Given a completed instance whose output is a Receipt
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, REF);

            // When the result is asked for as a Receipt and as an Invoice
            Receipt value = await(node.engine.result(id, Receipt.class));
            Throwable mismatch = failureOf(node.engine.result(id, Invoice.class), IDLE);

            // Then the Receipt is the decoded value, and the Invoice is refused naming both types and no value
            assertThat(value).isEqualTo(new Receipt(REF, 0, null));
            assertThat(mismatch).isInstanceOfSatisfying(WorkflowResultTypeMismatchException.class, refused -> {
                assertThat(refused.storedTypeName()).isEqualTo(Receipt.class.getName());
                assertThat(refused.requestedTypeName()).isEqualTo(Invoice.class.getName());
                assertThat(refused.instanceId()).isEqualTo(id);
                assertThat(refused.getMessage())
                        .contains(Receipt.class.getName(), Invoice.class.getName())
                        .doesNotContain(REF);
            });
        }
    }

    @Test
    @DisplayName("result on an unknown instance fails as not found")
    void resultOnAnUnknownInstanceIsNotFound() {
        try (SignalFixture f = fixture(
                DeclaredOutputFixtures.instantComplete("declared-output-unknown", new MapperSwitch(Mode.GOOD)))) {
            // Given an engine and an id no instance has
            Node node = f.node("engine").build();
            WorkflowInstanceId unknown = new WorkflowInstanceId(UUID.randomUUID());

            // When the result is asked for
            Throwable failure = failureOf(node.engine.result(unknown, Receipt.class), IDLE);

            // Then it fails as not found
            assertThat(failure).isInstanceOf(WorkflowInstanceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("result matches a stored binary name when the class has since been given a logical name")
    void resultMatchesTheBinaryNameOfAClassAnnotatedAfterPublication() {
        try (SignalFixture f = fixture(DeclaredOutputFixtures.annotatedOutput("declared-output-annotated"))) {
            // Given a completed instance of a definition whose output class carries a logical name, whose stored type
            // name is the logical name
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, REF);
            OutputRow stored = DeclaredOutputFixtures.outputRowOf(f, id);
            assertThat(stored.outputTypeName()).isEqualTo("declared.output.AnnotatedReceipt");

            // And the stored name rewritten to the binary name, as a version published before the class was
            // annotated stored it (an annotation cannot be changed at run time, so the row is what is modelled)
            setStoredTypeName(f, id, AnnotatedReceipt.class.getName());

            // When the result is asked for as the annotated class
            AnnotatedReceipt value = await(node.engine.result(id, AnnotatedReceipt.class));

            // Then the value is returned
            assertThat(value).isEqualTo(new AnnotatedReceipt(REF));
        }
    }

    @Test
    @DisplayName("result matches the stored name the pinned definition's name binding gives the class, and no other")
    void resultMatchesThroughTheNameBindingOfThePinnedDefinition() {
        try (SignalFixture f = fixture(DeclaredOutputFixtures.annotatedOutput("declared-output-bound"))) {
            // Given a completed instance whose stored type name matches neither name of the requested class
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, REF);
            setStoredTypeName(f, id, "legacy.Receipt");

            // When the result is asked for while the definition row holds no binding for the class
            Throwable unbound = failureOf(node.engine.result(id, AnnotatedReceipt.class), IDLE);

            // Then it is a mismatch
            assertThat(unbound).isInstanceOf(WorkflowResultTypeMismatchException.class);

            // When the pinned definition's name binding maps the class's binary name to the stored name
            await(f.fixture
                    .observer()
                    .preparedQuery("UPDATE workflow_definitions SET name_binding = $2 WHERE definition_id = $1")
                    .execute(Tuple.of(
                            "declared-output-bound",
                            new JsonObject().put(AnnotatedReceipt.class.getName(), "legacy.Receipt"))));
            AnnotatedReceipt value = await(node.engine.result(id, AnnotatedReceipt.class));

            // Then the value is returned
            assertThat(value).isEqualTo(new AnnotatedReceipt(REF));
        }
    }

    @Test
    @DisplayName("result is served by a node whose registry holds no definition")
    void resultNeedsNoPlan() {
        try (SignalFixture f = fixture(
                DeclaredOutputFixtures.instantComplete("declared-output-planless", new MapperSwitch(Mode.GOOD)))) {
            // Given a completed instance, and a node that holds the code of no definition
            Node full = f.node("full").build();
            WorkflowInstanceId id = f.waiting(full, REF);
            Node hidden =
                    f.node("hidden").withRegistry(new DefaultWorkflowRegistry()).build();

            // When that node is asked for the result
            Receipt value = await(hidden.engine.result(id, Receipt.class));

            // Then it returns the value stored with the instance
            assertThat(value).isEqualTo(new Receipt(REF, 0, null));
        }
    }

    @Test
    @DisplayName("result is served for an archived instance")
    void resultReadsAnArchivedInstance() {
        try (SignalFixture f = fixture(
                DeclaredOutputFixtures.instantComplete("declared-output-archived", new MapperSwitch(Mode.GOOD)))) {
            // Given a completed instance that has been archived
            Node node = f.node("engine").build();
            WorkflowInstanceId id = f.waiting(node, REF);
            await(f.fixture
                    .observer()
                    .preparedQuery("UPDATE workflow_instances SET archived_at = clock_timestamp() WHERE id = $1")
                    .execute(Tuple.of(id.value())));
            assertThat(f.row("SELECT archived_at IS NOT NULL AS archived FROM workflow_instances WHERE id = $1", id)
                            .getBoolean("archived"))
                    .isTrue();

            // When the result is asked for
            Receipt value = await(node.engine.result(id, Receipt.class));

            // Then it returns the stored value
            assertThat(value).isEqualTo(new Receipt(REF, 0, null));
        }
    }

    private static SignalFixture fixture(Definition definition) {
        return new SignalFixture(db.toPoolConfig(), definition, List.of("approve"));
    }

    private static void setStoredTypeName(SignalFixture f, WorkflowInstanceId id, String name) {
        await(f.fixture
                .observer()
                .preparedQuery("UPDATE workflow_instances SET output_type_name = $2 WHERE id = $1")
                .execute(Tuple.of(id.value(), name)));
    }
}
