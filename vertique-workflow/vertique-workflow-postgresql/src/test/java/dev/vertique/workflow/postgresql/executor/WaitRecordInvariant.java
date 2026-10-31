// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import static dev.vertique.workflow.postgresql.executor.ExecutorFixture.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.sqlclient.Row;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The invariant of the wait records, read from the tables on a connection of their own: for a live instance, a
 * {@code FAILED} one included, the set of its records equals the set of its open signal waits, which are its main wait
 * when that is a signal wait and each of its branch tokens that waits on a signal; an instance that has ended has no
 * record. Only a wait whose pinned plan declares a key has a record, so an open keyed wait without one is a violation, and so
 * is a record for a wait that declares no key.
 */
final class WaitRecordInvariant {

    private static final Set<String> ENDED = Set.of("COMPLETED", "COMPENSATED", "CANCELLED");

    private WaitRecordInvariant() {}

    /**
     * Asserts the invariant for every instance in the fixture's database. A violation names the instance, its status
     * and the two sets; no key value is read.
     *
     * @param f the fixture whose database is read
     */
    static void holds(KeyedWaitFixture f) {
        Map<UUID, Set<String>> expected = new HashMap<>();
        Map<UUID, String> statuses = new HashMap<>();
        Map<UUID, String> definitions = new HashMap<>();
        for (Row row : await(f.fixture
                .observer()
                .query("SELECT id, status, current_step_id, wait_type, definition_id, definition_version"
                        + " FROM workflow_instances")
                .execute())) {
            UUID id = row.getUUID("id");
            String status = row.getString("status");
            statuses.put(id, status);
            Set<String> open = new HashSet<>();
            definitions.put(id, row.getString("definition_id") + "@" + row.getLong("definition_version"));
            if (!ENDED.contains(status)
                    && "SIGNAL".equals(row.getString("wait_type"))
                    && keyed(
                            f,
                            row.getString("definition_id"),
                            row.getLong("definition_version"),
                            row.getString("current_step_id"))) {
                open.add(address(row.getString("current_step_id"), "", ""));
            }
            expected.put(id, open);
        }
        for (Row row : await(f.fixture
                .observer()
                .query("SELECT workflow_id, fork_step_id, branch_id, current_step_id FROM workflow_branch_tokens"
                        + " WHERE status = 'WAITING' AND wait_type = 'SIGNAL'")
                .execute())) {
            UUID id = row.getUUID("workflow_id");
            String[] definition = definitions.get(id).split("@");
            if (!ENDED.contains(statuses.get(id))
                    && keyed(f, definition[0], Long.parseLong(definition[1]), row.getString("current_step_id"))) {
                expected.get(id)
                        .add(address(
                                row.getString("current_step_id"),
                                row.getString("fork_step_id"),
                                row.getString("branch_id")));
            }
        }
        Map<UUID, Set<String>> actual = new HashMap<>();
        statuses.keySet().forEach(id -> actual.put(id, new HashSet<>()));
        for (Row row : await(f.fixture
                .observer()
                .query("SELECT instance_id, step_id, fork_step_id, branch_id FROM workflow_signal_waits")
                .execute())) {
            actual.computeIfAbsent(row.getUUID("instance_id"), ignored -> new HashSet<>())
                    .add(address(row.getString("step_id"), row.getString("fork_step_id"), row.getString("branch_id")));
        }
        for (Map.Entry<UUID, Set<String>> entry : actual.entrySet()) {
            Set<String> wanted = expected.getOrDefault(entry.getKey(), Set.of());
            assertThat(entry.getValue())
                    .as(
                            "the records of instance %s, which is %s, against its open signal waits %s",
                            entry.getKey(), statuses.get(entry.getKey()), wanted)
                    .containsExactlyInAnyOrderElementsOf(wanted);
        }
    }

    /** Whether the plan the instance is pinned to declares a key for the signal wait at the step. */
    private static boolean keyed(KeyedWaitFixture f, String definitionId, long version, String stepId) {
        return f.declaresKey(definitionId, version, stepId);
    }

    /**
     * The address of a wait: its step, its fork step and its branch, the last two empty on the main path.
     */
    private static String address(String stepId, String forkStepId, String branchId) {
        return List.of(stepId, forkStepId == null ? "" : forkStepId, branchId == null ? "" : branchId)
                .toString();
    }
}
