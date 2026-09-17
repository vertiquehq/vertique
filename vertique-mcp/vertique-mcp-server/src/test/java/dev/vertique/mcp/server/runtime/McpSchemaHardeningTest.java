// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T009 TP-001 — the frozen hardening contract matrix.
 *
 * <p>Every row hardens one hand-authored JSON-005 canonical document — a record tool, a
 * nested-object tool, a map-valued tool, and a polymorphic tool, exactly matching the shape
 * {@code AnnotationJsonSchemaGenerator.generateCanonical(Type)} produces (proven against real
 * generator output by the differential corpus in {@link McpSchemaHardeningDifferentialTest} and end
 * to end by {@code McpJson005ConsumptionIT}) — through {@link McpSchemaHardener#harden} and
 * {@link McpCanonicalJsonWriter#writeCanonical}, and compares the full result against a pinned
 * expectation.
 *
 * <p>The nested-object fixture's adjacent-object marker is the {@code address} property's
 * non-empty {@code properties} member: it is what makes that nested object schema a candidate for
 * closure. The sensitivity proof empties it, which must flip
 * {@link #shouldCloseAdjacentArgumentObjects()}'s closed-object assertion while leaving
 * {@link #shouldPreserveJson005AnyOfBranchesExactly()}'s independent branch-count assertion
 * unaffected.
 */
@DisplayName("MCP schema hardening — T009 contract matrix")
class McpSchemaHardeningTest {

    static Stream<MatrixRow> t009ContractMatrix() {
        return Stream.of(
                new MatrixRow(
                        "shouldCloseAdjacentArgumentObjects",
                        McpSchemaHardeningTest::shouldCloseAdjacentArgumentObjects),
                new MatrixRow(
                        "shouldAttachParameterDescriptions", McpSchemaHardeningTest::shouldAttachParameterDescriptions),
                new MatrixRow(
                        "shouldLeaveBareMapValueSchemasUntouched",
                        McpSchemaHardeningTest::shouldLeaveBareMapValueSchemasUntouched),
                new MatrixRow(
                        "shouldPreserveJson005AnyOfBranchesExactly",
                        McpSchemaHardeningTest::shouldPreserveJson005AnyOfBranchesExactly));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t009ContractMatrix")
    @DisplayName("enforces the T009 contract matrix")
    void shouldEnforceT009ContractMatrix(MatrixRow row) throws Throwable {
        row.proof().execute();
    }

    // --- Row 1: adjacent argument objects, root and nested, both closed ---

    /**
     * Given the nested-object tool document — a root carrier with a nested {@code address} object
     * schema declaring a non-empty {@code properties} member — hardening closes both the root and the
     * nested object, and every other JSON-005 construct (the {@code $schema} keyword, both property
     * shapes) is byte-preserved.
     */
    private static void shouldCloseAdjacentArgumentObjects() {
        String hardened = harden(McpSchemaHardeningTestFixture.NESTED_OBJECT_TOOL_DOCUMENT, List.of());

        assertThat(hardened)
                .as("the root and the nested adjacent object must both be closed, and nothing else changed")
                .isEqualTo(McpSchemaHardeningTestFixture.NESTED_OBJECT_TOOL_HARDENED);
    }

    // --- Row 2: parameter descriptions attach to root-carrier properties only ---

    /**
     * Given the record-tool document and two parameter descriptions, hardening attaches each
     * description to its matching root property (keyed by external name) and closes the root; every
     * other construct is byte-preserved.
     */
    private static void shouldAttachParameterDescriptions() {
        List<McpToolParameterMetadata> parameters = List.of(
                new McpToolParameterMetadata("argument0", "city", "The city name."),
                new McpToolParameterMetadata("argument1", "zip", "The zip code."));

        String hardened = harden(McpSchemaHardeningTestFixture.RECORD_TOOL_DOCUMENT, parameters);

        assertThat(hardened)
                .as("each root property must carry its matching description, and the root must be closed")
                .isEqualTo(McpSchemaHardeningTestFixture.RECORD_TOOL_HARDENED);
    }

    // --- Row 3: a bare map-value schema stays open ---

    /**
     * Given the map-valued tool document — a root carrier whose {@code counts} property is JSON-005's
     * bare {@code {"type":"object"}} representation of a resolved {@code Map<K,V>} — hardening closes
     * only the root; the property-less {@code counts} object stays open and schema-unconstrained.
     */
    private static void shouldLeaveBareMapValueSchemasUntouched() {
        String hardened = harden(McpSchemaHardeningTestFixture.MAP_VALUED_TOOL_DOCUMENT, List.of());

        assertThat(hardened)
                .as("the bare map-value schema must stay open; only the root is closed")
                .isEqualTo(McpSchemaHardeningTestFixture.MAP_VALUED_TOOL_HARDENED);
    }

    // --- Row 4: anyOf branches are closed individually, and preserved exactly otherwise ---

    /**
     * Given the polymorphic-tool document — a root carrier whose {@code pet} property is an
     * {@code anyOf} of two closed-polymorphism branches — hardening closes the root and each branch
     * individually; the {@code pet} wrapper itself (no {@code properties} member of its own) stays
     * open, the branch count and order are preserved, and every other construct is byte-preserved.
     */
    private static void shouldPreserveJson005AnyOfBranchesExactly() {
        String hardened = harden(McpSchemaHardeningTestFixture.POLYMORPHIC_TOOL_DOCUMENT, List.of());

        assertThat(hardened)
                .as("the root and each anyOf branch must be closed individually, with branch order preserved")
                .isEqualTo(McpSchemaHardeningTestFixture.POLYMORPHIC_TOOL_HARDENED);

        JsonNode reparsed = McpCanonicalJsonWriter.read(hardened);
        assertThat(reparsed.at("/properties/pet/anyOf"))
                .as("hardening must not add, remove, or reorder anyOf branches")
                .hasSize(2);
    }

    // --- T005 TP-001: a declared additionalProperties is kept, and the walk descends into it ---

    /**
     * Given a non-root object declaring {@code properties} beside a typed {@code additionalProperties}
     * schema (FR-015's any-setter description), hardening leaves that declaration untouched and closes
     * the root carrier alone.
     */
    @Test
    @DisplayName("T005 TP-001: a declared additionalProperties schema is never overwritten")
    void shouldKeepADeclaredAdditionalPropertiesSchema() {
        String hardened = harden(McpSchemaHardeningTestFixture.DECLARED_EXTRAS_SCHEMA_DOCUMENT, List.of());

        assertThat(hardened)
                .as("a declared additionalProperties schema must survive hardening, so the typed extras "
                        + "description reaches the client; only the root carrier is closed")
                .isEqualTo(McpSchemaHardeningTestFixture.DECLARED_EXTRAS_SCHEMA_HARDENED);
    }

    /**
     * Given a non-root object declaring {@code additionalProperties: true} — what an
     * application-authored profile override fragment declares — hardening keeps it open.
     */
    @Test
    @DisplayName("T005 TP-001: a declared additionalProperties true is never overwritten")
    void shouldKeepADeclaredAdditionalPropertiesTrue() {
        String hardened = harden(McpSchemaHardeningTestFixture.DECLARED_EXTRAS_TRUE_DOCUMENT, List.of());

        assertThat(hardened)
                .as("an application fragment's declared additionalProperties:true must stay true")
                .isEqualTo(McpSchemaHardeningTestFixture.DECLARED_EXTRAS_TRUE_HARDENED);
    }

    /**
     * Given a non-root object declaring {@code additionalProperties: false} — what a class-level
     * {@code @Schema(additionalProperties = FALSE)} declares — hardening keeps it closed. This row
     * characterizes the pre-change hardener too, which reached the same document by overwriting.
     */
    @Test
    @DisplayName("T005 TP-001: a declared additionalProperties false is kept")
    void shouldKeepADeclaredAdditionalPropertiesFalse() {
        String hardened = harden(McpSchemaHardeningTestFixture.DECLARED_EXTRAS_FALSE_DOCUMENT, List.of());

        assertThat(hardened)
                .as("a declared additionalProperties:false must stay false")
                .isEqualTo(McpSchemaHardeningTestFixture.DECLARED_EXTRAS_FALSE_HARDENED);
    }

    /**
     * Given a non-root object whose declared {@code additionalProperties} is a plain DTO schema with
     * {@code properties} of its own (design proof v2, V07), hardening keeps the declaration and closes
     * the DTO inside it, exactly as it closes an {@code items} subschema.
     */
    @Test
    @DisplayName("T005 TP-001: the walk descends into a declared additionalProperties")
    void shouldCloseAPlainTypeUsedAsAnAdditionalPropertiesValue() {
        String hardened = harden(McpSchemaHardeningTestFixture.DTO_VALUED_EXTRAS_DOCUMENT, List.of());

        assertThat(hardened)
                .as("a plain DTO used as an any-setter's value type must itself be closed, so an unknown "
                        + "nested key is rejected at the schema stage (design proof v2, V07)")
                .isEqualTo(McpSchemaHardeningTestFixture.DTO_VALUED_EXTRAS_HARDENED);
    }

    /**
     * Given a non-root object carrying FR-015's {@code propertyNames} reservation and FR-016's alias
     * rule — an {@code allOf} of a {@code oneOf} whose branches hold only {@code required} or
     * {@code not} — hardening closes the object and leaves both constructs byte-identical.
     */
    @Test
    @DisplayName("T005 TP-001: propertyNames and required-only rule branches are untouched")
    void shouldLeavePropertyNamesAndRequiredOnlyBranchesUntouched() {
        String hardened = harden(McpSchemaHardeningTestFixture.PROPERTY_NAMES_AND_BRANCHES_DOCUMENT, List.of());

        assertThat(hardened)
                .as("propertyNames and every required-only or not-only rule branch must be byte-preserved, "
                        + "while the object declaring properties is closed")
                .isEqualTo(McpSchemaHardeningTestFixture.PROPERTY_NAMES_AND_BRANCHES_HARDENED);
    }

    // --- Shared action: harden + canonicalize, exactly once ---

    private static String harden(String json005CanonicalDocument, List<McpToolParameterMetadata> parameters) {
        return McpCanonicalJsonWriter.writeCanonical(
                McpSchemaHardener.harden(McpCanonicalJsonWriter.read(json005CanonicalDocument), parameters));
    }

    /** One named row of the contract matrix. */
    private record MatrixRow(String rowName, Executable proof) {
        @Override
        public String toString() {
            return rowName;
        }
    }
}
