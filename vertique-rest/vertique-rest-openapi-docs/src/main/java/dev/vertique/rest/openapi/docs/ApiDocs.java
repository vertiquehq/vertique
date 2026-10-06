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
 * Enables the OpenAPI document of the application whose declaring interface carries this
 * annotation, and states who may read it. The document is named by the application name.
 *
 * <p>Who may read the document is a typed access policy: {@link #policy()} names an interface that
 * extends {@link AccessPolicy} and declares its requirements with the same annotations a resource
 * method uses ({@code @PermitAll}, {@code @DenyAll}, {@code @RolesAllowed}, {@code @Authorized}, and
 * {@code @RequiresAction}). A policy that is exactly one {@code @PermitAll} makes the document
 * public; every other valid policy, {@code @DenyAll} included, makes it protected. A protected
 * document is served only after the policy has been enforced, so it is never cached by a shared
 * cache and a denied reader gets neither its bytes nor a {@code 304}.
 *
 * <p>{@link #securityScheme()} names the security scheme that authenticates a reader of a protected
 * document. It is empty for a public document and is required for every other policy.
 *
 * <p>The annotation guards the document routes only. It is not API protection: it does not change
 * the access policy of any operation of the application.
 *
 * <p>The annotation is read at runtime by the fully qualified name {@link #ANNOTATION_NAME}, which
 * is the name {@code ApiDocsModuleInstalled.ANNOTATION_NAME} carries in the JAX-RS module.
 *
 * <p>No configuration changes the effect of {@link #policy()}. The annotation is honored only on
 * the declaring interface itself; the same annotation on a superinterface fails compilation. The
 * JAX-RS module and {@code vertique-codegen-jaxrs} recognize the annotation by its fully qualified
 * name and do not depend on this module.
 *
 * <p>An application interface compiled against an older version of this annotation, one that
 * declared {@code access} and {@code rolesAllowed} instead of {@code policy}, fails startup closed,
 * naming the application and its interface, until it is recompiled against this version.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ApiDocs {

    /** The fully qualified name of this annotation, by which the JAX-RS module recognizes it. */
    String ANNOTATION_NAME = "dev.vertique.rest.openapi.docs.ApiDocs";

    /**
     * The access policy of the document routes: a public interface that extends {@link AccessPolicy}
     * and declares its requirements directly. The policy must be a valid access policy, or startup
     * and compilation fail.
     *
     * @return the access policy of the document routes
     */
    Class<? extends AccessPolicy> policy();

    /**
     * The name of the security scheme that authenticates a reader of a protected document: empty
     * exactly when {@link #policy()} is public, and the name of a registered scheme otherwise.
     *
     * @return the scheme name, or an empty string
     */
    String securityScheme() default "";

    /**
     * How a document is classified from its policy. The classification is derived, never declared:
     * it drives caching, serving and the disclosure of an assembled document.
     */
    enum Access {
        /** The policy is exactly one {@code @PermitAll}: any caller may read the document. */
        PUBLIC,
        /** Every other valid policy: the policy is enforced before the document is read. */
        PROTECTED
    }
}
