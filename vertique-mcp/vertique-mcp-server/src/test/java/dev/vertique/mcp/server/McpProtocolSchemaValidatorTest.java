// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the compiled official-method schemas retain the pinned {@code tools/call.arguments} contract. */
class McpProtocolSchemaValidatorTest {

    private static final String SCHEMA_RESOURCE = "/mcp/schema/2026-07-28/schema.json";
    private static final ObjectMapper CONVERTER = JsonMapper.builder().build();
    private static final JsonSchemaOptions SENSITIVITY_SCHEMA_OPTIONS = new JsonSchemaOptions()
            .setDraft(Draft.DRAFT202012)
            .setBaseUri("https://vertique.test/r15-sensitivity/")
            .setOutputFormat(OutputFormat.Basic);

    /**
     * R15 TP-003. The first assertion drives the production validator, which loads and compiles the
     * packaged official schema. The second assertion is a sensitivity control: it mutates that same
     * packaged document in memory to accept a scalar and proves the corresponding compiled validator
     * would accept the request. Therefore, weakening the packaged constraint makes the first assertion
     * red; this is not a second hand-authored schema that can drift independently.
     */
    @Test
    @DisplayName("R15: the pinned tools/call schema rejects scalar arguments and detects a weakening mutation")
    void shouldCompileThePinnedOfficialMethodSchemasWithoutMutation() {
        JsonNode scalarArguments = nonObjectArguments();

        McpProtocolSchemaValidator pinnedValidator = new McpProtocolSchemaValidator();

        assertThat(pinnedValidator.isValid("tools/call", scalarArguments))
                .as("the packaged official tools/call schema must reject scalar arguments")
                .isFalse();

        ObjectNode locallyMutatedPinnedSchema = loadPinnedSchemaTree();
        ObjectNode properties = (ObjectNode) locallyMutatedPinnedSchema
                .path("$defs")
                .path("CallToolRequestParams")
                .path("properties");
        properties.set("arguments", CONVERTER.createObjectNode());
        JsonNode mutatedArguments = properties.path("arguments");
        assertThat(mutatedArguments.isObject())
                .as("the sensitivity document must replace arguments with an object node")
                .isTrue();
        assertThat(mutatedArguments.size())
                .as("the sensitivity document's replacement arguments schema must be unconstrained {}")
                .isZero();
        locallyMutatedPinnedSchema.put("$ref", "#/$defs/CallToolRequestParams");
        Validator scalarAcceptingValidator = Validator.create(
                JsonSchema.of(new JsonObject(locallyMutatedPinnedSchema.toString())), SENSITIVITY_SCHEMA_OPTIONS);

        OutputUnit scalarValidation =
                scalarAcceptingValidator.validate(CONVERTER.convertValue(scalarArguments, Object.class));
        assertThat(scalarValidation.getValid())
                .as(
                        "SENSITIVITY: removing the packaged arguments constraint must accept the scalar:%n%s",
                        scalarValidation.toJson().encodePrettily())
                .isTrue();
    }

    private static JsonNode nonObjectArguments() {
        ObjectNode meta = CONVERTER.createObjectNode();
        meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
        meta.set("io.modelcontextprotocol/clientCapabilities", CONVERTER.createObjectNode());

        ObjectNode params = CONVERTER.createObjectNode();
        params.set("_meta", meta);
        params.put("name", "example.tool");
        params.put("arguments", "not-an-object");
        return params;
    }

    private static ObjectNode loadPinnedSchemaTree() {
        try (InputStream schemaStream = McpProtocolSchemaValidatorTest.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            assertThat(schemaStream).as("packaged official MCP schema").isNotNull();
            JsonNode schema = CONVERTER.readTree(schemaStream);
            assertThat(schema).as("packaged official MCP schema document").isInstanceOf(ObjectNode.class);
            return (ObjectNode) schema;
        } catch (IOException loadFailure) {
            throw new UncheckedIOException(loadFailure);
        }
    }
}
