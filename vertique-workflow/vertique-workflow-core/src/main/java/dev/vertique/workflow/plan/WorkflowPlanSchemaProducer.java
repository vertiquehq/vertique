// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

import dev.vertique.workflow.registry.RuntimeWorkflow;
import io.vertx.core.json.JsonObject;
import java.util.Map;

/**
 * INTERNAL framework seam: produces the JSON Schemas that a definition row stores next to its plan hash. It is not
 * an application contract and is outside the maturity promise.
 *
 * <p>The {@code vertique-workflow-schema} module implements and binds it. The binding is optional: a node without
 * it writes definition rows without a start schema and without declared signal names. A node that executes
 * workflows is expected to bind it.
 *
 * <p>Both methods are pure and deterministic for one plan: they read the plan and never register, store or change
 * anything.
 */
public interface WorkflowPlanSchemaProducer {

    /**
     * Returns the JSON Schema of the start payload of the plan, as payload validation uses it.
     *
     * @param plan the runtime workflow whose start payload is described
     * @return the schema document, or {@code null} when the plan declares no start payload
     */
    JsonObject startSchema(RuntimeWorkflow plan);

    /**
     * Returns the JSON Schema of every decision payload of the plan's human tasks. A decision without a payload has
     * no entry.
     *
     * @param plan the runtime workflow whose decisions are described
     * @return the schemas keyed by step id, then decision name
     */
    Map<String, Map<String, JsonObject>> decisionSchemas(RuntimeWorkflow plan);
}
