// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.security.authz.AccessPolicy;
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
 * {@code policy} with no default, and {@code securityScheme} defaulting to {@code ""}. The stub
 * declares no other authorization element, so an application that still sets one fails javac's own
 * element resolution.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface ApiDocs {

    /**
     * Returns the access policy that decides who may read the application's documentation.
     *
     * @return the policy type
     */
    Class<? extends AccessPolicy> policy();

    /**
     * Returns the security scheme that protects the documentation; empty for a public policy and
     * supplied for every other policy.
     *
     * @return the security scheme name, or {@code ""} when none is set
     */
    String securityScheme() default "";

    /** The documentation access levels derived from the policy. */
    enum Access {
        /** Anyone may read the documentation. */
        PUBLIC,
        /** Only callers admitted by the policy may read it. */
        PROTECTED
    }
}
