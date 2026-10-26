// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.IDLE;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.awaitCondition;
import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.startOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.exception.WorkflowVersionNotRunnableException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.CommandRow;
import dev.vertique.workflow.postgresql.executor.ExecutorFixture.Node;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a plan which stops resolving between the peek and the segment releases the claim instead of failing
 * the command or its instance.
 */
@ExtendWith(DatabaseExtension.class)
class SegmentPinMismatchIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_segment_pin_mismatch_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a plan removed between the peek and the segment releases the command and leaves the instance pending")
    void planRemovedBetweenPeekAndSegmentReleasesTheCommand() {
        try (ExecutorFixture fixture = ExecutorFixture.open(db.toPoolConfig())) {
            // Given a node whose registry resolves a plan for the peek and never again, and a start recorded elsewhere
            AtomicInteger resolutions = new AtomicInteger();
            AtomicInteger refusals = new AtomicInteger();
            WorkflowRegistry real = fixture.newRegistry();
            WorkflowRegistry vanishing = (WorkflowRegistry) Proxy.newProxyInstance(
                    WorkflowRegistry.class.getClassLoader(),
                    new Class<?>[] {WorkflowRegistry.class},
                    (p, method, args) -> {
                        boolean lookup = args != null && args.length == 1 && args[0] instanceof DefinitionKey;
                        if (lookup && method.getName().equals("resolve")) {
                            if (resolutions.incrementAndGet() > 1) {
                                refusals.incrementAndGet();
                                throw new WorkflowVersionNotRunnableException((DefinitionKey) args[0]);
                            }
                        } else if (lookup && method.getName().equals("find") && resolutions.get() > 0) {
                            // the plan has left the registry: later peeks see it gone and defer the command
                            return java.util.Optional.empty();
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            Node recorder = fixture.node("recorder").build();
            Node a = fixture.node("A")
                    .withRegistry(vanishing)
                    .pollIntervalMs(100)
                    .build();
            WorkflowInstanceId id = recorder.record(startOf("vanishing-plan"));

            // When A's executor claims the command and the segment cannot resolve the plan
            a.startExecutor();
            awaitCondition("the segment to be refused the plan", IDLE, () -> refusals.get() > 0);
            awaitCondition("the command to be released and deferred", IDLE, () -> "DEFERRED"
                    .equals(fixture.commandOf(id).state()));
            a.stopExecutor();

            // Then the instance still waits for its start and the command is neither failed nor spent
            CommandRow row = fixture.commandOf(id);
            assertThat(row.state()).isNotIn("FAILED", "COMPLETE", "REJECTED");
            assertThat(row.attempts()).isZero();
            assertThat(row.claimToken()).isNull();
            assertThat(fixture.instanceOf(id).status()).isEqualTo("PENDING");
        }
    }
}
