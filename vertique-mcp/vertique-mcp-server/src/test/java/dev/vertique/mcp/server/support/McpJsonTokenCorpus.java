// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.support;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads the sole R18 JSON token-budget corpus and deterministically renders each declared shape.
 *
 * <p>The resource bytes are loaded exactly once so the digest proof can pin the artifact rather than
 * a reserialized interpretation of it. Parsing also independently recounts Jackson parser tokens,
 * making a stale declared count a loading error instead of an assertion a consumer may overlook.
 */
public final class McpJsonTokenCorpus {

    public static final String RESOURCE_PATH = "mcp/characterization/json-token-budget-corpus.jsonl";
    public static final String INGRESS_UNIT_CONSUMER = "ingress-unit";
    public static final String INGRESS_INTEGRATION_CONSUMER = "ingress-integration";
    public static final String OUTPUT_NORMALIZATION_CONSUMER = "output-normalization";
    public static final String BOTH_CONFIGURED_DEFAULTS_CONSUMER = "both-configured-defaults";

    private static final Set<String> ROW_FIELDS = Set.of(
            "id",
            "structuralClasses",
            "consumers",
            "generator",
            "tokenCount",
            "configuredBudget",
            "expected",
            "maxBodyBytes");
    private static final Set<String> GENERATOR_FIELDS = Set.of("description", "parameters");
    private static final JsonMapper ROW_MAPPER = JsonMapper.builder().build();
    private static final byte[] RESOURCE_BYTES = readResourceBytes();
    private static final List<Row> ROWS = parseBytes(RESOURCE_BYTES);

    private McpJsonTokenCorpus() {}

    public static List<Row> rows() {
        return ROWS;
    }

    public static List<Row> ingressUnitRows() {
        return rowsForConsumer(INGRESS_UNIT_CONSUMER);
    }

    public static List<Row> ingressIntegrationRows() {
        return rowsForConsumer(INGRESS_INTEGRATION_CONSUMER);
    }

    public static List<Row> outputNormalizationRows() {
        return rowsForConsumer(OUTPUT_NORMALIZATION_CONSUMER);
    }

    public static List<Row> bothConfiguredDefaultsRows() {
        return rowsForConsumer(BOTH_CONFIGURED_DEFAULTS_CONSUMER);
    }

    public static List<Row> rowsForConsumer(String consumer) {
        return ROWS.stream().filter(row -> row.consumers().contains(consumer)).toList();
    }

    public static byte[] resourceBytes() {
        return RESOURCE_BYTES.clone();
    }

    /**
     * Strictly parses exact corpus bytes. Exposed only from this test-scope loader so its schema
     * rejection behavior can be proved without creating a second resource.
     */
    public static List<Row> parseBytes(byte[] bytes) {
        return parseRows(bytes.clone());
    }

    private static byte[] readResourceBytes() {
        try (InputStream input = McpJsonTokenCorpus.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                throw new IllegalStateException("Missing JSON token corpus resource: " + RESOURCE_PATH);
            }
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read JSON token corpus resource: " + RESOURCE_PATH, failure);
        }
    }

    private static List<Row> parseRows(byte[] bytes) {
        String text = decodeUtf8(bytes);
        List<Row> rows = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        String[] lines = text.split("\\R", -1);
        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            if (lineNumber == lines.length - 1 && lines[lineNumber].isEmpty()) {
                continue;
            }
            if (lines[lineNumber].isBlank()) {
                throw invalid(lineNumber, "blank lines are not JSONL rows");
            }
            Row row = parseRow(lines[lineNumber], lineNumber + 1);
            if (!ids.add(row.id())) {
                throw invalid(lineNumber + 1, "duplicate id: " + row.id());
            }
            rows.add(row);
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("JSON token corpus must contain at least one row");
        }
        return List.copyOf(rows);
    }

    private static Row parseRow(String line, int lineNumber) {
        JsonNode parsed;
        try {
            parsed = ROW_MAPPER.readTree(line);
        } catch (IOException malformed) {
            throw invalid(lineNumber, "malformed JSON", malformed);
        }
        if (!(parsed instanceof ObjectNode object)) {
            throw invalid(lineNumber, "row must be a JSON object");
        }
        rejectUnknownOrMissingFields(object, ROW_FIELDS, lineNumber, "row");
        String id = requiredText(object, "id", lineNumber);
        Set<String> structuralClasses = requiredStringSet(object, "structuralClasses", lineNumber);
        Set<String> consumers = requiredStringSet(object, "consumers", lineNumber);
        Generator generator = parseGenerator(object.required("generator"), lineNumber);
        int tokenCount = requiredPositiveInt(object, "tokenCount", lineNumber);
        int configuredBudget = requiredPositiveInt(object, "configuredBudget", lineNumber);
        Expected expected = Expected.parse(requiredText(object, "expected", lineNumber), lineNumber);
        int maxBodyBytes = requiredPositiveInt(object, "maxBodyBytes", lineNumber);
        byte[] rendered = render(generator, lineNumber);
        int actualTokenCount = countParserTokens(rendered);
        if (actualTokenCount != tokenCount) {
            throw invalid(
                    lineNumber,
                    "declared tokenCount " + tokenCount + " does not match rendered count " + actualTokenCount);
        }
        return new Row(
                id,
                structuralClasses,
                consumers,
                generator,
                tokenCount,
                configuredBudget,
                expected,
                maxBodyBytes,
                rendered);
    }

    private static Generator parseGenerator(JsonNode node, int lineNumber) {
        if (!(node instanceof ObjectNode object)) {
            throw invalid(lineNumber, "generator must be a JSON object");
        }
        rejectUnknownOrMissingFields(object, GENERATOR_FIELDS, lineNumber, "generator");
        String description = requiredText(object, "description", lineNumber);
        JsonNode parameters = object.required("parameters");
        if (!(parameters instanceof ObjectNode parameterObject)) {
            throw invalid(lineNumber, "generator parameters must be a JSON object");
        }
        return new Generator(
                description,
                Map.copyOf(ROW_MAPPER.convertValue(parameterObject, new TypeReference<Map<String, Object>>() {})));
    }

    private static byte[] render(Generator generator, int lineNumber) {
        Map<String, Object> parameters = generator.parameters();
        String rendered =
                switch (generator.description()) {
                    case "literal" -> requiredStringParameter(parameters, "json", lineNumber, generator.description());
                    case "flat-scalar-array" ->
                        flatScalarArray(requiredPositiveParameter(
                                parameters, "elementCount", lineNumber, generator.description()));
                    case "nested-array" ->
                        nestedArray(
                                requiredPositiveParameter(parameters, "depth", lineNumber, generator.description()));
                    case "nested-object" ->
                        nestedObject(
                                requiredPositiveParameter(parameters, "depth", lineNumber, generator.description()));
                    case "distinct-key-object" ->
                        distinctKeyObject(requiredPositiveParameter(
                                parameters, "fieldCount", lineNumber, generator.description()));
                    case "long-string" ->
                        "\""
                                + "a"
                                        .repeat(requiredPositiveParameter(
                                                parameters, "characterCount", lineNumber, generator.description()))
                                + "\"";
                    case "valid-tools-call-arguments" ->
                        validToolsCallArguments(requiredPositiveParameter(
                                parameters, "argumentMemberCount", lineNumber, generator.description()));
                    default -> throw invalid(lineNumber, "unsupported generator: " + generator.description());
                };
        return rendered.getBytes(StandardCharsets.UTF_8);
    }

    private static String flatScalarArray(int elementCount) {
        return "[" + "0,".repeat(elementCount - 1) + "0]";
    }

    private static String nestedArray(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }

    private static String nestedObject(int depth) {
        return "{\"level\":".repeat(depth - 1) + "{}" + "}".repeat(depth - 1);
    }

    private static String distinctKeyObject(int fieldCount) {
        StringBuilder document = new StringBuilder("{");
        for (int index = 0; index < fieldCount; index++) {
            if (index > 0) {
                document.append(',');
            }
            document.append('\"').append("key").append(index).append("\":0");
        }
        return document.append('}').toString();
    }

    private static String validToolsCallArguments(int argumentMemberCount) {
        StringBuilder body = new StringBuilder(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"_meta\":{")
                .append("\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",")
                .append("\"io.modelcontextprotocol/clientCapabilities\":{}},\"name\":\"token-budget.publicTool\",")
                .append("\"arguments\":{\"field0\":[]");
        for (int field = 1; field < argumentMemberCount; field++) {
            body.append(",\"field").append(field).append("\":0");
        }
        return body.append("}}}").toString();
    }

    private static int countParserTokens(byte[] json) {
        int count = 0;
        try (JsonParser parser = new JsonFactory().createParser(json)) {
            while (parser.nextToken() != null) {
                count++;
            }
            return count;
        } catch (IOException malformed) {
            throw new IllegalStateException("Rendered JSON token corpus row is malformed", malformed);
        }
    }

    private static void rejectUnknownOrMissingFields(
            ObjectNode object, Set<String> allowed, int lineNumber, String subject) {
        Set<String> fields = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(fields::add);
        Set<String> unknown = new LinkedHashSet<>(fields);
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw invalid(lineNumber, "unknown " + subject + " fields: " + unknown);
        }
        Set<String> missing = new LinkedHashSet<>(allowed);
        missing.removeAll(fields);
        if (!missing.isEmpty()) {
            throw invalid(lineNumber, "missing " + subject + " fields: " + missing);
        }
    }

    private static String requiredText(ObjectNode object, String field, int lineNumber) {
        JsonNode value = object.required(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid(lineNumber, field + " must be a nonblank string");
        }
        return value.textValue();
    }

    private static Set<String> requiredStringSet(ObjectNode object, String field, int lineNumber) {
        JsonNode value = object.required(field);
        if (!value.isArray() || value.isEmpty()) {
            throw invalid(lineNumber, field + " must be a nonempty string array");
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode element : value) {
            if (!element.isTextual() || element.textValue().isBlank() || !values.add(element.textValue())) {
                throw invalid(lineNumber, field + " must contain unique nonblank strings");
            }
        }
        return Set.copyOf(values);
    }

    private static int requiredPositiveInt(ObjectNode object, String field, int lineNumber) {
        JsonNode value = object.required(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw invalid(lineNumber, field + " must be a positive integer");
        }
        return value.intValue();
    }

    private static int requiredPositiveParameter(
            Map<String, Object> parameters, String field, int lineNumber, String generator) {
        Object value = parameters.get(field);
        if (value instanceof java.math.BigInteger bigInteger) {
            if (bigInteger.signum() <= 0 || bigInteger.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw invalid(lineNumber, generator + " parameter " + field + " must be a positive integer");
            }
            rejectUnknownParameters(parameters, Set.of(field), lineNumber, generator);
            return bigInteger.intValue();
        }
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() <= 0
                || ((Number) value).longValue() > Integer.MAX_VALUE) {
            throw invalid(lineNumber, generator + " parameter " + field + " must be a positive integer");
        }
        rejectUnknownParameters(parameters, Set.of(field), lineNumber, generator);
        return ((Number) value).intValue();
    }

    private static String requiredStringParameter(
            Map<String, Object> parameters, String field, int lineNumber, String generator) {
        Object value = parameters.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid(lineNumber, generator + " parameter " + field + " must be a nonblank string");
        }
        rejectUnknownParameters(parameters, Set.of(field), lineNumber, generator);
        return text;
    }

    private static void rejectUnknownParameters(
            Map<String, Object> parameters, Set<String> allowed, int lineNumber, String generator) {
        if (!parameters.keySet().equals(allowed)) {
            throw invalid(lineNumber, generator + " parameters must be exactly " + allowed);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalStateException("JSON token corpus resource is not valid UTF-8", invalid);
        }
    }

    private static IllegalStateException invalid(int lineNumber, String reason) {
        return new IllegalStateException("Invalid JSON token corpus row " + lineNumber + ": " + reason);
    }

    private static IllegalStateException invalid(int lineNumber, String reason, Exception cause) {
        return new IllegalStateException("Invalid JSON token corpus row " + lineNumber + ": " + reason, cause);
    }

    public record Row(
            String id,
            Set<String> structuralClasses,
            Set<String> consumers,
            Generator generator,
            int tokenCount,
            int configuredBudget,
            Expected expected,
            int maxBodyBytes,
            byte[] renderedUtf8) {

        public Row {
            structuralClasses = Set.copyOf(structuralClasses);
            consumers = Set.copyOf(consumers);
            renderedUtf8 = renderedUtf8.clone();
        }

        @Override
        public byte[] renderedUtf8() {
            return renderedUtf8.clone();
        }
    }

    public record Generator(String description, Map<String, Object> parameters) {
        public Generator {
            parameters = Map.copyOf(parameters);
        }
    }

    public enum Expected {
        ACCEPT,
        REJECT;

        private static Expected parse(String value, int lineNumber) {
            return switch (value) {
                case "accept" -> ACCEPT;
                case "reject" -> REJECT;
                default -> throw invalid(lineNumber, "expected must be accept or reject");
            };
        }
    }
}
