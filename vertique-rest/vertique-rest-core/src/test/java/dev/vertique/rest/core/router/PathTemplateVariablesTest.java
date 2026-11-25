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
        String message = PathTemplateVariables.message(
                "Users.get()",
                "/u/{user_id:[0-9]+}",
                new PathTemplateVariables.Problem(PathTemplateVariables.Kind.INVALID_NAME, "user_id"));
        assertTrue(message.contains("Users.get()"), message);
        assertTrue(message.contains("/u/{user_id:[0-9]+}"), message);
        assertTrue(message.contains("'user_id'"), message);
        assertTrue(message.contains("letter followed by letters and digits"), message);
    }

    @Test
    @DisplayName("constraints with nested braces, empty braces and trailing text are read as before")
    void constraintGrammar() {
        var m = PathTemplateVariables.VARIABLE.matcher("/{id:\\d{3}}/{x : [a-z]+ }/{e:{}}/{plain}");
        List<String> constraints = new java.util.ArrayList<>();
        while (m.find()) {
            constraints.add(m.group(1) + "=" + m.group(2));
        }
        assertEquals(List.of("id=\\d{3}", "x=[a-z]+ ", "e={}", "plain=null"), constraints);
    }

    @Test
    @DisplayName("a very long constraint is matched without a stack overflow")
    void veryLongConstraint() {
        String template = "/{id:" + "a".repeat(200_000) + "}";
        assertEquals(List.of(), PathTemplateVariables.invalidRegexVariableNames(template));
        String braces = "/{id:" + "{a}".repeat(100_000) + "}";
        assertEquals(List.of(), PathTemplateVariables.invalidRegexVariableNames(braces));
    }

    @Test
    @DisplayName("a repeated name is reported once, in a plain template and in a regex template")
    void duplicateNames() {
        assertEquals(List.of("id"), PathTemplateVariables.duplicateVariableNames("/o/{id:\\d+}/{id}"));
        assertEquals(List.of("id"), PathTemplateVariables.duplicateVariableNames("/a/{id}/b/{id}/{id}"));
        assertEquals(List.of(), PathTemplateVariables.duplicateVariableNames("/a/{id}/b/{other:\\d+}"));
    }

    @Test
    @DisplayName("a constraint that does not compile is reported against its variable")
    void malformedConstraints() {
        assertEquals(List.of("id"), PathTemplateVariables.malformedConstraintNames("/o/{id:[0-9}"));
        assertEquals(
                List.of("a", "b"), PathTemplateVariables.malformedConstraintNames("/{a:(x}/{ok:\\d+}/{b:*}/{a:(y}"));
        assertEquals(List.of(), PathTemplateVariables.malformedConstraintNames("/o/{id:\\d{3}}/{plain}"));
    }

    @Test
    @DisplayName(
            "problems lists names, repeats and constraints in that order, and the message names method and variable")
    void problemsInOrder() {
        List<PathTemplateVariables.Problem> problems = PathTemplateVariables.problems("/{a_b:[0-9}/{x}/{x}");
        assertEquals(
                List.of(
                        new PathTemplateVariables.Problem(PathTemplateVariables.Kind.INVALID_NAME, "a_b"),
                        new PathTemplateVariables.Problem(PathTemplateVariables.Kind.DUPLICATE_NAME, "x"),
                        new PathTemplateVariables.Problem(PathTemplateVariables.Kind.MALFORMED_CONSTRAINT, "a_b")),
                problems);
        String duplicate = PathTemplateVariables.message("Orders.get()", "/{x}/{x}", problems.get(1));
        assertTrue(duplicate.contains("Orders.get()") && duplicate.contains("'x'"), duplicate);
        String malformed = PathTemplateVariables.message("Orders.get()", "/{a_b:[0-9}", problems.get(2));
        assertTrue(malformed.contains("Orders.get()") && malformed.contains("'a_b'"), malformed);
        assertEquals(List.of(), PathTemplateVariables.problems("/o/{id:\\d+}/{subKey}"));
    }

    @Test
    @DisplayName("a constraint that compiles alone but not inside its group is reported against its variable")
    void constraintsThatFailOnlyInsideTheGroup() {
        assertEquals(List.of("id"), PathTemplateVariables.malformedConstraintNames("/m/{id:\\Qab}"));
        assertEquals(List.of("id"), PathTemplateVariables.malformedConstraintNames("/m/{id:(?x) \\d+ # c}"));
        assertEquals(List.of("id"), PathTemplateVariables.malformedConstraintNames("/m/{id:(?<id>a)}"));
        // The same constraints are fine alone, which is why a check on the constraint by itself is not enough.
        assertEquals(List.of(), PathTemplateVariables.malformedConstraintNames("/m/{id:(?<other>a)}"));
    }

    @Test
    @DisplayName("a template whose variables each pass but whose route pattern does not compile is reported as a whole")
    void unregistrableTemplate() {
        List<PathTemplateVariables.Problem> problems = PathTemplateVariables.problems("/m/{a:(?<b>x)}/{b}");
        assertEquals(
                List.of(new PathTemplateVariables.Problem(PathTemplateVariables.Kind.UNREGISTRABLE, "")), problems);
        String message = PathTemplateVariables.message("Orders.get()", "/m/{a:(?<b>x)}/{b}", problems.get(0));
        assertTrue(message.contains("Orders.get()") && message.contains("/m/{a:(?<b>x)}/{b}"), message);
        assertTrue(message.contains("cannot be compiled into a route pattern"), message);
        assertEquals(List.of(), PathTemplateVariables.problems("/m/{a:(?<c>x)}/{b}"));
    }
}
