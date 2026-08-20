// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.mcp.tool.McpToolDescriptor;
import jakarta.annotation.Nullable;
import java.util.Map;
import java.util.Objects;

/**
 * The immutable schema-and-mapper binding one generated invoker obtains during composition.
 *
 * <p>An instance privately retains the validating descriptor built once at composition and the exact
 * stable {@link ObjectMapper} of the effective JSON profile. The mapper is never exposed, never
 * replaced, and never re-resolved on the request path. Application code neither constructs nor calls
 * this type: {@link McpToolRuntimeFactory#create} is its only construction path.
 *
 * @param <I> the generated input-carrier record type of the owning tool
 */
public final class McpToolRuntime<I> {

    private final McpToolDescriptor descriptor;
    private final ObjectMapper mapper;
    private final Class<I> inputCarrierType;

    /**
     * Binds one tool's descriptor to the exact stable mapper of its effective JSON profile.
     *
     * @param descriptor the descriptor built once during composition
     * @param mapper the effective profile's stable mapper
     * @param inputCarrierType the generated input-carrier record type
     */
    McpToolRuntime(McpToolDescriptor descriptor, ObjectMapper mapper, Class<I> inputCarrierType) {
        this.descriptor = descriptor;
        this.mapper = mapper;
        this.inputCarrierType = inputCarrierType;
    }

    /**
     * Returns the immutable descriptor published for this tool.
     *
     * @return the descriptor built once during composition
     */
    public McpToolDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Materializes the typed input carrier from the server-validated, post-processing argument tree.
     *
     * @param normalizedArguments the schema-validated and input-processed argument tree
     * @return the materialized input carrier
     */
    public I materializeArguments(Map<String, Object> normalizedArguments) {
        Objects.requireNonNull(normalizedArguments, "normalizedArguments");
        return mapper.convertValue(normalizedArguments, inputCarrierType);
    }

    /**
     * Normalizes a structured tool result into the one bounded JSON-compatible value later reused for
     * output-schema validation, canonical text, observation, and envelope encoding.
     *
     * @param value the application structured value, or {@code null} when the result carries none
     * @return the normalized JSON-compatible value, or {@code null} when {@code value} is {@code null}
     */
    public @Nullable Object normalizeStructuredContent(@Nullable Object value) {
        return value == null ? null : mapper.convertValue(value, Object.class);
    }
}
