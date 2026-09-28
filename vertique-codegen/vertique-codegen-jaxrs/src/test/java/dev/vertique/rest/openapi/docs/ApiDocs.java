// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-source stand-in for the documentation module's {@code @ApiDocs}, declared at that
 * annotation's fully qualified name with the same shape, so {@code vertique-codegen-jaxrs}'s
 * compile fixtures can exercise the processor's {@code @ApiDocs} checks without a dependency on
 * the documentation module.
 *
 * <p>The processor recognizes the annotation by its fully qualified name and reads its elements by
 * name, so this stub's element names and defaults are exactly the ones the processor reads:
 * {@code access} with no default, {@code securityScheme} defaulting to {@code ""}, and
 * {@code rolesAllowed} defaulting to an empty array.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface ApiDocs {

    /**
     * Returns who may read the application's documentation.
     *
     * @return the documentation access level
     */
    Access access();

    /**
     * Returns the security scheme that protects the documentation; required and non-blank exactly
     * when {@link #access()} is {@link Access#PROTECTED}.
     *
     * @return the security scheme name, or {@code ""} when none is set
     */
    String securityScheme() default "";

    /**
     * Returns the roles allowed to read protected documentation; allowed only when
     * {@link #access()} is {@link Access#PROTECTED}, every entry non-blank, and empty meaning any
     * authenticated caller.
     *
     * @return the allowed roles, or an empty array when none is set
     */
    String[] rolesAllowed() default {};

    /** The documentation access levels. */
    enum Access {
        /** Anyone may read the documentation. */
        PUBLIC,
        /** Only callers authenticated through {@link ApiDocs#securityScheme()} may read it. */
        PROTECTED
    }
}
