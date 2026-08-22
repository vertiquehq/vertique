// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.mcp.tool.McpToolDescriptor;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.Validator;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T009 TP-003 — one compiled validator set per Vert.x {@link Context}.
 *
 * <p>Given two independently created Vert.x contexts (two independent {@link Vertx} instances, each
 * providing its own context), each composes a fresh {@link McpSchemaRegistry} on its own context
 * from the same two-tool descriptor registry ({@code weather.lookup}, {@code clock.now}). Compiling
 * happens exactly once per context, and both contexts validate the same argument document.
 *
 * <p><strong>Deviation from the task's stated test location:</strong> this class lives in
 * {@code dev.vertique.mcp.server}, not {@code dev.vertique.mcp.server.runtime}, because
 * {@link McpSchemaRegistry} is package-private per the frozen artifact inventory and is not
 * consumer-facing public API (see {@code McpServerInventoryGuardTest}'s progressive public-surface
 * guard) — package-private access requires the exact same package as the type under test.
 */
@DisplayName("MCP validator concurrency — T009 TP-003")
class McpValidatorConcurrencyTest {

    private final Vertx vertxA = Vertx.vertx();
    private final Vertx vertxB = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        McpValidatorConcurrencyTestFixture.closeAndAwait(vertxA);
        McpValidatorConcurrencyTestFixture.closeAndAwait(vertxB);
    }

    @Test
    @DisplayName("shouldCompileOneValidatorSetPerVertxContext")
    void shouldCompileOneValidatorSetPerVertxContext() throws Exception {
        // --- Given: two independently created Vert.x contexts, and the same two-tool registry ---
        Context contextA = vertxA.getOrCreateContext();
        Context contextB = vertxB.getOrCreateContext();
        Map<String, McpToolDescriptor> descriptors = McpValidatorConcurrencyTestFixture.twoToolDescriptors();

        // --- When: compile validators once per context ---
        McpSchemaRegistry registryA = McpValidatorConcurrencyTestFixture.compileOnContext(contextA, descriptors);
        McpSchemaRegistry registryB = McpValidatorConcurrencyTestFixture.compileOnContext(contextB, descriptors);

        Validator weatherValidatorA = registryA.inputValidator("weather.lookup");
        Validator weatherValidatorB = registryB.inputValidator("weather.lookup");
        Validator clockValidatorA = registryA.inputValidator("clock.now");
        Validator clockValidatorB = registryB.inputValidator("clock.now");

        JsonObject validWeatherArguments = new JsonObject().put("city", "Helsinki");
        JsonObject validClockArguments = new JsonObject();

        // --- Then: both contexts accept the same valid argument document ---
        OutputUnit weatherResultA = weatherValidatorA.validate(validWeatherArguments);
        OutputUnit weatherResultB = weatherValidatorB.validate(validWeatherArguments);
        assertThat(weatherResultA.getValid())
                .as("context A's weather.lookup validator must accept it")
                .isTrue();
        assertThat(weatherResultB.getValid())
                .as("context B's weather.lookup validator must accept it")
                .isTrue();
        assertThat(clockValidatorA.validate(validClockArguments).getValid())
                .as("context A's clock.now validator must accept its valid document")
                .isTrue();
        assertThat(clockValidatorB.validate(validClockArguments).getValid())
                .as("context B's clock.now validator must accept its valid document")
                .isTrue();

        // --- Then (decisive): no validator instance is shared across the two contexts ---
        assertThat(weatherValidatorA)
                .as("each context must own its own compiled weather.lookup validator instance")
                .isNotSameAs(weatherValidatorB);
        assertThat(clockValidatorA)
                .as("each context must own its own compiled clock.now validator instance")
                .isNotSameAs(clockValidatorB);
    }
}
