// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ParameterViolation}: the optional structured path that trails the parameter
 * index and the violation detail, and the previous two-argument construction that leaves it absent.
 */
class ParameterViolationTest {

    @Test
    @DisplayName("the two-argument constructor leaves the structured path absent")
    void twoArgumentConstructorLeavesThePropertyPathAbsent() {
        // Given a violation detail
        ViolationDetail detail = ViolationDetail.of("name", "must not be blank");

        // When a violation is constructed without a structured path and with the root path
        ParameterViolation withoutPath = new ParameterViolation(1, detail);
        ParameterViolation withRoot = new ParameterViolation(1, detail, PropertyPath.root());

        // Then the first has no structured path and the second returns the root path
        assertNull(withoutPath.propertyPath());
        assertEquals(PropertyPath.root(), withRoot.propertyPath());

        // And both keep the index and the detail
        assertEquals(1, withoutPath.parameterIndex());
        assertSame(detail, withoutPath.detail());
        assertEquals(1, withRoot.parameterIndex());
        assertSame(detail, withRoot.detail());
    }
}
