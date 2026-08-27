// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * T009 — effective profile selection drives schema generation, end to end through
 * {@link McpToolRuntimeFactory#create}.
 *
 * <p>{@link McpJsonProfileBindingTest} (T002) already proves profile <em>resolution</em> and that
 * the runtime binding retains the resolved profile's exact stable mapper. This test proves the
 * remaining T009 half: the resolved profile is not just retained for materialization, it is the one
 * threaded into {@code AnnotationJsonSchemaGenerator.forInputProfile(...)}, so two tools declaring
 * different profiles over the exact same input carrier type publish two different input schemas.
 */
@DisplayName("MCP JSON profile selection — T009 schema-generation binding")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpJsonProfileIT {

    record AmountInput(@JsonProperty("amount") BigDecimal argument0) {}

    @Test
    @DisplayName("shouldGenerateDifferentSchemasForTheSameCarrierUnderDifferentDeclaredProfiles")
    void shouldGenerateDifferentSchemasForTheSameCarrierUnderDifferentDeclaredProfiles() {
        // --- Given: a factory over the framework vertx profile plus a registered profile whose
        // BigDecimal override republishes it as a wire string ---
        McpToolRuntimeFactory factory = McpJsonProfileITFixture.factory();
        List<McpToolParameterMetadata> parameters =
                List.of(new McpToolParameterMetadata("argument0", "amount", "The amount."));

        // --- When: create() is called for the exact same carrier type under each declared profile ---
        McpToolDescriptor defaultProfileDescriptor = factory.create(
                        "amount.default",
                        null,
                        "Uses the default profile.",
                        new McpToolAnnotations(false, false, true, false),
                        AmountInput.class,
                        null,
                        parameters,
                        null,
                        new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null))
                .descriptor();
        McpToolDescriptor overrideProfileDescriptor = factory.create(
                        "amount.override",
                        null,
                        "Uses the string-override profile.",
                        new McpToolAnnotations(false, false, true, false),
                        AmountInput.class,
                        null,
                        parameters,
                        JsonProfileId.of(McpJsonProfileITFixture.STRING_OVERRIDE_PROFILE_ID),
                        new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null))
                .descriptor();

        // --- Then: the default profile's schema represents BigDecimal as a JSON number ---
        JsonNode defaultSchema = McpCanonicalJsonWriter.read(defaultProfileDescriptor.inputSchema());
        assertThat(defaultSchema.at("/properties/amount/type").asText())
                .as("the default profile declares no override, so BigDecimal is a number")
                .isEqualTo("number");

        // --- Then (decisive): the declared override profile's schema represents the exact same Java
        // property as a JSON string instead ---
        JsonNode overrideSchema = McpCanonicalJsonWriter.read(overrideProfileDescriptor.inputSchema());
        assertThat(overrideSchema.at("/properties/amount/type").asText())
                .as("the declared profile's BigDecimal override must republish amount as a string")
                .isEqualTo("string");
    }
}
