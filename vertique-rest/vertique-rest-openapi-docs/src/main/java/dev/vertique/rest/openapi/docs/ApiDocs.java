// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables the OpenAPI document of the application whose declaring interface carries this
 * annotation, and states who may read it. The document is named by the application name.
 *
 * <p>The annotation guards the document routes only. It is not API protection: it does not change
 * the access policy of any operation of the application.
 *
 * <p>The annotation is read at runtime by the fully qualified name {@link #ANNOTATION_NAME}, which
 * is the name {@code ApiDocsInstalled.ANNOTATION_NAME} carries in the JAX-RS module.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ApiDocs {

    /** The fully qualified name of this annotation, by which the JAX-RS module recognizes it. */
    String ANNOTATION_NAME = "dev.vertique.rest.openapi.docs.ApiDocs";

    /**
     * Who may read the document routes.
     *
     * @return the access policy of the document routes
     */
    Access access();

    /**
     * The name of the security scheme that authenticates a reader of a {@link Access#PROTECTED}
     * document. Required exactly when {@link #access()} is {@code PROTECTED}.
     *
     * @return the scheme name, or an empty string
     */
    String securityScheme() default "";

    /**
     * The roles allowed to read a {@link Access#PROTECTED} document; every entry is non-blank.
     * Empty means any authenticated reader. Applies to {@code PROTECTED} only.
     *
     * @return the allowed roles
     */
    String[] rolesAllowed() default {};

    /** Who may read the document routes of a documented application. */
    enum Access {
        /** Any caller may read the document. */
        PUBLIC,
        /** Only an authenticated caller, optionally holding one of the allowed roles, may read it. */
        PROTECTED
    }
}
