// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.support;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/**
 * Utility methods for deriving Java identifier strings during annotation processing.
 *
 * <p>Provides two main operations:
 * <ul>
 *   <li>{@link #generatedClassName} — flattens a potentially-nested type name and appends a
 *       processor-specific suffix, producing a valid top-level class name
 *   <li>{@link #constantName} — converts a {@code camelCase} identifier to {@code SCREAMING_SNAKE}
 *       for use as a Java constant name
 * </ul>
 *
 * <p>All methods are stateless and thread-safe. This class cannot be instantiated.
 */
public final class Identifiers {

    private Identifiers() {}

    /**
     * Derives a generated class name for a type by walking the enclosing-type chain and joining
     * each enclosing type's simple name with {@code _}, then appending the suffix.
     *
     * <p>{@link TypeElement#getSimpleName()} only returns the innermost name, so nested types must
     * be flattened by walking {@link Element#getEnclosingElement()} to capture the surrounding
     * structure. Without this walk, two nested classes with the same simple name in different
     * enclosing types would collide on the same generated FQN. Examples:
     * <ul>
     *   <li>{@code Foo} with suffix {@code Module} → {@code FooModule}
     *   <li>{@code Outer.Inner} with suffix {@code Module} → {@code Outer_InnerModule}
     *   <li>{@code A.B.C} with suffix {@code Factory} → {@code A_B_CFactory}
     * </ul>
     *
     * @param origin the type element whose name is used as the base; must not be {@code null}
     * @param suffix the suffix to append; must not be {@code null} (pass {@code ""} for no suffix)
     * @return the generated class name, never {@code null} or empty
     */
    public static String generatedClassName(TypeElement origin, String suffix) {
        StringBuilder sb = new StringBuilder(origin.getSimpleName().toString());
        Element enclosing = origin.getEnclosingElement();
        while (enclosing instanceof TypeElement enclosingType) {
            sb.insert(0, enclosingType.getSimpleName().toString() + '_');
            enclosing = enclosingType.getEnclosingElement();
        }
        return sb.append(suffix).toString();
    }

    /**
     * Converts a {@code camelCase} or {@code PascalCase} identifier to {@code SCREAMING_SNAKE_CASE}.
     *
     * <p>Inserts an underscore before each uppercase letter that follows a lowercase letter or
     * digit, then uppercases the entire result. Non-alphanumeric characters (except underscores
     * already present) are replaced with underscores.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code myField} → {@code MY_FIELD}
     *   <li>{@code HTTPResponse} → {@code H_T_T_P_RESPONSE}
     *   <li>{@code getMyValue} → {@code GET_MY_VALUE}
     *   <li>{@code my-field} → {@code MY_FIELD}
     * </ul>
     *
     * @param camel the camelCase or PascalCase identifier to convert; must not be {@code null}
     * @return the SCREAMING_SNAKE_CASE equivalent; never {@code null}
     */
    public static String constantName(String camel) {
        // First replace non-alphanumeric (except underscore) with underscore
        String cleaned = camel.replaceAll("[^a-zA-Z0-9_]", "_");

        // Insert underscore before uppercase letters that follow a lowercase letter or digit
        String withUnderscores = cleaned.replaceAll("([a-z0-9])([A-Z])", "$1_$2");

        return withUnderscores.toUpperCase();
    }

    /**
     * Sanitizes a string into a valid Java identifier by replacing characters that are not valid
     * in a Java identifier (as determined by {@link Character#isJavaIdentifierPart}) with
     * underscores, and ensuring the result starts with a valid identifier start character.
     *
     * <p>If the sanitized result starts with a digit, a leading underscore is prepended. Inputs
     * that resolve to a Java reserved word (e.g., {@code class}, {@code switch}) get a trailing
     * underscore so the result is always a legal identifier per
     * {@link SourceVersion#isName(CharSequence)}.
     *
     * @param raw the raw string to sanitize; must not be {@code null}
     * @return a valid Java identifier; never {@code null} or empty (at minimum returns {@code "__"},
     *         since {@code "_"} alone is a reserved keyword in Java 9+)
     */
    public static String sanitize(String raw) {
        var sb = new StringBuilder(Math.max(raw.length(), 1));
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        if (sb.isEmpty() || !Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        String result = sb.toString();
        return SourceVersion.isName(result) ? result : result + "_";
    }
}
