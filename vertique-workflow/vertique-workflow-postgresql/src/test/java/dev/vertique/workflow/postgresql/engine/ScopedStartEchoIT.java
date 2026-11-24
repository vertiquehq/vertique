// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.ACME;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.GLOBEX;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.ID;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.PURCHASE_CONTRACT;
import static dev.vertique.workflow.postgresql.engine.ScopedStartFixture.failureOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.exception.WorkflowDefinitionMissingException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.postgresql.engine.ScopedStartFixture.Harness;
import dev.vertique.workflow.postgresql.engine.ScopedStartFixture.Purchase;
import dev.vertique.workflow.postgresql.engine.ScopedStartFixture.StartPurchase;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Real PostgreSQL proof that a failed start repeats a caller-supplied definition id or scope as supplied: the
 * message holds the whole text and the typed accessors carry the same identifiers.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class ScopedStartEchoIT {

    private static final int GRINNING_FACE = 0x1F600;

    /** An id with a line break, a control character and 100 supplementary code points after them. */
    private static final String HOSTILE_ID = "forged" + "\r\n" + "LOG-LINE" + (char) 7
            + Character.toString(GRINNING_FACE).repeat(100);

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_scoped_start_echo_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;

    @BeforeAll
    static void setUp(Vertx vertx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(10))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
    }

    @BeforeEach
    void truncateTables() throws Exception {
        ScopedStartFixture.truncate(pool);
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    private static void assertEchoedAsSupplied(String message) {
        assertThat(message).as("message").isNotNull().contains(HOSTILE_ID);
    }

    private static StartCommand command(String definitionId, Long version, String startScope, String targetScope) {
        return new StartCommand(
                definitionId,
                PURCHASE_CONTRACT,
                new StartPurchase("p"),
                "k-echo",
                null,
                null,
                version,
                startScope,
                targetScope);
    }

    @Test
    @DisplayName("an unknown definition id is repeated as supplied")
    void unknownDefinitionIdIsAsSupplied() throws Exception {
        // Given: an engine that knows only the purchase definition
        Harness harness = ScopedStartFixture.open(pool, new Purchase(ID, 1L, "done"));

        // When: a start names an unknown id with a line break and a long tail
        Throwable failure = failureOf(() -> harness.engine().start(command(HOSTILE_ID, null, null, null)));

        // Then: it is refused as a missing definition whose message holds the id as supplied
        assertThat(failure).isInstanceOf(WorkflowDefinitionMissingException.class);
        assertEchoedAsSupplied(failure.getMessage());
    }

    @Test
    @DisplayName("a version in a foreign scope is refused with the definition id as supplied")
    void foreignScopeVersionIsAsSupplied() throws Exception {
        // Given: an engine that knows the purchase definition
        Harness harness = ScopedStartFixture.open(pool, new Purchase(ID, 1L, "done"));

        // When: acme starts version 1 of a hostile id in globex, a scope it cannot name
        Throwable failure = failureOf(() -> harness.engine().start(command(HOSTILE_ID, 1L, ACME, GLOBEX)));

        // Then: it is refused as a missing definition whose message holds the id as supplied
        assertThat(failure).isInstanceOf(WorkflowDefinitionMissingException.class);
        assertEchoedAsSupplied(failure.getMessage());
    }

    @Test
    @DisplayName("a version that no key can hold is refused with the definition id as supplied")
    void badVersionIsAsSuppliedAndKeepsTheFullIdTyped() throws Exception {
        // Given: an engine that knows the purchase definition
        Harness harness = ScopedStartFixture.open(pool, new Purchase(ID, 1L, "done"));

        // When: a start names an explicit version of an id that no definition key can hold
        Throwable failure = failureOf(() -> harness.engine().start(command(HOSTILE_ID, 1L, null, null)));

        // Then: the unavailable-version refusal holds the id as supplied
        assertThat(failure).isInstanceOf(WorkflowVersionPinUnavailableException.class);
        assertEchoedAsSupplied(failure.getMessage());
        // and: the typed accessor still carries the full id
        assertThat(((WorkflowVersionPinUnavailableException) failure).definitionId())
                .isEqualTo(HOSTILE_ID);
    }

    @Test
    @DisplayName("a non-positive version is refused with the definition id as supplied")
    void nonPositiveVersionIsAsSupplied() throws Exception {
        // Given: an engine that knows the purchase definition
        Harness harness = ScopedStartFixture.open(pool, new Purchase(ID, 1L, "done"));

        // When: a start names version 0 of a hostile id
        Throwable failure = failureOf(() -> harness.engine().start(command(HOSTILE_ID, 0L, null, null)));

        // Then: the refusal holds the id as supplied
        assertThat(failure).isInstanceOf(WorkflowVersionPinUnavailableException.class);
        assertEchoedAsSupplied(failure.getMessage());
    }
}
