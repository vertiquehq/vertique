// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dev.vertique.db.test.PostgresContainer;
import dev.vertique.examples.workflow.order.state.OrderState;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import io.vertx.core.json.Json;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * The rows a release without logical type names left behind for the review-task order definition: version 1 hashed over
 * the binary name of the state class, recorded with a binding of binary names to themselves and made current, with one
 * instance waiting at its review task.
 *
 * <p>One JVM cannot hold the state class both unannotated and annotated, so the rows are written with SQL after the
 * plan is built with the binary name, which is what the release computed.
 */
final class PreAnnotationSeed {

    static final String DEFINITION_ID = "order-fulfillment";

    /** What was written: the instance and its task, and the plan hash the version was recorded with. */
    record Seeded(UUID instance, UUID task, String planHash) {}

    private PreAnnotationSeed() {}

    /** The plan hash of the review-task definition when its state class is named by the given name. */
    static String planHash(long version, String stateTypeName) {
        WorkflowBuilder<OrderState> builder = new WorkflowBuilder<>();
        new ReviewedOrderDefinition(version).define(builder);
        return builder.build(DEFINITION_ID, version, stateTypeName).planHash();
    }

    /** Writes version 1, its default activation, an instance at its review task and the task. */
    static Seeded write(PostgresContainer database) {
        String stateName = OrderState.class.getName();
        String approvalName = OrderApproval.class.getName();
        String planHash = planHash(1, stateName);
        UUID instance = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        String binding =
                "{\"" + stateName + "\":\"" + stateName + "\",\"" + approvalName + "\":\"" + approvalName + "\"}";
        String state = Json.encode(OrderState.fromCommand(OrderScenarios.order("order-before-annotation")));
        String decisions =
                "[{\"name\":\"approve\",\"payloadTypeName\":\"" + approvalName + "\",\"nextStepId\":\"done\"}]";
        try (Connection connection =
                DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
            execute(
                    connection,
                    """
                    INSERT INTO workflow_definitions
                        (scope_key, definition_id, definition_version, kind, contract_name, state_type_name, plan_hash,
                         name_binding, status, source_type, source_uri, loaded_at)
                    VALUES ('default', ?, 1, 'CODE', 'order-fulfillment', ?, ?, ?::jsonb, 'AVAILABLE', 'code', ?,
                            '2026-05-10T00:00:00Z')
                    """,
                    DEFINITION_ID,
                    stateName,
                    planHash,
                    binding,
                    ReviewedOrderDefinition.class.getName());
            execute(connection, """
                    INSERT INTO workflow_definition_activations
                        (scope_key, definition_id, target_scope_key, definition_version, activated_at)
                    VALUES ('default', ?, 'default', 1, '2026-05-10T00:00:00Z')
                    """, DEFINITION_ID);
            execute(connection, """
                    INSERT INTO workflow_instances
                        (id, definition_id, definition_version, plan_hash, status, current_step_id, wait_type, wait_key,
                         state_json, created_at, updated_at)
                    VALUES (?::uuid, ?, 1, ?, 'WAITING', 'review', 'TASK', ?, ?::jsonb,
                            '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z')
                    """, instance.toString(), DEFINITION_ID, planHash, task.toString(), state);
            execute(connection, """
                    INSERT INTO workflow_tasks
                        (task_id, workflow_id, step_id, status, assignee_type, assignee_key, decisions_snapshot_json,
                         created_at, updated_at)
                    VALUES (?::uuid, ?::uuid, 'review', 'OPEN', 'ROLE', 'reviewers', ?::jsonb,
                            '2026-05-10T00:00:00Z', '2026-05-10T00:00:00Z')
                    """, task.toString(), instance.toString(), decisions);
        } catch (SQLException e) {
            throw new IllegalStateException("The pre-annotation rows could not be written", e);
        }
        return new Seeded(instance, task, planHash);
    }

    private static void execute(Connection connection, String sql, String... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setString(index + 1, values[index]);
            statement.executeUpdate();
        }
    }
}
