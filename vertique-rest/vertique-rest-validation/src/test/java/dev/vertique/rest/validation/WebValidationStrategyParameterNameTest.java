// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pins how the schema gate turns a parameter name and the instance location of a violation inside its
 * value into the {@code parameter} an error entry reports: the name followed by {@code [n]} for each
 * leading segment that is an array index, and nothing after the first segment that is not one. A member
 * name inside an object-valued parameter is never rendered, because the client chooses it.
 *
 * <p>Nothing else in this module's tests refers to the helper under test, so the remaining tests build
 * and run without it.
 */
class WebValidationStrategyParameterNameTest {

    @ParameterizedTest(name = "({0}, {1}) -> {2}")
    @CsvSource({
        "'ids', '', 'ids'",
        "'ids', '/1', 'ids[1]'",
        "'grid', '/0/2', 'grid[0][2]'",
        "'filter', '/ZZKEY/x', 'filter'",
        "'filter', '/0/ZZKEY', 'filter[0]'",
        "'tags', '/01', 'tags'",
        "'ids', '/0', 'ids[0]'",
        "'ids', '/10/a', 'ids[10]'",
        "'grid', '/1/01', 'grid[1]'",
        "'ids', '/-1', 'ids'",
        "'ids', '/1a', 'ids'",
        "'ids[]', '/1', 'ids[][1]'"
    })
    @DisplayName("a parameter name keeps only the leading array indexes of the violation's location")
    void parameterNameKeepsOnlyLeadingIndexes(String name, String pointer, String expected) {
        // Given a parameter name and the pointer of a violation inside its value

        // When the gate names the parameter for that violation
        String parameter = WebValidationStrategy.parameterName(name, pointer);

        // Then the name is followed by the leading indexes only, and no client-chosen member name appears
        assertEquals(expected, parameter);
        assertFalse(parameter.contains("ZZKEY"), "a member name must never reach the parameter: " + parameter);
    }
}
