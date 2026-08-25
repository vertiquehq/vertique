// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.json.JacksonFieldNameResolver;
import dev.vertique.mcp.tool.McpStructuredOutputWriter;
import dev.vertique.mcp.tool.McpToolDescriptor;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

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
public final class McpToolRuntime<I> implements McpStructuredOutputWriter {

    private final McpToolDescriptor descriptor;
    private final ObjectMapper mapper;
    private final ObjectWriter structuredOutputWriter;
    private final Class<I> inputCarrierType;
    private final InputFieldNameResolver fieldNameResolver;

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
        this.structuredOutputWriter = mapper.writer().without(JsonWriteFeature.WRITE_NAN_AS_STRINGS);
        this.inputCarrierType = inputCarrierType;
        this.fieldNameResolver = JacksonFieldNameResolver.forMapper(mapper);
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
     * The wire property name the generated {@code OptionalProbe} canary's sole component carries
     * (contract §4.1).
     */
    private static final String OPTIONAL_PROBE_PROPERTY = "value";

    /** The value the "present" probe case supplies, and the exact value a correct mapper must return. */
    private static final String OPTIONAL_PROBE_PRESENT_VALUE = "vertique-mcp-optional-probe";

    /**
     * Behaviorally proves that this tool's effective JSON profile mapper materializes
     * {@code Optional<T>} the way an {@code Optional<T>} tool parameter's contract requires (contract
     * §4.1): an omitted property and an explicit JSON {@code null} both resolve to {@code
     * Optional.empty()}, and a present property resolves to {@code Optional.of(value)}.
     *
     * <p>Called once, during composition, from the constructor of every generated invoker whose tool
     * declares at least one {@code Optional<T>} parameter, against the generated same-package {@code
     * OptionalProbe} canary record — never inferred from which module ids are registered. A profile
     * whose mapper cannot materialize {@code Optional} correctly fails composition here, before the
     * tool ever mounts, rather than surfacing as silently-wrong {@code Optional} arguments at request
     * time.
     *
     * @param <P> the generated {@code OptionalProbe} carrier type
     * @param probeType the generated {@code OptionalProbe} class, materialized through this runtime's
     *     effective mapper exactly as the tool's real {@code Input} carrier is; must not be
     *     {@code null}
     * @param valueAccessor the probe's generated {@code value()} accessor; must not be {@code null}
     * @throws ConfigurationException if the effective profile mapper does not materialize
     *     {@code Optional<T>} correctly for the omitted, explicit-null, or present case
     */
    public <P> void verifyOptionalMaterialization(Class<P> probeType, Function<P, Optional<?>> valueAccessor) {
        Objects.requireNonNull(probeType, "probeType");
        Objects.requireNonNull(valueAccessor, "valueAccessor");
        try {
            requireEmpty(valueAccessor.apply(mapper.convertValue(Map.of(), probeType)), "an omitted");

            Map<String, Object> explicitNullArguments = new HashMap<>();
            explicitNullArguments.put(OPTIONAL_PROBE_PROPERTY, null);
            requireEmpty(
                    valueAccessor.apply(mapper.convertValue(explicitNullArguments, probeType)), "an explicit-null");

            Optional<?> present = valueAccessor.apply(
                    mapper.convertValue(Map.of(OPTIONAL_PROBE_PROPERTY, OPTIONAL_PROBE_PRESENT_VALUE), probeType));
            if (!present.equals(Optional.of(OPTIONAL_PROBE_PRESENT_VALUE))) {
                throw materializationFailure("a present Optional<T> argument materialized as " + present
                        + " instead of Optional.of(\"" + OPTIONAL_PROBE_PRESENT_VALUE + "\")");
            }
        } catch (ConfigurationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ConfigurationException(
                    materializationFailureMessage(
                            "Optional<T> materialization threw " + e.getClass().getName() + ": " + e.getMessage()),
                    e);
        }
    }

    private void requireEmpty(@Nullable Optional<?> result, String caseLabel) {
        if (result == null || result.isPresent()) {
            throw materializationFailure(
                    caseLabel + " Optional<T> argument materialized as " + result + " instead of Optional.empty()");
        }
    }

    private ConfigurationException materializationFailure(String detail) {
        return new ConfigurationException(materializationFailureMessage(detail));
    }

    private String materializationFailureMessage(String detail) {
        return "MCP tool '" + descriptor.name() + "': the effective JSON profile mapper cannot materialize "
                + "Optional<T> arguments correctly (" + detail + "). Optional-materialization capability is "
                + "proven behaviorally, never inferred from registered module ids (contract §4.1).";
    }

    /**
     * Returns the wire-to-Java field name projection of this tool's effective profile mapper (contract
     * §4.7 point 2), composed once during composition and reused for every request.
     *
     * <p>The generated invoker's {@code prepare()} passes this resolver to {@code
     * InputObjectProcessor#processInput} and, once during composition, to {@code
     * InputObjectProcessor#precomputeFieldNameResolution} for the input carrier type — the same
     * resolver instance both times, so its per-type projection cache is composed exactly once.
     *
     * @return the effective profile's field name resolver; never {@code null}
     */
    public InputFieldNameResolver fieldNameResolver() {
        return fieldNameResolver;
    }

    /**
     * Writes a structured tool result through this runtime's stable effective-profile mapper.
     *
     * <p>The dispatcher owns and supplies the bounded destination, then reparses only the produced
     * bytes for validation, observation, and envelope encoding. This runtime never exposes its mapper
     * or materializes an unbounded intermediate tree.
     */
    @Override
    public void write(Object value, OutputStream destination) throws IOException {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(destination, "destination");
        structuredOutputWriter.writeValue(destination, value);
    }
}
