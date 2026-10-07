// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.config;

import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.AccessPolicyResolver;
import dev.vertique.security.authz.Authorized;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import java.lang.annotation.Annotation;
import java.lang.annotation.AnnotationTypeMismatchException;
import java.lang.annotation.IncompleteAnnotationException;
import java.util.List;

/**
 * The one derivation of a document's classification from the {@link AccessPolicy} its {@link
 * ApiDocs} declares, shared by the configuration checks, the selection of documents and the serving
 * side.
 *
 * <p>A document is {@link ApiDocs.Access#PUBLIC} exactly when its policy resolves to a single
 * {@code @PermitAll}; every other valid policy, {@code @DenyAll} included, is {@link
 * ApiDocs.Access#PROTECTED}. A policy the resolver rejects has no classification.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 */
public final class DocumentPolicies {

    private DocumentPolicies() {}

    /**
     * Classifies a valid policy.
     *
     * @param policy the policy a document declares
     * @return {@link ApiDocs.Access#PUBLIC} for exactly one {@code @PermitAll}, else {@link
     *     ApiDocs.Access#PROTECTED}
     * @throws IllegalArgumentException when the policy is not a valid access policy
     */
    static ApiDocs.Access classify(Class<? extends AccessPolicy> policy) {
        List<Annotation> requirements = AccessPolicyResolver.resolve(policy);
        boolean open = requirements.size() == 1 && requirements.get(0) instanceof PermitAll;
        return open ? ApiDocs.Access.PUBLIC : ApiDocs.Access.PROTECTED;
    }

    /**
     * Classifies a declaration for the selection of documents, failing closed: a policy that is
     * missing from the declaration or is not valid is {@link ApiDocs.Access#PROTECTED}, never public.
     * Such a declaration is refused by the configuration checks before any document is used; those
     * shape checks are the real gate, and this fallback is defence in depth for a caller that
     * classifies without them.
     *
     * @param declaration the declaration
     * @return the classification of its policy, {@link ApiDocs.Access#PROTECTED} when it has none
     */
    static ApiDocs.Access classifyOrProtect(ApiDocs declaration) {
        try {
            return classify(declaration.policy());
        } catch (IllegalArgumentException
                | IncompleteAnnotationException
                | AnnotationTypeMismatchException
                | TypeNotPresentException unclassifiable) {
            return ApiDocs.Access.PROTECTED;
        }
    }

    /**
     * Reports whether a declaring type carries an {@link ApiDocs} whose policy is public, failing
     * closed: a type without the annotation, or whose policy cannot be read or is not valid, is not
     * public.
     *
     * @param declaringType the interface that declares the document
     * @return {@code true} only when its {@code @ApiDocs} policy resolves to a single {@code @PermitAll}
     */
    public static boolean isPublic(Class<?> declaringType) {
        ApiDocs declaration = declaringType.getAnnotation(ApiDocs.class);
        return declaration != null && classifyOrProtect(declaration) == ApiDocs.Access.PUBLIC;
    }

    /**
     * Reports whether a valid policy denies every reader: exactly one {@code @DenyAll}.
     *
     * @param policy the policy a document declares
     * @return {@code true} only for a lone {@code @DenyAll}
     * @throws IllegalArgumentException when the policy is not a valid access policy
     */
    public static boolean isDenyAll(Class<? extends AccessPolicy> policy) {
        List<Annotation> requirements = AccessPolicyResolver.resolve(policy);
        return requirements.size() == 1 && requirements.get(0) instanceof DenyAll;
    }

    /**
     * Reports whether a valid policy requires authentication and nothing more: exactly one
     * {@code @Authorized} that lists no scope.
     *
     * @param policy the policy a document declares
     * @return {@code true} when every principal its scheme authenticates may read the document
     * @throws IllegalArgumentException when the policy is not a valid access policy
     */
    public static boolean isAuthenticatedOnly(Class<? extends AccessPolicy> policy) {
        List<Annotation> requirements = AccessPolicyResolver.resolve(policy);
        return requirements.size() == 1
                && requirements.get(0) instanceof Authorized authorized
                && authorized.scopes().length == 0;
    }
}
