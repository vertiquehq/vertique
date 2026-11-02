// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.exception.WorkflowSignalNoMatchException;
import dev.vertique.workflow.ops.CorrelatedSignalReceipt.Outcome;
import dev.vertique.workflow.postgresql.executor.MigrationScenario;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(DatabaseExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReplyMigrationIT {
    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_reply_migration_it")
            .withMigration("classpath:db/migration/workflow");

    @Test
    void migrationOntoAReplyWaitCallsTheServiceAgain() {
        try (var scenario = MigrationScenario.atReplyWait(db.toPoolConfig())) {
            assertThat(scenario.waits()).hasSize(1);
            UUID oldWait = scenario.waits().getFirst();
            UUID oldTimer = scenario.timer();
            assertThat(oldTimer).isNotNull();
            assertThat(scenario.timerStatus(oldTimer)).isEqualTo("SCHEDULED");
            assertThat(scenario.dispatches()).containsExactly(oldWait);

            scenario.migrate();

            assertThat(scenario.version()).isEqualTo(2L);
            assertThat(scenario.step()).isEqualTo("reserve.reply");
            assertThat(scenario.waits()).hasSize(1);
            UUID newWait = scenario.waits().getFirst();
            assertThat(newWait).isNotEqualTo(oldWait);
            assertThat(scenario.dispatches()).hasSize(2).containsExactlyInAnyOrder(oldWait, newWait);
            assertThat(scenario.timerStatus(oldTimer)).isEqualTo("CANCELLED");
            assertThat(scenario.timer()).isNotNull().isNotEqualTo(oldTimer);
            assertThat(scenario.signal(newWait, "new reply").outcome()).isEqualTo(Outcome.RECORDED);
            scenario.apply();
            assertThat(scenario.waits()).isEmpty();
            assertThat(scenario.version()).isEqualTo(2L);
            assertThat(scenario.step()).isEqualTo("after");
            assertThat(scenario.reserved()).isEqualTo("new reply");
        }
    }

    @Test
    void oldOutcomeAfterAMigrationChangesNothing() {
        try (var scenario = MigrationScenario.atReplyWait(db.toPoolConfig())) {
            UUID oldWait = scenario.waits().getFirst();
            scenario.migrate();
            assertThat(scenario.waits()).hasSize(1);
            UUID newWait = scenario.waits().getFirst();
            assertThat(newWait).isNotEqualTo(oldWait);
            assertThat(scenario.dispatches()).hasSize(2).containsExactlyInAnyOrder(oldWait, newWait);
            UUID newTimer = scenario.timer();
            String state = scenario.state();

            assertThatThrownBy(() -> scenario.signal(oldWait, "stale"))
                    .hasRootCauseInstanceOf(WorkflowSignalNoMatchException.class);
            assertThat(scenario.waits()).containsExactly(newWait);
            assertThat(scenario.timer()).isEqualTo(newTimer);
            assertThat(scenario.timerStatus(newTimer)).isEqualTo("SCHEDULED");
            assertThat(scenario.state()).isEqualTo(state);
            assertThat(scenario.step()).isEqualTo("reserve.reply");
            assertThat(scenario.signal(newWait, "accepted").outcome()).isEqualTo(Outcome.RECORDED);
            scenario.apply();
            assertThat(scenario.step()).isEqualTo("after");
            assertThat(scenario.reserved()).isEqualTo("accepted");
        }
    }

    @Test
    void retryOfAFailedInstanceKeepsItsRecord() {
        try (var scenario = MigrationScenario.atReplyWait(db.toPoolConfig())) {
            UUID wait = scenario.waits().getFirst();
            assertThat(scenario.dispatches()).containsExactly(wait);
            scenario.failAtWait();
            assertThat(scenario.status()).isEqualTo("FAILED");
            assertThat(scenario.waits()).containsExactly(wait);

            scenario.retry();

            assertThat(scenario.status()).isEqualTo("WAITING");
            assertThat(scenario.waits()).containsExactly(wait);
            assertThat(scenario.dispatches()).hasSize(1).containsExactly(wait);
            assertThat(scenario.signal(wait, "after retry").outcome()).isEqualTo(Outcome.RECORDED);
            scenario.apply();
            assertThat(scenario.waits()).isEmpty();
            assertThat(scenario.dispatches()).hasSize(1).containsExactly(wait);
            assertThat(scenario.step()).isEqualTo("after");
            assertThat(scenario.reserved()).isEqualTo("after retry");
        }
    }
}
