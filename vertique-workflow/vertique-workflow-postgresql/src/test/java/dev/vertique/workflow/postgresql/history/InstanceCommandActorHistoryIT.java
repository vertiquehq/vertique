// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.history;

import static dev.vertique.workflow.postgresql.history.AuditRun.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.history.WorkflowInstanceHistoryService;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.postgresql.executor.ActorScenario;
import dev.vertique.workflow.postgresql.executor.MigrationScenario;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies, through the public history service, that every command that changes an instance is logged with the actor
 * that recorded it: a start, a cancellation, a retry and a migration each name the caller on their own history entry,
 * and a call with no identity is logged as such.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
public class InstanceCommandActorHistoryIT {

    // The container starts empty; the three migration sets all begin at V1, so each gets a history table of its own.
    static final PostgresContainer db = new PostgresContainer().withDatabaseName("workflow_instance_actor_history_it");

    @BeforeAll
    static void migrate() {
        migrate("classpath:db/migration/workflow", "flyway_workflow_history", false);
        migrate("classpath:db/migration/inbox-outbox", "flyway_inbox_outbox_history", true);
        migrate("classpath:db/migration/job", "flyway_job_history", true);
    }

    private static void migrate(String location, String historyTable, boolean baseline) {
        var flyway = Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations(location)
                .table(historyTable);
        if (baseline) {
            flyway.baselineOnMigrate(true).baselineVersion("0");
        }
        flyway.load().migrate();
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("a start and a cancellation are logged with the user and the service that recorded them")
    void aStartAndACancellationNameTheirCallers(Vertx vertx) {
        PgConnectOptions options = HistoryGraph.options(db);
        Pool reading = HistoryGraph.pool(vertx, options);
        try (ActorScenario scenario = ActorScenario.timeoutWait(db.toPoolConfig())) {
            WorkflowInstanceHistoryService history =
                    HistoryGraph.reading(vertx, options, reading).historyService();

            // An actor ID of the longest storable length, with a line break and a bidi override, is stored as it is
            // and still names the same caller.
            String userId = "u-0\n\u202e" + "x".repeat(250);
            WorkflowInstanceId named = scenario.start(user(userId), "actor-named", Map.of());
            scenario.runToIdle(named);
            scenario.cancel(service("ops"), named);
            WorkflowInstanceId nobody = scenario.start(null, "actor-nobody", Map.of());
            scenario.runToIdle(nobody);
            WorkflowInstanceId anonymous =
                    scenario.start(dev.vertique.security.SecurityIdentity.anonymous(), "actor-anonymous", Map.of());
            scenario.runToIdle(anonymous);

            // When the log of each is read through the history service
            List<WorkflowHistoryEntry> namedLog = await(history.entries(named, 0, 500));
            List<WorkflowHistoryEntry> nobodyLog = await(history.entries(nobody, 0, 500));
            List<WorkflowHistoryEntry> anonymousLog = await(history.entries(anonymous, 0, 500));

            // Then the start entry names the user, and the cancellation entry names the service
            WorkflowHistoryEntry start = only(namedLog, WorkflowEntryType.START);
            assertThat(start.actor()).isEqualTo(new WorkflowActor.User(userId));
            WorkflowHistoryEntry cancelled = only(namedLog, WorkflowEntryType.CANCELLED);
            assertThat(cancelled.actor()).isEqualTo(new WorkflowActor.Service("ops"));

            // And a start with no security context is logged with no actor
            assertThat(only(nobodyLog, WorkflowEntryType.START).actor()).isNull();

            // And a start by an anonymous caller is logged with the anonymous actor
            assertThat(only(anonymousLog, WorkflowEntryType.START).actor()).isEqualTo(new WorkflowActor.Anonymous());
        } finally {
            reading.close();
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("a retry and a migration are logged with the user and the service that recorded them")
    void aRetryAndAMigrationNameTheirCallers(Vertx vertx) {
        PgConnectOptions options = HistoryGraph.options(db);
        Pool reading = HistoryGraph.pool(vertx, options);
        try (MigrationScenario scenario = MigrationScenario.atReplyWait(db.toPoolConfig())) {
            WorkflowInstanceHistoryService history =
                    HistoryGraph.reading(vertx, options, reading).historyService();

            // Given an instance that failed at its wait, retried by user r-1, then migrated by service deployer
            scenario.failAtWait();
            scenario.retryAs(user("r-1"));
            scenario.migrateAs(service("deployer"));

            // When its log is read through the history service
            List<WorkflowHistoryEntry> log = await(history.entries(scenario.id(), 0, 500));

            // Then the retry entry names the user, and the migration entry names the service
            WorkflowHistoryEntry retried = only(log, WorkflowEntryType.RETRIED);
            assertThat(retried.actor()).isEqualTo(new WorkflowActor.User("r-1"));
            WorkflowHistoryEntry migrated = only(log, WorkflowEntryType.WORKFLOW_MIGRATED);
            assertThat(migrated.actor()).isEqualTo(new WorkflowActor.Service("deployer"));
        } finally {
            reading.close();
        }
    }

    private static WorkflowHistoryEntry only(List<WorkflowHistoryEntry> log, WorkflowEntryType type) {
        return log.stream()
                .filter(entry -> entry.entryType() == type)
                .reduce((first, second) -> {
                    throw new AssertionError("More than one " + type + " entry in " + log);
                })
                .orElseThrow(() -> new AssertionError("No " + type + " entry in " + log));
    }

    private static SecurityIdentity user(String id) {
        return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, id, Map.of()));
    }

    private static SecurityIdentity service(String id) {
        return SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, id, Map.of()));
    }
}
