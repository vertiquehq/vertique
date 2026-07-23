// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.expression;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.workflow.definition.expression.cel.CelExpressionProfile;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sanity-checks the {@link ExpressionProfile} interface contract and verifies that
 * {@link CelExpressionProfile} satisfies the Dagger injection requirements ({@link Singleton} scope
 * and an {@link Inject}-annotated constructor).
 *
 * <p>These tests drive the interface surface only — they do not import any CEL-internal type.
 */
class ExpressionProfileContractTest {

    @Test
    @DisplayName("CelExpressionProfile is annotated @Singleton")
    void celExpressionProfileIsSingleton() {
        assertThat(CelExpressionProfile.class.isAnnotationPresent(Singleton.class))
                .as("CelExpressionProfile must be @Singleton so Dagger wires it as a single instance")
                .isTrue();
    }

    @Test
    @DisplayName("CelExpressionProfile has an @Inject-annotated constructor")
    void celExpressionProfileHasInjectConstructor() {
        boolean hasInjectConstructor = false;
        for (Constructor<?> ctor : CelExpressionProfile.class.getDeclaredConstructors()) {
            if (ctor.isAnnotationPresent(Inject.class)) {
                hasInjectConstructor = true;
                break;
            }
        }
        assertThat(hasInjectConstructor)
                .as("CelExpressionProfile must have an @Inject constructor for Dagger")
                .isTrue();
    }

    @Test
    @DisplayName("CelExpressionProfile implements ExpressionProfile interface")
    void celExpressionProfileImplementsInterface() {
        assertThat(ExpressionProfile.class.isAssignableFrom(CelExpressionProfile.class))
                .as("CelExpressionProfile must implement ExpressionProfile")
                .isTrue();
    }

    @Test
    @DisplayName("CompiledExpression record validates non-null invariants at construction")
    void compiledExpressionValidatesNonNull() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new CompiledExpression(null, ExpressionType.BOOLEAN, new Object()))
                .isInstanceOf(IllegalArgumentException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new CompiledExpression("", ExpressionType.BOOLEAN, new Object()))
                .isInstanceOf(IllegalArgumentException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CompiledExpression("expr", null, new Object()))
                .isInstanceOf(IllegalArgumentException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new CompiledExpression("expr", ExpressionType.BOOLEAN, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ExpressionEnv validates non-null and copies sets defensively")
    void expressionEnvValidatesNonNull() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ExpressionEnv(null, java.util.Set.of(), java.util.Set.of()))
                .isInstanceOf(IllegalArgumentException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ExpressionEnv(String.class, null, java.util.Set.of()))
                .isInstanceOf(IllegalArgumentException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ExpressionEnv(String.class, java.util.Set.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ExpressionEnv copies sets defensively (caller mutation does not affect record)")
    void expressionEnvCopiesSetsDefensively() {
        java.util.Set<String> mutableNames = new java.util.HashSet<>();
        mutableNames.add("lowRiskAutoApprove");

        ExpressionEnv env = new ExpressionEnv(String.class, mutableNames, java.util.Set.of());
        mutableNames.add("newEntry"); // mutate after construction

        assertThat(env.namedConditionIds())
                .containsExactly("lowRiskAutoApprove")
                .doesNotContain("newEntry");
    }
}
