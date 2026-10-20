// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.exception.ConfigurationException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Unit tests for the validation, copying and lookup invariants of {@link DbSchemas}. */
@DisplayName("DbSchemas")
class DbSchemasTest {

    @TestFactory
    @DisplayName("validates its input and keeps an immutable defensive copy")
    Stream<DynamicTest> validatesAndCopiesMappings() {
        return Stream.of(
                nullContainerIsRejected(),
                nullKeyIsRejected(),
                nullValueIsRejected(),
                blankKeyIsRejected("", "empty key"),
                blankKeyIsRejected("   ", "space-only key"),
                blankKeyIsRejected("\t\n", "whitespace-control key"),
                blankValueIsRejected("", "empty value"),
                blankValueIsRejected("   ", "space-only value"),
                blankValueIsRejected("\t\n", "whitespace-control value"),
                validMappingsAreExposedUnchanged(),
                unknownNameHasNoSchema(),
                laterChangesToTheSourceMapAreNotVisible(),
                mappingsViewCannotBeModified(),
                emptyHasNoMappings());
    }

    private static DynamicTest nullContainerIsRejected() {
        return DynamicTest.dynamicTest("rejects a null map", () -> {
            // Given a null map; When constructing; Then it is rejected
            assertThrows(NullPointerException.class, () -> new DbSchemas(null));
        });
    }

    private static DynamicTest nullKeyIsRejected() {
        return DynamicTest.dynamicTest("rejects a null key", () -> {
            // Given a map holding a null key
            Map<String, String> source = new HashMap<>();
            source.put(null, "jobs");

            // When constructing; Then it is rejected
            assertThrows(NullPointerException.class, () -> new DbSchemas(source));
        });
    }

    private static DynamicTest nullValueIsRejected() {
        return DynamicTest.dynamicTest("rejects a null value", () -> {
            // Given a map holding a null value
            Map<String, String> source = new HashMap<>();
            source.put("job", null);

            // When constructing; Then it is rejected
            assertThrows(NullPointerException.class, () -> new DbSchemas(source));
        });
    }

    private static DynamicTest blankKeyIsRejected(String key, String shape) {
        return DynamicTest.dynamicTest("rejects a blank key: " + shape, () -> {
            // Given a map with a blank key; When constructing; Then it is rejected
            assertThrows(ConfigurationException.class, () -> new DbSchemas(Map.of(key, "jobs")));
        });
    }

    private static DynamicTest blankValueIsRejected(String value, String shape) {
        return DynamicTest.dynamicTest("rejects a blank value: " + shape, () -> {
            // Given a map with a blank value; When constructing; Then it is rejected
            assertThrows(ConfigurationException.class, () -> new DbSchemas(Map.of("job", value)));
        });
    }

    private static DynamicTest validMappingsAreExposedUnchanged() {
        return DynamicTest.dynamicTest("exposes valid mappings unchanged", () -> {
            // Given a valid map, including a name that matches no migration instance
            Map<String, String> source = Map.of("job", "jobs", "app", "Mixed-Case.Schema 1");

            // When constructing
            DbSchemas schemas = new DbSchemas(source);

            // Then every mapping is visible through both accessors, verbatim
            assertEquals(source, schemas.mappings());
            assertEquals(Optional.of("jobs"), schemas.schema("job"));
            assertEquals(Optional.of("Mixed-Case.Schema 1"), schemas.schema("app"));
        });
    }

    private static DynamicTest unknownNameHasNoSchema() {
        return DynamicTest.dynamicTest("has no schema for an unknown name", () -> {
            // Given a binding for one name
            DbSchemas schemas = new DbSchemas(Map.of("job", "jobs"));

            // When asking for a different name; Then the answer is empty
            assertEquals(Optional.empty(), schemas.schema("workflow"));
        });
    }

    private static DynamicTest laterChangesToTheSourceMapAreNotVisible() {
        return DynamicTest.dynamicTest("copies defensively from the source map", () -> {
            // Given a binding built from a mutable map
            Map<String, String> source = new HashMap<>(Map.of("job", "jobs"));
            DbSchemas schemas = new DbSchemas(source);

            // When the source is changed afterwards
            source.put("workflow", "wf");
            source.put("job", "changed");

            // Then the binding is unchanged
            assertEquals(Map.of("job", "jobs"), schemas.mappings());
            assertEquals(Optional.of("jobs"), schemas.schema("job"));
            assertEquals(Optional.empty(), schemas.schema("workflow"));

            // And removing from the source does not remove from the binding
            source.clear();
            assertEquals(Map.of("job", "jobs"), schemas.mappings());
        });
    }

    private static DynamicTest mappingsViewCannotBeModified() {
        return DynamicTest.dynamicTest("exposes mappings that cannot be modified", () -> {
            // Given a binding
            DbSchemas schemas = new DbSchemas(Map.of("job", "jobs"));

            // When modifying the returned mappings in each way; Then each attempt fails
            assertThrows(UnsupportedOperationException.class, () -> schemas.mappings()
                    .put("workflow", "wf"));
            assertThrows(UnsupportedOperationException.class, () -> schemas.mappings()
                    .put("job", "changed"));
            assertThrows(UnsupportedOperationException.class, () -> schemas.mappings()
                    .remove("job"));
            assertThrows(UnsupportedOperationException.class, () -> schemas.mappings()
                    .clear());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> schemas.mappings().entrySet().clear());

            // And the binding is unchanged
            assertEquals(Map.of("job", "jobs"), schemas.mappings());
        });
    }

    private static DynamicTest emptyHasNoMappings() {
        return DynamicTest.dynamicTest("empty() has no mappings and they cannot be modified", () -> {
            // Given the empty binding
            DbSchemas empty = DbSchemas.empty();

            // Then it maps nothing
            assertEquals(Map.of(), empty.mappings());
            assertEquals(Optional.empty(), empty.schema("job"));

            // And its mappings cannot be modified
            assertThrows(
                    UnsupportedOperationException.class, () -> empty.mappings().put("job", "jobs"));
            assertEquals(Map.of(), DbSchemas.empty().mappings());
        });
    }
}
