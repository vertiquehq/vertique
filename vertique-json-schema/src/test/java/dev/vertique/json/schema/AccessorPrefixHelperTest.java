// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S4: the single accessor-prefix-stripping helper's uppercase check, exercised directly through
 * {@link InputPropertyDescriber}'s package-private {@code impliedFieldName}/{@code getterBeanName} —
 * the two call sites that previously stripped a prefix without checking the character that follows it.
 * A getter or setter literally named {@code issue}/{@code settle} must not be mistaken for an accessor
 * of a property {@code sue}/{@code tle}: the character right after {@code is}/{@code set} is lowercase
 * in both, so neither name is actually using the "is" + Capitalized / "set" + Capitalized convention.
 *
 * <p>Reflection, not an end-to-end generated document, is deliberate: routing a real Jackson-bound
 * fixture through these two specific call sites turned out to depend on incidental, unrelated Jackson
 * binding choices (which member becomes the deserializer's bound mutator for a given property) that
 * can mask the very bug this test exists to catch. Testing the helper methods directly is the decisive
 * proof; {@link MetadataParityTest} and {@link MetadataConstraintSourceCoverageTest} independently
 * cover the same helper's effect through real end-to-end generation for the shapes where a real
 * fixture does reach it unambiguously.
 */
class AccessorPrefixHelperTest {

    private static String impliedFieldName(String methodName) throws ReflectiveOperationException {
        Method probe = ProbeMethods.class.getDeclaredMethod(methodName);
        Method impliedFieldName = InputPropertyDescriber.class.getDeclaredMethod("impliedFieldName", Method.class);
        impliedFieldName.setAccessible(true);
        return (String) impliedFieldName.invoke(null, probe);
    }

    private static String getterBeanName(String methodName) throws ReflectiveOperationException {
        Method probe = ProbeMethods.class.getDeclaredMethod(methodName);
        Method getterBeanName = InputPropertyDescriber.class.getDeclaredMethod("getterBeanName", Method.class);
        getterBeanName.setAccessible(true);
        return (String) getterBeanName.invoke(null, probe);
    }

    /** Methods whose names exercise the prefix-stripping helpers, independent of any Jackson binding. */
    private static final class ProbeMethods {
        void settle() {}

        void setLevel() {}

        void with() {}

        int issue() {
            return 0;
        }

        int isActive() {
            return 0;
        }

        int is() {
            return 0;
        }
    }

    @Test
    @DisplayName("S4: a setter literally named settle is not treated as \"set\" + \"tle\"")
    void settleIsNotTreatedAsSetPlusTle() throws ReflectiveOperationException {
        assertEquals("settle", impliedFieldName("settle"));
    }

    @Test
    @DisplayName("S4: a well-formed setter (setLevel) still strips its prefix correctly")
    void wellFormedSetterStillStrips() throws ReflectiveOperationException {
        assertEquals("level", impliedFieldName("setLevel"));
    }

    @Test
    @DisplayName("S4: a bare \"with\" (nothing after the prefix) is left unchanged, not stripped to an empty name")
    void bareWithPrefixIsLeftUnchanged() throws ReflectiveOperationException {
        assertEquals("with", impliedFieldName("with"));
    }

    @Test
    @DisplayName("S4: a getter literally named issue is not treated as \"is\" + \"sue\"")
    void issueIsNotTreatedAsIsPlusSue() throws ReflectiveOperationException {
        assertEquals("issue", getterBeanName("issue"));
    }

    @Test
    @DisplayName("S4: a well-formed getter (isActive) still strips its prefix correctly")
    void wellFormedGetterStillStrips() throws ReflectiveOperationException {
        assertEquals("active", getterBeanName("isActive"));
    }

    @Test
    @DisplayName("S4: a bare \"is\" (nothing after the prefix) is left unchanged, not stripped to an empty name")
    void bareIsPrefixIsLeftUnchanged() throws ReflectiveOperationException {
        assertEquals("is", getterBeanName("is"));
    }
}
