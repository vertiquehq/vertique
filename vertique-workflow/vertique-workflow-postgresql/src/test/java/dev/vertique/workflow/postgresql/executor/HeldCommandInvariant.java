// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.sqlclient.Row;
import java.util.ArrayList;
import java.util.List;

/**
 * The invariant of the commands a wait holds, read from the tables on a connection of their own: every {@code PARKED}
 * signal command whose target carries a {@code waitId} names the wait record that is open for its instance at the
 * address the command names, so that a parked correlated command never outlives the entry it was recorded for. A
 * command without a {@code waitId} is not read.
 */
final class HeldCommandInvariant {

    private HeldCommandInvariant() {}

    /**
     * Asserts the invariant for every instance in the fixture's database. A violation names the instance and the command;
     * no key value is read.
     *
     * @param f the fixture whose database is read
     */
    static void holds(KeyedWaitFixture f) {
        List<String> violations = new ArrayList<>();
        for (Row row : await(f.fixture
                .observer()
                .query("SELECT c.id, c.workflow_id FROM workflow_commands c"
                        + " WHERE c.kind = 'SIGNAL' AND c.state = 'PARKED'"
                        + " AND c.target_json->>'waitId' IS NOT NULL"
                        + " AND NOT EXISTS (SELECT 1 FROM workflow_signal_waits w"
                        + " WHERE w.instance_id = c.workflow_id"
                        + " AND w.wait_id::text = c.target_json->>'waitId'"
                        + " AND w.fork_step_id = COALESCE(c.target_json->>'forkStepId', '')"
                        + " AND w.branch_id = COALESCE(c.target_json->>'branchId', ''))")
                .execute())) {
            violations.add("command " + row.getLong("id") + " of instance " + row.getUUID("workflow_id"));
        }
        assertThat(violations)
                .as("parked correlated commands that name no open wait record of their instance")
                .isEmpty();
    }
}
