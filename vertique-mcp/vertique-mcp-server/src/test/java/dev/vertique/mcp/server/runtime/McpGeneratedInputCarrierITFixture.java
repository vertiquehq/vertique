// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import dev.vertique.core.json.JsonProfileCodec;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonProfileRegistry;
import dev.vertique.json.DefaultJsonProfileRegistry;
import dev.vertique.json.Jackson2JsonProfileCodec;
import dev.vertique.json.JsonConfig;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.mcp.server.McpServerConfig;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import java.util.List;
import java.util.Set;

/**
 * Framework wiring for {@link McpGeneratedInputCarrierIT}: a real
 * {@link DefaultJsonProfileRegistry} composed with one application-registered {@code strict}
 * profile alongside the always-present framework {@code vertx} profile, a real
 * {@link McpJsonProfileResolver} resolving the effective profile exactly as composition does, and
 * direct {@link McpToolRuntime} construction through its package-private constructor — the same
 * construction path {@code McpToolRuntimeFactory} uses.
 *
 * <p>Nothing here decides Given values or asserts outcomes — that stays in the test method.
 */
final class McpGeneratedInputCarrierITFixture {

    private McpGeneratedInputCarrierITFixture() {}

    /**
     * Builds a resolver over the framework {@code vertx}/{@code vertique} profiles plus one
     * registered {@code strict} profile whose mapper supports JDK8 {@code Optional} materialization
     * and Vert.x JSON types (so it passes the registry's structural round-trip probe), with no
     * configured MCP-boundary or global default (the tail is the {@code vertique} profile — not the
     * reserved {@code vertx} profile).
     *
     * @return the composed resolver
     */
    static McpJsonProfileResolver resolver() {
        JsonProfileRegistry registry = new DefaultJsonProfileRegistry(Set.of(new StrictProfile()));
        McpServerConfig mcpConfig =
                McpServerConfig.builder().enabled(true).jsonProfile(null).build();
        return new McpJsonProfileResolver(registry, JsonConfig.defaults(), mcpConfig);
    }

    /**
     * Builds the runtime binding a generated invoker would obtain for the resolved profile, via the
     * same package-private construction path {@code McpToolRuntimeFactory} uses.
     *
     * @param profile          the resolved effective profile
     * @param inputCarrierType the generated-shaped input carrier record type
     * @param <I>              the carrier type
     * @return the runtime binding
     */
    static <I> McpToolRuntime<I> runtime(JsonProfileCodec profile, Class<I> inputCarrierType) {
        McpToolDescriptor descriptor = new McpToolDescriptor(
                "identity.register",
                null,
                "Register an address or nickname.",
                new McpToolAnnotations(false, false, true, false),
                "{\"type\":\"object\"}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
        return new McpToolRuntime<>(descriptor, Jackson2JsonProfileCodec.objectMapper(profile), inputCarrierType);
    }

    /**
     * The registered {@code strict} application profile: JDK8 {@link Jdk8Module} for real
     * {@code Optional} materialization, plus Vert.x's Jackson support so the registry's structural
     * round-trip probe (which round-trips a {@code JsonObject}/{@code JsonArray}) passes.
     */
    private static final class StrictProfile extends Jackson2JsonProfileCodec {

        private final ObjectMapper mapper =
                new ObjectMapper().registerModule(new Jdk8Module()).registerModule(VertxJsonSupport.module());

        @Override
        public JsonProfileId id() {
            return JsonProfileId.of("strict");
        }

        @Override
        protected ObjectMapper mapper() {
            return mapper;
        }
    }
}
