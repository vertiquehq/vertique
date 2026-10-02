// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.examples.services.codegen.MavenComparisonNormalizer.Difference;
import dev.vertique.examples.services.codegen.MavenComparisonNormalizer.ExpectedDifference;
import dev.vertique.examples.services.codegen.MavenComparisonNormalizer.Reconciliation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hand-written rows for {@link MavenComparisonNormalizer}: each row compares two literal documents
 * and expects literal difference lines.
 */
class MavenComparisonNormalizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Builds a document with one {@code POST /op} operation {@code op} whose JSON request body has
     * the given schema, plus the given component schemas.
     */
    private static JsonNode bodyDocument(String schema, String components) {
        return json("{\"paths\":{\"/op\":{\"post\":{\"operationId\":\"op\",\"requestBody\":{\"content\":"
                + "{\"application/json\":{\"schema\":" + schema + "}}},\"responses\":{}}}},"
                + "\"components\":{\"schemas\":" + components + "}}");
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static List<String> lines(List<Difference> differences) {
        return differences.stream().map(Difference::line).toList();
    }

    @Test
    @DisplayName("nullable true in the Maven document equals a type array naming null")
    void nullableMapsToNullInTheTypeSet() {
        JsonNode maven = bodyDocument(
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"nullable\":true}}}", "{}");
        JsonNode runtime =
                bodyDocument("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":[\"string\",\"null\"]}}}", "{}");

        assertEquals(List.of(), lines(MavenComparisonNormalizer.compare(maven, runtime)));
    }

    @Test
    @DisplayName("a missing nullable is a type difference on that property")
    void missingNullableIsATypeDifference() {
        JsonNode maven = bodyDocument("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}", "{}");
        JsonNode runtime =
                bodyDocument("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":[\"string\",\"null\"]}}}", "{}");

        assertEquals(
                List.of("op requestBody application/json #/properties/name type [string]→[null, string]"),
                lines(MavenComparisonNormalizer.compare(maven, runtime)));
    }

    @Test
    @DisplayName("a component reference and a relocated fragment reference resolve to equal schemas")
    void referencesResolveInBothStyles() {
        JsonNode maven = bodyDocument(
                "{\"$ref\":\"#/components/schemas/Order\"}",
                "{\"Order\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},"
                        + "\"line\":{\"$ref\":\"#/components/schemas/Line\"}}},"
                        + "\"Line\":{\"type\":\"object\",\"properties\":{\"sku\":{\"type\":\"string\"}}}}");
        JsonNode runtime = bodyDocument(
                "{\"$ref\":\"#/components/schemas/op%20request\"}",
                "{\"op request\":{\"type\":\"object\",\"properties\":{"
                        + "\"id\":{\"$ref\":\"#/components/schemas/a~1b/$defs/Id\"},"
                        + "\"line\":{\"$ref\":\"#/components/schemas/op.request.Line\"}}},"
                        + "\"op.request.Line\":{\"type\":\"object\",\"properties\":{\"sku\":{\"type\":\"string\"}}},"
                        + "\"a/b\":{\"$defs\":{\"Id\":{\"type\":\"string\"}}}}");

        assertEquals(List.of(), lines(MavenComparisonNormalizer.compare(maven, runtime)));
    }

    @Test
    @DisplayName("a reference that does not resolve fails naming the reference")
    void unresolvedReferenceFails() {
        JsonNode maven = bodyDocument("{\"$ref\":\"#/components/schemas/Missing\"}", "{}");
        JsonNode runtime = bodyDocument("{\"type\":\"object\"}", "{}");

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> MavenComparisonNormalizer.compare(maven, runtime));
        assertTrue(failure.getMessage().contains("#/components/schemas/Missing"), failure.getMessage());
    }

    @Test
    @DisplayName("recursion is recorded as a back-reference to the ancestor and is never followed")
    void recursionIsABackReference() {
        String node =
                "{\"Node\":{\"type\":\"object\",\"properties\":{\"next\":{\"$ref\":\"#/components/schemas/Node\"}}}}";
        JsonNode maven = bodyDocument("{\"$ref\":\"#/components/schemas/Node\"}", node);
        JsonNode same = bodyDocument("{\"$ref\":\"#/components/schemas/Node\"}", node);
        JsonNode unrolled = bodyDocument(
                "{\"$ref\":\"#/components/schemas/Node\"}",
                "{\"Node\":{\"type\":\"object\",\"properties\":{\"next\":{\"type\":\"object\"}}}}");

        assertEquals(List.of(), lines(MavenComparisonNormalizer.compare(maven, same)));
        assertEquals(
                List.of("op requestBody application/json #/properties/next recursion back-reference to #→none"),
                lines(MavenComparisonNormalizer.compare(maven, unrolled)));
    }

    @Test
    @DisplayName("an operation in one document only is a presence difference")
    void operationInOneDocumentIsAPresenceDifference() {
        JsonNode maven = json("{\"paths\":{\"/a\":{\"get\":{\"operationId\":\"onlyMaven\",\"responses\":{}}}}}");
        JsonNode runtime = json("{\"paths\":{\"/b\":{\"get\":{\"operationId\":\"onlyRuntime\",\"responses\":{}}}}}");

        assertEquals(
                List.of(
                        "onlyMaven operation # presence present→absent",
                        "onlyRuntime operation # presence absent→present"),
                lines(MavenComparisonNormalizer.compare(maven, runtime)));
    }

    @Test
    @DisplayName("a renamed response property is one property-names difference with no cascade")
    void renamedPropertyIsAPropertyNamesDifference() {
        JsonNode maven = json("{\"paths\":{\"/a\":{\"get\":{\"operationId\":\"read\",\"responses\":{\"200\":"
                + "{\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":"
                + "{\"trackingId\":{\"type\":\"string\"}}}}}}}}}}}");
        JsonNode runtime = json("{\"paths\":{\"/a\":{\"get\":{\"operationId\":\"read\",\"responses\":{\"200\":"
                + "{\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":"
                + "{\"trackingNumber\":{\"type\":\"string\"}}}}}}}}}}}");

        assertEquals(
                List.of("read response 200 application/json # property-names [trackingId]→[trackingNumber]"),
                lines(MavenComparisonNormalizer.compare(maven, runtime)));
    }

    @Test
    @DisplayName("parameter requiredness and parameter presence are compared")
    void parametersAreCompared() {
        JsonNode maven = json("{\"paths\":{\"/a\":{\"post\":{\"operationId\":\"send\",\"parameters\":["
                + "{\"name\":\"orderId\",\"in\":\"query\",\"required\":true,\"schema\":{\"type\":\"string\"}},"
                + "{\"name\":\"trace\",\"in\":\"header\",\"schema\":{\"type\":\"string\"}}],\"responses\":{}}}}}");
        JsonNode runtime = json("{\"paths\":{\"/a\":{\"post\":{\"operationId\":\"send\",\"parameters\":["
                + "{\"name\":\"orderId\",\"in\":\"query\",\"schema\":{\"type\":\"string\"}}],\"responses\":{}}}}}");

        assertEquals(
                List.of(
                        "send parameter header trace # presence present→absent",
                        "send parameter query orderId # required true→false"),
                lines(MavenComparisonNormalizer.compare(maven, runtime)));
    }

    @Test
    @DisplayName("a schema-level required list in one document only is a required difference")
    void schemaRequiredIsCompared() {
        JsonNode maven = bodyDocument(
                "{\"type\":\"object\",\"required\":[\"a\"],\"properties\":{\"a\":{\"type\":\"string\"}}}", "{}");
        JsonNode runtime = bodyDocument("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}", "{}");

        assertEquals(
                List.of("op requestBody application/json # required [a]→[]"),
                lines(MavenComparisonNormalizer.compare(maven, runtime)));
    }

    @Test
    @DisplayName("allOf with one branch against allOf with two branches is a composition arity difference")
    void compositionArityIsCompared() {
        JsonNode maven = bodyDocument("{\"allOf\":[{\"type\":\"string\"}]}", "{}");
        JsonNode runtime = bodyDocument("{\"allOf\":[{\"type\":\"string\"},{\"type\":\"object\"}]}", "{}");

        assertEquals(
                List.of("op requestBody application/json # composition [allOf[1]]→[allOf[2]]"),
                lines(MavenComparisonNormalizer.compare(maven, runtime)));
    }

    @Test
    @DisplayName("not in one document only is a composition difference")
    void notPresenceIsCompared() {
        JsonNode maven = bodyDocument("{\"type\":\"string\",\"not\":{\"type\":\"null\"}}", "{}");
        JsonNode runtime = bodyDocument("{\"type\":\"string\"}", "{}");

        assertEquals(
                List.of("op requestBody application/json # composition [not]→[]"),
                lines(MavenComparisonNormalizer.compare(maven, runtime)));
    }

    @Test
    @DisplayName("oneOf in one document and anyOf in the other is a composition difference")
    void oneOfAndAnyOfPresenceIsCompared() {
        JsonNode maven = bodyDocument("{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}", "{}");
        JsonNode runtime = bodyDocument("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}", "{}");

        assertEquals(
                List.of("op requestBody application/json # composition [oneOf[2]]→[anyOf[2]]"),
                lines(MavenComparisonNormalizer.compare(maven, runtime)));
    }

    @Test
    @DisplayName("readOnly true against an absent readOnly is a readOnly difference")
    void readOnlyIsCompared() {
        JsonNode maven = bodyDocument(
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"readOnly\":true}}}", "{}");
        JsonNode runtime = bodyDocument("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}", "{}");

        assertEquals(
                List.of("op requestBody application/json #/properties/id readOnly true→false"),
                lines(MavenComparisonNormalizer.compare(maven, runtime)));
    }

    @Test
    @DisplayName("an absent writeOnly against writeOnly true is a writeOnly difference")
    void writeOnlyIsCompared() {
        JsonNode maven = bodyDocument("{\"type\":\"object\",\"properties\":{\"secret\":{\"type\":\"string\"}}}", "{}");
        JsonNode runtime = bodyDocument(
                "{\"type\":\"object\",\"properties\":{\"secret\":{\"type\":\"string\",\"writeOnly\":true}}}", "{}");

        assertEquals(
                List.of("op requestBody application/json #/properties/secret writeOnly false→true"),
                lines(MavenComparisonNormalizer.compare(maven, runtime)));
    }

    @Test
    @DisplayName("items in one document only is a presence difference at the items pointer")
    void itemsPresenceIsCompared() {
        JsonNode maven = bodyDocument("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}", "{}");
        JsonNode runtime = bodyDocument("{\"type\":\"array\"}", "{}");

        assertEquals(
                List.of("op requestBody application/json #/items presence present→absent"),
                lines(MavenComparisonNormalizer.compare(maven, runtime)));
    }

    @Test
    @DisplayName("an expected entry that no difference matches is reported as stale")
    void staleEntryIsDetected() {
        List<Difference> observed =
                List.of(new Difference("read", "response 200 application/json", "#", "property-names", "[a]", "[b]"));
        List<ExpectedDifference> expected = MavenComparisonNormalizer.readExpected(json("["
                + "{\"operationId\":\"read\",\"location\":\"response 200 application/json\",\"pointer\":\"#\","
                + "\"dimension\":\"property-names\",\"authority\":\"canonical-output-generator\",\"reason\":\"r\"},"
                + "{\"operationId\":\"read\",\"location\":\"response 200 application/json\","
                + "\"pointer\":\"#/properties/a\",\"dimension\":\"type\",\"authority\":\"json-profile\","
                + "\"reason\":\"r\"}]"));

        Reconciliation reconciliation = MavenComparisonNormalizer.reconcile(observed, expected);

        assertEquals(List.of(), reconciliation.unexplained());
        assertEquals(
                List.of("read response 200 application/json #/properties/a type *→*"),
                reconciliation.stale().stream().map(ExpectedDifference::line).toList());
    }

    @Test
    @DisplayName("a difference no expected entry names is reported as unexplained")
    void unexplainedDifferenceIsDetected() {
        Difference difference =
                new Difference("read", "response 200 application/json", "#", "property-names", "[a]", "[b]");

        Reconciliation reconciliation = MavenComparisonNormalizer.reconcile(List.of(difference), List.of());

        assertEquals(List.of(difference), reconciliation.unexplained());
        assertTrue(
                reconciliation.report().contains("read response 200 application/json # property-names [a]→[b]"),
                reconciliation.report());
    }

    @Test
    @DisplayName("an expected entry naming an authority outside the closed set is refused")
    void unknownAuthorityIsRefused() {
        JsonNode file = json("[{\"operationId\":\"read\",\"location\":\"operation\",\"pointer\":\"#\","
                + "\"dimension\":\"presence\",\"authority\":\"it-just-differs\",\"reason\":\"r\"}]");

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> MavenComparisonNormalizer.readExpected(file));
        assertTrue(failure.getMessage().contains("it-just-differs"), failure.getMessage());
    }

    @Test
    @DisplayName("expected entries out of operation, location, and pointer order are refused")
    void unsortedEntriesAreRefused() {
        JsonNode file = json("["
                + "{\"operationId\":\"ship\",\"location\":\"operation\",\"pointer\":\"#\",\"dimension\":\"presence\","
                + "\"authority\":\"json-profile\",\"reason\":\"r\"},"
                + "{\"operationId\":\"charge\",\"location\":\"operation\",\"pointer\":\"#\",\"dimension\":\"presence\","
                + "\"authority\":\"json-profile\",\"reason\":\"r\"}]");

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> MavenComparisonNormalizer.readExpected(file));
        assertTrue(failure.getMessage().contains("out of order"), failure.getMessage());
    }
}
