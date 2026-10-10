// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the JSON-RPC codes the server defines for itself against the codes the vendored protocol
 * schema defines, so two different conditions can never share one wire code.
 *
 * <p>The schema's own codes are read from the vendored schema file rather than spelled out here, so
 * a schema that gains a code the server already uses fails this test instead of silently colliding.
 */
class McpWireCodeDistinctnessTest {

    private static final String SCHEMA_RESOURCE = "/mcp/schema/2026-07-28/schema.json";

    /** The JSON-RPC server-error range an implementation may define its own codes in. */
    private static final int SERVER_ERROR_RANGE_MIN = -32099;

    private static final int SERVER_ERROR_RANGE_MAX = -32000;

    /** The pre-dispatch request-interceptor rejection code: server-defined, absent from the schema. */
    private static final int INTERCEPTOR_REJECTED = -32001;

    /** The wire value of the rate-limit code; clients switch on it, so a change must fail here. */
    private static final int RATE_LIMITED_WIRE_VALUE = -32010;

    @Test
    @DisplayName("the unsupported-version code is the schema's UnsupportedProtocolVersionError code")
    void shouldUseTheSchemaCodeForAnUnsupportedProtocolVersion() throws IOException {
        JsonNode schema = schema();

        int schemaCode = codeOf(schema, "UnsupportedProtocolVersionError");

        assertThat(schemaCode).isEqualTo(-32022);
        assertThat(McpProtocolCodec.UNSUPPORTED_PROTOCOL_VERSION).isEqualTo(schemaCode);
    }

    @Test
    @DisplayName("the rate-limit code is a server-defined code the schema does not define")
    void shouldKeepTheRateLimitCodeOutsideEverySchemaDefinedCode() throws IOException {
        Set<Integer> schemaCodes = schemaCodes(schema());

        assertThat(schemaCodes)
                .as("the schema defines its error codes as constants, so the set is not empty")
                .contains(-32020, -32021, -32022, -32700, -32600, -32601, -32602, -32603);
        assertThat(McpRequestDispatcher.RATE_LIMITED)
                .as("the rate-limit code must not be any code the schema defines")
                .isNotIn(schemaCodes)
                .isBetween(SERVER_ERROR_RANGE_MIN, SERVER_ERROR_RANGE_MAX)
                .isEqualTo(RATE_LIMITED_WIRE_VALUE);
    }

    @Test
    @DisplayName("the unsupported-version, rate-limit and interceptor-rejection codes are pairwise distinct")
    void shouldNotShareAWireCodeBetweenDifferentConditions() {
        assertThat(McpProtocolCodec.UNSUPPORTED_PROTOCOL_VERSION).isNotEqualTo(McpRequestDispatcher.RATE_LIMITED);
        assertThat(McpRequestDispatcher.RATE_LIMITED).isNotEqualTo(INTERCEPTOR_REJECTED);
        assertThat(McpProtocolCodec.UNSUPPORTED_PROTOCOL_VERSION).isNotEqualTo(INTERCEPTOR_REJECTED);
    }

    @Test
    @DisplayName("the server-defined interceptor-rejection code is not a schema-defined code either")
    void shouldKeepTheInterceptorRejectionCodeOutsideEverySchemaDefinedCode() throws IOException {
        assertThat(schemaCodes(schema())).doesNotContain(INTERCEPTOR_REJECTED);
    }

    private static int codeOf(JsonNode schema, String definition) {
        JsonNode code = null;
        for (JsonNode part : schema.at("/$defs/" + definition + "/properties/error/allOf")) {
            JsonNode candidate = part.at("/properties/code/const");
            if (candidate.isInt()) {
                code = candidate;
            }
        }
        assertThat(code).as(definition + " must define a constant error code").isNotNull();
        return code.intValue();
    }

    /** Every integer constant the schema assigns to a {@code code} property, wherever it appears. */
    private static Set<Integer> schemaCodes(JsonNode schema) {
        Set<Integer> codes = new TreeSet<>();
        collectCodes(schema, codes);
        return codes;
    }

    private static void collectCodes(JsonNode node, Set<Integer> codes) {
        if (node.isObject()) {
            JsonNode constant = node.path("code").path("const");
            if (constant.isInt()) {
                codes.add(constant.intValue());
            }
        }
        node.forEach(child -> collectCodes(child, codes));
    }

    private static JsonNode schema() throws IOException {
        try (InputStream input = McpWireCodeDistinctnessTest.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            assertThat(input).as("vendored schema resource " + SCHEMA_RESOURCE).isNotNull();
            return new ObjectMapper().readTree(input);
        }
    }
}
