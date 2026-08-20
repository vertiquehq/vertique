// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonConfig;
import dev.vertique.mcp.server.McpServerConfig;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolInvoker;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The sole construction path for generated tool descriptors and their profile bindings.
 *
 * <p>One generated invoker obtains exactly one {@link McpToolRuntime} from this factory during
 * application composition. The factory resolves the effective JSON profile once, proves the selected
 * mapper safe for remote input, and returns an immutable binding that privately retains the exact
 * stable mapper. No profile lookup, generator construction, or descriptor assembly happens on the
 * request path. Application code neither calls nor implements this type; its injected constructor is
 * package-private so Dagger can create the binding without adding an application-callable
 * construction path.
 */
@Singleton
public final class McpToolRuntimeFactory {

    private final McpJsonProfileResolver profileResolver;
    private final McpJsonProfileSafetyValidator safetyValidator;

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
        this.safetyValidator = new McpJsonProfileSafetyValidator();
    }

    /**
     * Builds the one immutable runtime binding of a generated tool.
     *
     * <p>The effective JSON profile is resolved once and its mapper is proven safe for remote input
     * before any schema exists. Binding the proven profile to the profile-aware schema generator, and
     * with it the returned {@link McpToolRuntime}, lands with the schema slice; until then this method
     * performs the composition-time checks and then reports the unbound step.
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
        JsonMapperProfile effectiveProfile = profileResolver.resolve(declaredJsonProfile);
        safetyValidator.validate(effectiveProfile, inputCarrierType, structuredOutputType);
        throw new UnsupportedOperationException(
                "MCP tool schema generation is bound to the profile-aware schema generator in a later slice");
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
