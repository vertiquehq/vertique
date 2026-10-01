// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static dev.vertique.rest.openapi.docs.OperationMetadata.isSet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.rest.core.RestConfigurationException;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import jakarta.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

/**
 * Renders the example values and the named Example Objects a document publishes for an input.
 *
 * <p>An example value is published as JSON when its whole trimmed text parses strictly as one JSON
 * value ({@code null} included), and otherwise as the string it is. Named examples are keyed by their
 * names in declaration order; each Example Object carries {@code summary}, {@code description}, and
 * {@code value} or {@code externalValue}, in that order, each only when set. Extensions of an {@link
 * ExampleObject} are not published.
 *
 * <p>A named example fails publication when its name is blank, when an earlier example of the same
 * array has its name, when it sets both a value and an external value, or when it sets a reference,
 * which the document cannot resolve. Every failure names the operation, the input, and the attribute,
 * never a value.
 */
final class Examples {

    private static final ObjectMapper STRICT_JSON =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Examples() {}

    /**
     * Returns an example text as the value a document publishes.
     *
     * @param text the example text
     * @return the JSON value the trimmed text parses to, or the text as a string when it does not parse
     *     as exactly one JSON value
     */
    static JsonNode value(String text) {
        JsonNode parsed = parseStrictly(text.trim());
        return parsed != null ? parsed : NODES.textNode(text);
    }

    /**
     * Parses a text strictly as exactly one JSON value.
     *
     * @param text the text, parsed as given
     * @return the JSON value, or {@code null} when the text is not exactly one JSON value
     */
    @Nullable
    static JsonNode parseStrictly(String text) {
        try {
            JsonNode parsed = STRICT_JSON.readTree(text);
            return parsed == null || parsed.isMissingNode() ? null : parsed;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Checks the named examples of an input and renders them.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param operationId the runtime operation id
     * @param where where the examples are declared, for example {@code @Parameter.examples on query
     *     parameter q}
     * @param examples the named examples, in declaration order
     * @return the Examples map, or {@code null} when there are none
     * @throws RestConfigurationException when a name is blank or repeated, an example sets both a
     *     value and an external value, or an example sets a reference
     */
    static ObjectNode render(String subject, String operationId, String where, ExampleObject[] examples) {
        check(subject, operationId, where, examples);
        if (examples.length == 0) {
            return null;
        }
        ObjectNode rendered = NODES.objectNode();
        for (ExampleObject example : examples) {
            rendered.set(example.name(), example(example));
        }
        return rendered;
    }

    /** Checks named examples, in declaration order, without rendering them. */
    private static void check(String subject, String operationId, String where, ExampleObject[] examples) {
        Set<String> names = new HashSet<>();
        for (ExampleObject example : examples) {
            String prefix = subject + ": operation '" + operationId + "': @ExampleObject.";
            if (!isSet(example.name())) {
                throw new RestConfigurationException(prefix + "name in " + where + " is blank");
            }
            if (!names.add(example.name())) {
                throw new RestConfigurationException(prefix + "name in " + where + " names more than one example");
            }
            if (isSet(example.value()) && isSet(example.externalValue())) {
                throw new RestConfigurationException(prefix + "value with externalValue in " + where
                        + ": an example has a value or an external value, not both");
            }
            if (isSet(example.ref())) {
                throw InputDocumentation.unresolvedReference(subject, operationId, "@ExampleObject.ref in " + where);
            }
        }
    }

    /** Renders one Example Object. */
    private static ObjectNode example(ExampleObject example) {
        ObjectNode node = NODES.objectNode();
        if (isSet(example.summary())) {
            node.put("summary", example.summary());
        }
        if (isSet(example.description())) {
            node.put("description", example.description());
        }
        if (isSet(example.value())) {
            node.set("value", value(example.value()));
        } else if (isSet(example.externalValue())) {
            node.put("externalValue", example.externalValue());
        }
        return node;
    }
}
