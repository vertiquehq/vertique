// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonConfig;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import dev.vertique.mcp.server.McpServerConfig;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sole construction path for generated tool descriptors and their profile bindings.
 *
 * <p>One generated invoker obtains exactly one {@link McpToolRuntime} from this factory during
 * application composition. The factory resolves the effective JSON profile once and returns an
 * immutable binding that privately retains the exact stable mapper. No profile lookup, generator
 * construction, or descriptor assembly happens on the request path. Application code neither calls
 * nor implements this type; its injected constructor is package-private so Dagger can create the
 * binding without adding an application-callable construction path.
 *
 * <p>The framework does not statically prove the selected mapper safe for remote input: a profile
 * exposes an application-owned {@link com.fasterxml.jackson.databind.ObjectMapper}, and the
 * framework-shipped profiles are safe by default. The unsafe mapper configurations an
 * application-supplied profile must not enable for a remotely reachable tool are documented in the
 * module reference, not enforced here.
 *
 * <p>Schema construction (T009): for each distinct effective profile used by a tool, this factory
 * creates or reuses one {@link AnnotationJsonSchemaGenerator#forInputProfile(JsonMapperProfile)}
 * and, when a structured output type is declared, one
 * {@link AnnotationJsonSchemaGenerator#forOutputProfile(JsonMapperProfile)} — never
 * {@link AnnotationJsonSchemaGenerator#withVictoolsDefaults()}, and never a directly configured
 * Victools instance. The generated input schema is then hardened at the protocol argument-object
 * boundary by {@link McpSchemaHardener} and re-serialized deterministically by
 * {@link McpCanonicalJsonWriter}; a declared structured-output schema is published exactly as
 * JSON-005 generates it, since output is server-produced and carries no argument-object boundary to
 * close. None of this happens on the request path: it runs exactly once per tool, here, during
 * composition.
 */
@Singleton
public final class McpToolRuntimeFactory {

    private final McpJsonProfileResolver profileResolver;

    /**
     * One profile-aware input-direction generator per distinct effective {@link JsonProfileId},
     * created or reused across every tool sharing that profile, never rebuilt per tool.
     */
    private final Map<JsonProfileId, AnnotationJsonSchemaGenerator> inputGeneratorsByProfile =
            new ConcurrentHashMap<>();

    /** The output-direction counterpart of {@link #inputGeneratorsByProfile}. */
    private final Map<JsonProfileId, AnnotationJsonSchemaGenerator> outputGeneratorsByProfile =
            new ConcurrentHashMap<>();

    /**
     * Binds the factory to the registries and configuration that select an effective profile.
     *
     * @param profiles the registry of every discovered JSON mapper profile
     * @param jsonConfig the global JSON configuration carrying {@code json.jsonProfile}
     * @param mcpConfig the MCP server configuration carrying {@code mcp.jsonProfile}
     */
    @Inject
    McpToolRuntimeFactory(JsonMapperProfileRegistry profiles, JsonConfig jsonConfig, McpServerConfig mcpConfig) {
        this.profileResolver = new McpJsonProfileResolver(profiles, jsonConfig, mcpConfig);
    }

    /**
     * Builds the one immutable runtime binding of a generated tool.
     *
     * <p>The effective JSON profile is resolved once, JSON-005's profile-aware generator contract
     * builds the canonical input (and, when declared, output) schema, MCP's own hardening and
     * canonical re-serialization apply to the input schema, and the resulting descriptor and stable
     * mapper are bound into the returned, immutable runtime.
     *
     * @param <I> the generated input-carrier record type
     * @param name the unique published tool name
     * @param title the optional display title, or {@code null} when the tool declares none
     * @param description the published tool description
     * @param annotations the published behavior hints
     * @param inputCarrierType the generated input-carrier record type
     * @param structuredOutputType the declared structured-output type, or {@code null} when the tool
     *     returns text only
     * @param parameters the position-stable declared-parameter metadata
     * @param declaredJsonProfile the method- or type-declared profile id, or {@code null} when the
     *     tool declares none
     * @param access the resolved authorization policy
     * @return the immutable schema-and-mapper binding for this tool
     * @throws ConfigurationException if two parameters declare the same external name
     */
    public <I> McpToolRuntime<I> create(
            String name,
            @Nullable String title,
            String description,
            McpToolAnnotations annotations,
            Class<I> inputCarrierType,
            @Nullable Type structuredOutputType,
            List<McpToolParameterMetadata> parameters,
            @Nullable JsonProfileId declaredJsonProfile,
            McpToolAccess access) {
        Objects.requireNonNull(inputCarrierType, "inputCarrierType");
        List<McpToolParameterMetadata> parameterMetadata =
                List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        requireUniqueExternalNames(name, parameterMetadata);

        JsonMapperProfile profile = profileResolver.resolve(declaredJsonProfile);

        String hardenedInputSchema = McpCanonicalJsonWriter.writeCanonical(McpSchemaHardener.harden(
                McpCanonicalJsonWriter.read(inputGeneratorFor(profile).generateCanonical(inputCarrierType)),
                parameterMetadata));

        String outputSchema = structuredOutputType == null
                ? null
                : outputGeneratorFor(profile).generateCanonical(structuredOutputType);

        McpToolDescriptor descriptor =
                new McpToolDescriptor(name, title, description, annotations, hardenedInputSchema, outputSchema, access);
        return new McpToolRuntime<>(descriptor, profile.mapper(), inputCarrierType);
    }

    /**
     * Returns the shared input-direction generator for {@code profile}'s id, building it once on
     * first use.
     *
     * @param profile the resolved effective profile
     * @return the shared generator for this profile's input direction
     */
    private AnnotationJsonSchemaGenerator inputGeneratorFor(JsonMapperProfile profile) {
        return inputGeneratorsByProfile.computeIfAbsent(
                profile.id(), unusedId -> AnnotationJsonSchemaGenerator.forInputProfile(profile));
    }

    /**
     * Returns the shared output-direction generator for {@code profile}'s id, building it once on
     * first use.
     *
     * @param profile the resolved effective profile
     * @return the shared generator for this profile's output direction
     */
    private AnnotationJsonSchemaGenerator outputGeneratorFor(JsonMapperProfile profile) {
        return outputGeneratorsByProfile.computeIfAbsent(
                profile.id(), unusedId -> AnnotationJsonSchemaGenerator.forOutputProfile(profile));
    }

    /**
     * Rejects a parameter list carrying two entries with the same external name.
     *
     * @param toolName the owning tool's name, for the failure message
     * @param parameters the declared-parameter metadata
     * @throws ConfigurationException if a duplicate external name is found
     */
    private static void requireUniqueExternalNames(String toolName, List<McpToolParameterMetadata> parameters) {
        Set<String> seenExternalNames = new HashSet<>();
        for (McpToolParameterMetadata parameter : parameters) {
            if (!seenExternalNames.add(parameter.externalName())) {
                throw new ConfigurationException("Duplicate MCP tool parameter external name '"
                        + parameter.externalName() + "' for tool '" + toolName + "'");
            }
        }
    }

    /**
     * Builds the generated tool registry once from the explicitly contributed invoker set.
     *
     * <p>Only contributed invokers are registered — nothing is discovered by classpath scanning — and
     * each contribution is asked for its immutable descriptor exactly once. The result is keyed by
     * tool name in global name order, so the published order never depends on contribution order.
     *
     * @param contributedInvokers the generated {@code @IntoSet} invoker contributions
     * @return the immutable registry, keyed by tool name in global name order
     * @throws ConfigurationException if two contributions publish the same tool name; the message
     *     names the duplicate and no registry is produced
     */
    static Map<String, McpToolInvoker> buildGeneratedRegistry(Set<McpToolInvoker> contributedInvokers) {
        Objects.requireNonNull(contributedInvokers, "contributedInvokers");
        Map<String, McpToolInvoker> byName = new TreeMap<>();
        for (McpToolInvoker invoker : contributedInvokers) {
            String name = invoker.descriptor().name();
            McpToolInvoker previous = byName.putIfAbsent(name, invoker);
            if (previous != null) {
                throw new ConfigurationException(
                        "Duplicate MCP tool name '" + name + "': two generated invokers publish it");
            }
        }
        return Collections.unmodifiableMap(byName);
    }
}
