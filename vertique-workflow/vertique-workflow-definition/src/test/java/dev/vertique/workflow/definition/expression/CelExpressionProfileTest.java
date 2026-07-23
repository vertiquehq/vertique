// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.workflow.definition.expression.cel.CelExpressionProfile;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies {@link CelExpressionProfile} against all test scenarios specified in the Slice E plan:
 * <ul>
 *   <li>Happy-path operator groups (boolean composition, comparisons, membership, strings, lists,
 *       nested property access, {@code has()} macro)</li>
 *   <li>Sandbox-escape attempts (must throw {@link ExpressionParseException} or
 *       {@link ExpressionEvaluationException})</li>
 *   <li>Bounds enforcement (source length, AST depth, comprehension iteration limit)</li>
 *   <li>Result-type assertion for {@link ExpressionProfile#evaluateBoolean}</li>
 *   <li>Named-condition variable injection</li>
 *   <li>Determinism: same source → same canonical form and fingerprint; whitespace-only changes
 *       → same fingerprint; parenthesis around entire expression → same fingerprint; semantic
 *       change → different fingerprint (FR-WF-DEF-045/049)</li>
 * </ul>
 *
 * <p>All assertions go through the {@link ExpressionProfile} interface — no CEL-internal types
 * are imported or referenced here.
 */
class CelExpressionProfileTest {

    private ExpressionProfile profile;
    private ExpressionEnv boolEnv;

    /** Simple state POJO for tests. */
    record TestState(String status, int priority, List<String> tags, Map<String, Object> review) {}

    @BeforeEach
    void setUp() {
        // WorkflowDefinitionMapperFactory has package-private constructor — mock it and stub
        // jsonMapper() to return a standard ObjectMapper suitable for test state conversion.
        WorkflowDefinitionMapperFactory mockFactory = Mockito.mock(WorkflowDefinitionMapperFactory.class);
        Mockito.when(mockFactory.jsonMapper()).thenReturn(new ObjectMapper());
        profile = new CelExpressionProfile();
        boolEnv = new ExpressionEnv(TestState.class, Set.of(), Set.of());
    }

    // --- Happy-path operators ---

    @Nested
    @DisplayName("Boolean composition operators")
    class BooleanComposition {

        @Test
        @DisplayName("&& (and) — both conditions true")
        void andBothTrue() {
            CompiledExpression expr = profile.compile("state.priority > 3 && state.status == 'high'", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("priority", 5L, "status", "high"));
            assertThat(profile.evaluateBoolean(expr, env)).isTrue();
        }

        @Test
        @DisplayName("&& (and) — one condition false")
        void andOneFalse() {
            CompiledExpression expr = profile.compile("state.priority > 3 && state.status == 'high'", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("priority", 2L, "status", "high"));
            assertThat(profile.evaluateBoolean(expr, env)).isFalse();
        }

        @Test
        @DisplayName("|| (or) — first condition true")
        void orFirstTrue() {
            CompiledExpression expr = profile.compile("state.status == 'high' || state.priority > 10", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("status", "high", "priority", 3L));
            assertThat(profile.evaluateBoolean(expr, env)).isTrue();
        }

        @Test
        @DisplayName("! (not) — negation of true")
        void notTrue() {
            CompiledExpression expr = profile.compile("!(state.status == 'low')", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("status", "high"));
            assertThat(profile.evaluateBoolean(expr, env)).isTrue();
        }
    }

    @Nested
    @DisplayName("Comparison operators")
    class Comparisons {

        @Test
        @DisplayName("== equal")
        void equalityMatch() {
            CompiledExpression expr = profile.compile("state.status == 'approved'", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("status", "approved"));
            assertThat(profile.evaluateBoolean(expr, env)).isTrue();
        }

        @Test
        @DisplayName("!= not equal")
        void notEqual() {
            CompiledExpression expr = profile.compile("state.status != 'pending'", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("status", "approved"));
            assertThat(profile.evaluateBoolean(expr, env)).isTrue();
        }

        @Test
        @DisplayName("< less than")
        void lessThan() {
            CompiledExpression expr = profile.compile("state.priority < 5", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("priority", 3L));
            assertThat(profile.evaluateBoolean(expr, env)).isTrue();
        }

        @Test
        @DisplayName("<= less than or equal")
        void lessThanOrEqual() {
            CompiledExpression expr = profile.compile("state.priority <= 5", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("priority", 5L));
            assertThat(profile.evaluateBoolean(expr, env)).isTrue();
        }

        @Test
        @DisplayName("> greater than")
        void greaterThan() {
            CompiledExpression expr = profile.compile("state.priority > 2", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("priority", 5L));
            assertThat(profile.evaluateBoolean(expr, env)).isTrue();
        }

        @Test
        @DisplayName(">= greater than or equal")
        void greaterThanOrEqual() {
            CompiledExpression expr = profile.compile("state.priority >= 5", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("priority", 5L));
            assertThat(profile.evaluateBoolean(expr, env)).isTrue();
        }
    }

    @Nested
    @DisplayName("Membership operator (in)")
    class Membership {

        @Test
        @DisplayName("'high' in state.tags — member present")
        void memberPresent() {
            CompiledExpression expr = profile.compile("'high' in state.tags", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("tags", List.of("high", "urgent")));
            assertThat(profile.evaluateBoolean(expr, env)).isTrue();
        }

        @Test
        @DisplayName("'low' in state.tags — member absent")
        void memberAbsent() {
            CompiledExpression expr = profile.compile("'low' in state.tags", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("tags", List.of("high", "urgent")));
            assertThat(profile.evaluateBoolean(expr, env)).isFalse();
        }

        @Test
        @DisplayName("state.priority in list literal ['high', 'critical'] — string in list")
        void stateValueInListLiteral() {
            CompiledExpression expr = profile.compile("state.priority in ['high', 'critical']", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("priority", "critical"));
            assertThat(profile.evaluateBoolean(expr, env)).isTrue();
        }
    }

    @Nested
    @DisplayName("String literal comparison")
    class StringLiterals {

        @Test
        @DisplayName("state.status == string literal matches")
        void stringLiteralMatch() {
            CompiledExpression expr = profile.compile("state.status == 'approved'", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("status", "approved"));
            assertThat(profile.evaluateBoolean(expr, env)).isTrue();
        }

        @Test
        @DisplayName("compile a string-typed literal — resultType is STRING")
        void stringLiteralExpressionResultType() {
            CompiledExpression expr = profile.compile("'hello'", boolEnv);
            assertThat(expr.resultType()).isEqualTo(ExpressionType.STRING);
        }
    }

    @Nested
    @DisplayName("Nested property access")
    class NestedPropertyAccess {

        @Test
        @DisplayName("state.review.riskLevel == 'high' — nested map access")
        void nestedMapAccess() {
            CompiledExpression expr = profile.compile("state.review.riskLevel == 'high'", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("review", Map.of("riskLevel", "high")));
            assertThat(profile.evaluateBoolean(expr, env)).isTrue();
        }
    }

    @Nested
    @DisplayName("has() macro for existence checks")
    class HasMacro {

        @Test
        @DisplayName("has(state.status) — field present")
        void hasFieldPresent() {
            CompiledExpression expr = profile.compile("has(state.status)", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("status", "active"));
            assertThat(profile.evaluateBoolean(expr, env)).isTrue();
        }

        @Test
        @DisplayName("has(state.missing) — field absent")
        void hasFieldAbsent() {
            CompiledExpression expr = profile.compile("has(state.missing)", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of("status", "active"));
            assertThat(profile.evaluateBoolean(expr, env)).isFalse();
        }
    }

    // --- Sandbox-escape attempts ---

    @Nested
    @DisplayName("Sandbox-escape attempts — must throw")
    class SandboxEscapes {

        @Test
        @DisplayName("now() — function not declared in env → ExpressionParseException at compile time")
        void nowFunctionRejected() {
            assertThatThrownBy(() -> profile.compile("now()", boolEnv))
                    .isInstanceOf(ExpressionParseException.class)
                    .satisfies(ex ->
                            assertThat(((ExpressionParseException) ex).source()).isEqualTo("now()"));
        }

        @Test
        @DisplayName("state.toString() — member call on dyn not supported → ExpressionParseException")
        void toStringMethodCallRejected() {
            // CEL type-checker rejects method invocations on dynamic map values
            assertThatThrownBy(() -> profile.compile("state.toString()", boolEnv))
                    .isInstanceOf(ExpressionParseException.class);
        }

        @Test
        @DisplayName("1 / 0 — division by zero detected at evaluation → ExpressionEvaluationException")
        void divisionByZeroAtEvalTime() {
            // 1 / 0 type-checks fine (INT / INT -> INT) but fails at eval time
            CompiledExpression expr = profile.compile("1 / 0 == 0", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of());
            assertThatThrownBy(() -> profile.evaluate(expr, env)).isInstanceOf(ExpressionEvaluationException.class);
        }
    }

    // --- Bounds enforcement ---

    @Nested
    @DisplayName("Bounds enforcement")
    class BoundsEnforcement {

        @Test
        @DisplayName("source exceeding maxExpressionCodePointSize → ExpressionParseException")
        void sourceTooLong() {
            // Build an expression longer than 2000 code points
            String longExpr = "state.x == 1" + " || state.x == 2".repeat(200);
            assertThatThrownBy(() -> profile.compile(longExpr, boolEnv)).isInstanceOf(ExpressionParseException.class);
        }

        @Test
        @DisplayName("AST depth exceeding 30 levels → ExpressionParseException")
        void astDepthExceeded() {
            // Build a right-associative chain of &&: a && (b && (c && ...)) — each && adds one
            // level of nesting in the AST. 35 levels exceeds the depth limit of 30.
            // Leaf expression uses state.status == 'x' (string comparisons avoid int/long issues).
            String leaf = "state.status == 'x'";
            String deepExpr = leaf;
            for (int i = 0; i < 35; i++) {
                deepExpr = leaf + " && (" + deepExpr + ")";
            }
            final String expr = deepExpr;
            assertThatThrownBy(() -> profile.compile(expr, boolEnv)).isInstanceOf(ExpressionParseException.class);
        }

        @Test
        @DisplayName("comprehension iteration limit exceeded → ExpressionEvaluationException")
        void comprehensionIterationLimitExceeded() {
            // Use the all() macro with a list larger than comprehensionMaxIterations (1000)
            ExpressionEnv env = new ExpressionEnv(TestState.class, Set.of(), Set.of());
            CompiledExpression expr = profile.compile("state.items.all(x, x > 0)", env);

            // Build a list with 1100 Long items — exceeds the 1000-iteration limit
            List<Long> bigList = new java.util.ArrayList<>();
            for (long i = 1; i <= 1100; i++) {
                bigList.add(i);
            }
            Map<String, Object> evalEnv = Map.of("state", Map.of("items", bigList));

            assertThatThrownBy(() -> profile.evaluate(expr, evalEnv)).isInstanceOf(ExpressionEvaluationException.class);
        }
    }

    // --- Result-type assertion ---

    @Nested
    @DisplayName("Result-type assertions")
    class ResultTypeAssertions {

        @Test
        @DisplayName("compile 'high' → resultType STRING")
        void stringLiteralResultType() {
            CompiledExpression expr = profile.compile("'high'", boolEnv);
            assertThat(expr.resultType()).isEqualTo(ExpressionType.STRING);
        }

        @Test
        @DisplayName("evaluateBoolean on STRING-typed expression → IllegalStateException")
        void evaluateBooleanOnStringExprThrows() {
            CompiledExpression stringExpr = profile.compile("'high'", boolEnv);
            Map<String, Object> env = Map.of("state", Map.of());
            assertThatThrownBy(() -> profile.evaluateBoolean(stringExpr, env))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BOOLEAN");
        }

        @Test
        @DisplayName("compile state.x > 5 → resultType BOOLEAN")
        void booleanExprResultType() {
            CompiledExpression expr = profile.compile("state.x > 5", boolEnv);
            assertThat(expr.resultType()).isEqualTo(ExpressionType.BOOLEAN);
        }

        @Test
        @DisplayName("compile 42 → resultType NUMBER")
        void numberLiteralResultType() {
            CompiledExpression expr = profile.compile("42", boolEnv);
            assertThat(expr.resultType()).isEqualTo(ExpressionType.NUMBER);
        }
    }

    // --- Named conditions ---

    @Nested
    @DisplayName("Named condition variables")
    class NamedConditions {

        @Test
        @DisplayName("expression referencing named condition — evaluates true when condition is true")
        void namedConditionTrue() {
            ExpressionEnv env = new ExpressionEnv(TestState.class, Set.of("lowRiskAutoApprove"), Set.of());
            CompiledExpression expr = profile.compile("state.x > 5 || lowRiskAutoApprove", env);

            Map<String, Object> evalEnv = Map.of("state", Map.of("x", 3L), "lowRiskAutoApprove", Boolean.TRUE);
            assertThat(profile.evaluateBoolean(expr, evalEnv)).isTrue();
        }

        @Test
        @DisplayName("expression referencing named condition — evaluates false when both false")
        void namedConditionFalse() {
            ExpressionEnv env = new ExpressionEnv(TestState.class, Set.of("lowRiskAutoApprove"), Set.of());
            CompiledExpression expr = profile.compile("state.x > 5 || lowRiskAutoApprove", env);

            Map<String, Object> evalEnv = Map.of("state", Map.of("x", 3L), "lowRiskAutoApprove", Boolean.FALSE);
            assertThat(profile.evaluateBoolean(expr, evalEnv)).isFalse();
        }

        @Test
        @DisplayName("expression referencing named condition — evaluates true when x > 5")
        void namedConditionXTrue() {
            ExpressionEnv env = new ExpressionEnv(TestState.class, Set.of("lowRiskAutoApprove"), Set.of());
            CompiledExpression expr = profile.compile("state.x > 5 || lowRiskAutoApprove", env);

            Map<String, Object> evalEnv = Map.of("state", Map.of("x", 10L), "lowRiskAutoApprove", Boolean.FALSE);
            assertThat(profile.evaluateBoolean(expr, evalEnv)).isTrue();
        }
    }

    // --- Determinism (FR-WF-DEF-006) ---

    @Nested
    @DisplayName("Determinism (FR-WF-DEF-006)")
    class Determinism {

        @Test
        @DisplayName("two compilations of same source → equal canonicalForm")
        void sameSourceSameCanonicalForm() {
            String source = "state.x > 5 && state.status == 'approved'";
            CompiledExpression a = profile.compile(source, boolEnv);
            CompiledExpression b = profile.compile(source, boolEnv);
            assertThat(a.canonicalForm()).isEqualTo(b.canonicalForm());
        }

        @Test
        @DisplayName("two compilations of same source → equal fingerprint")
        void sameSourceSameFingerprint() {
            String source = "state.x > 5 && state.status == 'approved'";
            CompiledExpression a = profile.compile(source, boolEnv);
            CompiledExpression b = profile.compile(source, boolEnv);
            assertThat(profile.fingerprint(a)).isEqualTo(profile.fingerprint(b));
        }

        @Test
        @DisplayName("whitespace-only changes → same fingerprint (AC #8)")
        void whitespaceOnlyChangesSameFingerprint() {
            CompiledExpression a = profile.compile("state.x > 5", boolEnv);
            CompiledExpression b = profile.compile("state.x  >  5", boolEnv);
            assertThat(profile.fingerprint(a)).isEqualTo(profile.fingerprint(b));
        }

        @Test
        @DisplayName("parenthesis around entire expression → same fingerprint (AST identical)")
        void parenthesisAroundExpression() {
            CompiledExpression a = profile.compile("state.x > 5", boolEnv);
            CompiledExpression b = profile.compile("(state.x > 5)", boolEnv);
            assertThat(profile.fingerprint(a)).isEqualTo(profile.fingerprint(b));
        }

        @Test
        @DisplayName("semantic change → different fingerprint")
        void semanticChangeDifferentFingerprint() {
            CompiledExpression a = profile.compile("state.x > 5", boolEnv);
            CompiledExpression b = profile.compile("state.x > 10", boolEnv);
            assertThat(profile.fingerprint(a)).isNotEqualTo(profile.fingerprint(b));
        }
    }

    // --- ExpressionParseException carries source ---

    @Test
    @DisplayName("ExpressionParseException carries the original source text")
    void parseExceptionCarriesSource() {
        String badSource = "unknownFunction()";
        assertThatThrownBy(() -> profile.compile(badSource, boolEnv))
                .isInstanceOf(ExpressionParseException.class)
                .satisfies(ex ->
                        assertThat(((ExpressionParseException) ex).source()).isEqualTo(badSource));
    }
}
