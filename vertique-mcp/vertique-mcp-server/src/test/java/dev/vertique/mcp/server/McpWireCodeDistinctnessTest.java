// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins every JSON-RPC code the server puts on the wire against the codes the vendored protocol
 * schema defines, so two different conditions can never share one wire code and a server-defined
 * code can never collide with a schema-defined one.
 *
 * <p>The schema's own codes are read from the vendored schema file rather than spelled out here, so
 * a schema that gains a code the server already uses fails this test instead of silently colliding.
 * The server's codes are read from the production constants, so a constant that is changed to a
 * colliding value fails it too.
 */
class McpWireCodeDistinctnessTest {

    private static final String SCHEMA_RESOURCE = "/mcp/schema/2026-07-28/schema.json";

    /** The JSON-RPC server-error range an implementation may define its own codes in. */
    private static final int SERVER_ERROR_RANGE_MIN = -32099;

    private static final int SERVER_ERROR_RANGE_MAX = -32000;

    /** The wire value of the rate-limit code; clients switch on it, so a change must fail here. */
    private static final int RATE_LIMITED_WIRE_VALUE = -32010;

    /**
     * Every condition the server reports with a code the schema defines, by the schema definition
     * that owns the code. Each production constant must equal that definition's code.
     */
    private static Map<String, Integer> schemaDefinedCodes() {
        Map<String, Integer> codes = new LinkedHashMap<>();
        codes.put("ParseError", McpProtocolCodec.PARSE_ERROR);
        codes.put("InvalidRequestError", McpProtocolCodec.INVALID_REQUEST);
        codes.put("MethodNotFoundError", McpProtocolCodec.METHOD_NOT_FOUND);
        codes.put("InvalidParamsError", McpProtocolCodec.INVALID_PARAMS);
        codes.put("InternalError", McpProtocolCodec.INTERNAL_ERROR);
        codes.put("HeaderMismatchError", McpProtocolCodec.NEGOTIATION_MISMATCH);
        codes.put("UnsupportedProtocolVersionError", McpProtocolCodec.UNSUPPORTED_PROTOCOL_VERSION);
        codes.put("MissingRequiredClientCapabilityError", McpRequestDispatcher.MISSING_REQUIRED_CLIENT_CAPABILITY);
        return codes;
    }

    /** Every condition the server reports with a code of its own, which the schema must not define. */
    private static Map<String, Integer> serverDefinedCodes() {
        Map<String, Integer> codes = new LinkedHashMap<>();
        codes.put("request interceptor rejection", McpRequestDispatcher.INTERCEPTOR_REJECTED);
        codes.put("rate limit", McpRequestDispatcher.RATE_LIMITED);
        return codes;
    }

    @Test
    @DisplayName("every schema-defined code the server uses equals the code of its schema definition")
    void shouldUseTheSchemaCodeForEveryConditionTheSchemaDefines() throws IOException {
        JsonNode schema = schema();

        schemaDefinedCodes().forEach((definition, code) -> assertThat(code)
                .as("the server's code for " + definition)
                .isEqualTo(codeOf(schema, definition)));
    }

    @Test
    @DisplayName("every server-defined code is outside the schema's codes and inside the server-error range")
    void shouldKeepEveryServerDefinedCodeOutsideEverySchemaDefinedCode() throws IOException {
        Set<Integer> schemaCodes = schemaCodes(schema());

        assertThat(schemaCodes)
                .as("the schema defines its error codes as constants, so the set is not empty")
                .containsAll(schemaDefinedCodes().values());
        serverDefinedCodes().forEach((condition, code) -> assertThat(code)
                .as("the server-defined code for " + condition)
                .isNotIn(schemaCodes)
                .isBetween(SERVER_ERROR_RANGE_MIN, SERVER_ERROR_RANGE_MAX));
    }

    @Test
    @DisplayName("no two different conditions share a wire code")
    void shouldNotShareAWireCodeBetweenDifferentConditions() {
        Map<String, Integer> conditions = new LinkedHashMap<>(schemaDefinedCodes());
        conditions.putAll(serverDefinedCodes());

        assertThat(Set.copyOf(conditions.values()))
                .as("every condition has its own code: " + conditions)
                .hasSameSizeAs(conditions.values());
    }

    @Test
    @DisplayName("the dispatcher's own copies of the codec's codes have the same values")
    void shouldKeepTheDispatcherCopiesEqualToTheCodecCodes() {
        assertThat(McpRequestDispatcher.PARSE_ERROR).isEqualTo(McpProtocolCodec.PARSE_ERROR);
        assertThat(McpRequestDispatcher.INVALID_REQUEST).isEqualTo(McpProtocolCodec.INVALID_REQUEST);
        assertThat(McpRequestDispatcher.METHOD_NOT_FOUND).isEqualTo(McpProtocolCodec.METHOD_NOT_FOUND);
        assertThat(McpRequestDispatcher.INTERNAL_ERROR).isEqualTo(McpProtocolCodec.INTERNAL_ERROR);
        assertThat(McpRequestDispatcher.NEGOTIATION_MISMATCH).isEqualTo(McpProtocolCodec.NEGOTIATION_MISMATCH);
        assertThat(McpPolicyEnforcer.UNKNOWN_OR_UNAUTHORIZED_CODE).isEqualTo(McpProtocolCodec.INVALID_PARAMS);
    }

    @Test
    @DisplayName("the rate-limit code keeps its documented wire value")
    void shouldKeepTheDocumentedRateLimitWireValue() {
        assertThat(McpRequestDispatcher.RATE_LIMITED).isEqualTo(RATE_LIMITED_WIRE_VALUE);
    }

    /** The one constant code the schema's {@code definition} assigns, wherever it sits inside it. */
    private static int codeOf(JsonNode schema, String definition) {
        JsonNode node = schema.at("/$defs/" + definition);
        assertThat(node.isMissingNode()).as("schema definition " + definition).isFalse();
        Set<Integer> codes = new TreeSet<>();
        collectCodes(node, codes);
        assertThat(codes)
                .as(definition + " must define exactly one constant error code")
                .hasSize(1);
        return codes.iterator().next();
    }

    /**
     * Every integer the schema assigns to a {@code code} property as a {@code const} or as an
     * {@code enum} member, wherever it appears.
     */
    private static Set<Integer> schemaCodes(JsonNode schema) {
        Set<Integer> codes = new TreeSet<>();
        collectCodes(schema, codes);
        return codes;
    }

    private static void collectCodes(JsonNode node, Set<Integer> codes) {
        if (node.isObject()) {
            JsonNode code = node.path("code");
            JsonNode constant = code.path("const");
            if (constant.isInt()) {
                codes.add(constant.intValue());
            }
            code.path("enum").forEach(member -> {
                if (member.isInt()) {
                    codes.add(member.intValue());
                }
            });
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
