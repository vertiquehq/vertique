// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.mcp.tool.McpToolDescriptor;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.Validator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Compiles one immutable set of {@code vertx-json-schema} {@link Validator} instances from a
 * built tool descriptor registry.
 *
 * <p>{@code vertx-json-schema} publishes no cross-{@link io.vertx.core.Context Context} concurrency
 * guarantee for a compiled {@link Validator}, so one instance of this class is composed fresh per
 * deployed server Vert.x {@code Context} — one Dagger graph per verticle instance in this
 * framework's stateless multi-instance deployment model yields exactly that. An instance compiles
 * every tool's input validator, and its output validator when the tool publishes structured output,
 * exactly once at construction; nothing here compiles lazily or on the request path, and no
 * validator instance is ever shared between two instances of this class.
 *
 * <p>Not part of the application-facing public surface: the frozen {@code McpToolRuntime}/
 * {@code McpToolRuntimeFactory}/{@code McpToolParameterMetadata} contract in
 * {@code dev.vertique.mcp.server.runtime} is the only consumer-visible schema/runtime API this
 * module publishes. This type, its constructor, and its accessors are package-private on purpose —
 * its owning proof ({@code McpValidatorConcurrencyTest}) lives in this same package for exactly that
 * reason — and {@code McpServerInventoryGuardTest}'s progressive public-surface guard would fail this
 * module's build the moment any member here were widened to {@code public} without a matching,
 * deliberate inventory update.
 */
final class McpSchemaRegistry {

    private static final JsonSchemaOptions SCHEMA_OPTIONS = new JsonSchemaOptions()
            .setDraft(Draft.DRAFT202012)
            .setBaseUri("https://vertique.local/")
            .setOutputFormat(OutputFormat.Basic);

    private final Map<String, Validator> inputValidatorsByToolName;
    private final Map<String, Validator> outputValidatorsByToolName;

    /**
     * Compiles the validator set for every descriptor in {@code descriptorsByToolName}.
     *
     * @param descriptorsByToolName the built tool descriptor registry, keyed by tool name
     * @throws NullPointerException if {@code descriptorsByToolName} is {@code null}
     */
    McpSchemaRegistry(Map<String, McpToolDescriptor> descriptorsByToolName) {
        Objects.requireNonNull(descriptorsByToolName, "descriptorsByToolName");
        Map<String, Validator> inputs = new LinkedHashMap<>();
        Map<String, Validator> outputs = new LinkedHashMap<>();
        for (Map.Entry<String, McpToolDescriptor> entry : descriptorsByToolName.entrySet()) {
            McpToolDescriptor descriptor = entry.getValue();
            inputs.put(entry.getKey(), compile(descriptor.inputSchema()));
            if (descriptor.outputSchema() != null) {
                outputs.put(entry.getKey(), compile(descriptor.outputSchema()));
            }
        }
        this.inputValidatorsByToolName = Map.copyOf(inputs);
        this.outputValidatorsByToolName = Map.copyOf(outputs);
    }

    /**
     * Returns the compiled input validator for one tool.
     *
     * @param toolName the tool name
     * @return the compiled validator, owned exclusively by this instance
     * @throws IllegalArgumentException if no descriptor named {@code toolName} was compiled
     */
    Validator inputValidator(String toolName) {
        Validator validator = inputValidatorsByToolName.get(toolName);
        if (validator == null) {
            throw new IllegalArgumentException("no compiled input validator for tool '" + toolName + "'");
        }
        return validator;
    }

    /**
     * Returns the compiled output validator for one tool, when it publishes structured output.
     *
     * @param toolName the tool name
     * @return the compiled validator, or {@link Optional#empty()} when the tool publishes no
     *     structured output
     */
    Optional<Validator> outputValidator(String toolName) {
        return Optional.ofNullable(outputValidatorsByToolName.get(toolName));
    }

    /**
     * Compiles one {@code vertx-json-schema} validator from a canonical schema document.
     *
     * @param canonicalSchemaJson the canonical JSON Schema document text
     * @return the compiled, reusable validator
     */
    private static Validator compile(String canonicalSchemaJson) {
        return Validator.create(JsonSchema.of(new JsonObject(canonicalSchemaJson)), SCHEMA_OPTIONS);
    }
}
