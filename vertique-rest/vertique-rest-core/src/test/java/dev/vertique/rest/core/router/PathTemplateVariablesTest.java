// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PathTemplateVariablesTest {

    @ParameterizedTest
    @ValueSource(strings = {"user_id", "user-id", "user.id", "2nd", "_id"})
    @DisplayName("a name a Java named group rejects is reported when the template has a regex constraint")
    void invalidNameReportedInRegexTemplate(String name) {
        String template = "/u/{" + name + ":[0-9]+}";
        assertEquals(List.of(name), PathTemplateVariables.invalidRegexVariableNames(template));
        // The reported names are exactly the ones route creation would fail on.
        assertThrows(PatternSyntaxException.class, () -> Pattern.compile("(?<" + name + ">[0-9]+)"));
    }

    @Test
    @DisplayName("an unconstrained variable is checked when another variable carries a regex constraint")
    void unconstrainedVariableChecked() {
        assertEquals(List.of("sub_key"), PathTemplateVariables.invalidRegexVariableNames("/u/{id:[0-9]+}/{sub_key}"));
    }

    @Test
    @DisplayName("a template with no regex constraint reports nothing, whatever the names")
    void plainTemplateReportsNothing() {
        assertEquals(List.of(), PathTemplateVariables.invalidRegexVariableNames("/u/{user_id}/{a-b}"));
    }

    @Test
    @DisplayName("letter-and-digit names are accepted and a repeated bad name is reported once")
    void validNamesAcceptedAndDuplicatesCollapsed() {
        assertEquals(List.of(), PathTemplateVariables.invalidRegexVariableNames("/u/{id:\\d+}/{subKey}/{a1}"));
        assertEquals(List.of("a_b"), PathTemplateVariables.invalidRegexVariableNames("/{a_b:\\d+}/{a_b}"));
    }

    @Test
    @DisplayName("the message names the template, the variable and the rule")
    void messageNamesTemplateVariableAndRule() {
        String message = PathTemplateVariables.invalidRegexVariableNameMessage("/u/{user_id:[0-9]+}", "user_id");
        assertTrue(message.contains("/u/{user_id:[0-9]+}"), message);
        assertTrue(message.contains("'user_id'"), message);
        assertTrue(message.contains("letter followed by letters and digits"), message);
    }
}
