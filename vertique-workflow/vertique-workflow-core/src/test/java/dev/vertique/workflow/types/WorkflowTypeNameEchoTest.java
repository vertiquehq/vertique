// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.types;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies which unknown names an error message may repeat: a name made of letters, digits, underscore, dot,
 * dollar sign and hyphen, at most 255 characters long, is repeated; any other name is described without being
 * repeated, so a hostile value never reaches a log line.
 *
 * <p>The hostile names are built at run time from code points, so no invisible or direction-changing character
 * appears in this source file.
 */
class WorkflowTypeNameEchoTest {

    private static final String RIGHT_TO_LEFT_OVERRIDE = codePoint(0x202E);
    private static final String ZERO_WIDTH_SPACE = codePoint(0x200B);
    private static final String ZERO_WIDTH_JOINER = codePoint(0x200D);

    private static String codePoint(int value) {
        return String.valueOf(Character.toChars(value));
    }

    // --- Names that must not be repeated ---

    @Test
    @DisplayName("an unknown name with a direction override or zero-width characters is not repeated in the error")
    void hostileUnknownNamesAreNotRepeated() {
        for (String hostile : hostileNames()) {
            // Given: a hostile name that no index holds
            DefaultWorkflowTypeNames names = new DefaultWorkflowTypeNames();

            // When: the error for the name is built by the document seam and by a lookup
            WorkflowUnknownTypeNameException fromDocument = DefaultWorkflowTypeNames.unknownName("stateType", hostile);
            WorkflowUnknownTypeNameException fromLookup = resolveFailure(names, hostile);

            // Then: neither message repeats any part of the name, and the document message names the field
            for (WorkflowUnknownTypeNameException error :
                    new WorkflowUnknownTypeNameException[] {fromDocument, fromLookup}) {
                assertThat(error.getMessage())
                        .as("message for a name of %d chars", hostile.length())
                        .doesNotContain(RIGHT_TO_LEFT_OVERRIDE)
                        .doesNotContain(ZERO_WIDTH_SPACE)
                        .doesNotContain(ZERO_WIDTH_JOINER)
                        .doesNotContain("evil");
            }
            assertThat(fromDocument.getMessage()).contains("stateType");
        }
    }

    @Test
    @DisplayName("an unknown name with a space or other character outside the allowed set is not repeated")
    void unknownNameOutsideTheAllowedSetIsNotRepeated() {
        for (String outside : new String[] {"has space", "slash/name", "quote'name", "angle<name>", "dollar$ok name"}) {
            WorkflowUnknownTypeNameException fromDocument = DefaultWorkflowTypeNames.unknownName("stateType", outside);
            WorkflowUnknownTypeNameException fromLookup = resolveFailure(new DefaultWorkflowTypeNames(), outside);

            assertThat(fromDocument.getMessage())
                    .as(outside)
                    .doesNotContain(outside)
                    .contains("stateType");
            assertThat(fromLookup.getMessage()).as(outside).doesNotContain(outside);
        }
    }

    @Test
    @DisplayName("an unknown name longer than 255 characters is not repeated, while one of 255 is")
    void overlongNameIsNotRepeatedAtTheLimit() {
        String atLimit = "a".repeat(255);
        String overLimit = "a".repeat(256);

        assertThat(DefaultWorkflowTypeNames.unknownName("stateType", atLimit).getMessage())
                .contains(atLimit);
        assertThat(resolveFailure(new DefaultWorkflowTypeNames(), atLimit).getMessage())
                .contains(atLimit);
        assertThat(DefaultWorkflowTypeNames.unknownName("stateType", overLimit).getMessage())
                .doesNotContain("aaaaaaaaaa");
        assertThat(resolveFailure(new DefaultWorkflowTypeNames(), overLimit).getMessage())
                .doesNotContain("aaaaaaaaaa");
    }

    @Test
    @DisplayName("a missing name is described without a repeated value")
    void nullNameIsDescribedWithoutAValue() {
        assertThat(DefaultWorkflowTypeNames.unknownName("contract", null).getMessage())
                .contains("contract")
                .doesNotContain("null");
    }

    // --- Names that are still repeated ---

    @Test
    @DisplayName("an unknown name matching the allowed set is still repeated, with its field")
    void conformingUnknownNamesAreStillRepeated() {
        for (String conforming : new String[] {
            "order", "acme.order-v2", "com.acme.Outer$Inner", "A_b.c-D$9", "dev.vertique.workflow.Missing"
        }) {
            WorkflowUnknownTypeNameException fromDocument =
                    DefaultWorkflowTypeNames.unknownName("stateType", conforming);
            WorkflowUnknownTypeNameException fromLookup = resolveFailure(new DefaultWorkflowTypeNames(), conforming);

            assertThat(fromDocument.getMessage()).contains(conforming).contains("stateType");
            assertThat(fromLookup.getMessage()).contains(conforming);
        }
    }

    // --- Helpers ---

    private static String[] hostileNames() {
        return new String[] {
            "evil" + RIGHT_TO_LEFT_OVERRIDE + "name",
            "evil" + ZERO_WIDTH_SPACE + "name",
            "evil" + ZERO_WIDTH_JOINER + RIGHT_TO_LEFT_OVERRIDE + ZERO_WIDTH_SPACE + "name",
            RIGHT_TO_LEFT_OVERRIDE + "evilname",
            "evilname" + ZERO_WIDTH_SPACE
        };
    }

    private static WorkflowUnknownTypeNameException resolveFailure(DefaultWorkflowTypeNames names, String name) {
        try {
            names.resolve(name);
        } catch (WorkflowUnknownTypeNameException e) {
            return e;
        }
        throw new AssertionError("expected resolve to refuse the unknown name");
    }
}
