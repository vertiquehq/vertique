// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
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
