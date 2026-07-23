// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor.validate;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.rest.client.processor.ClientInterfaceModel;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * Validates that every non-{@code default} method in a {@code @RestClient} interface carries
 * exactly one supported HTTP verb annotation.
 *
 * <p>Supported verbs: {@code @GET}, {@code @POST}, {@code @PUT}, {@code @DELETE}, {@code @PATCH},
 * {@code @HEAD}. {@code @OPTIONS} is intentionally NOT supported — the runtime scanner
 * ({@code ClientInterfaceScanner.java:217}) skips it, so accepting it at compile time while
 * rejecting it at runtime would be inconsistent.
 *
 * <p>An error is emitted for each non-{@code default} method that has none of the supported
 * verb annotations (including methods with only {@code @OPTIONS}).
 */
public final class HttpVerbValidator {

    private static final String ERROR_MESSAGE =
            "%s is missing a supported HTTP verb annotation (one of @GET/@POST/@PUT/@DELETE/@PATCH/@HEAD)";

    private static final List<String> SUPPORTED_VERB_FQNS = List.of(
            "jakarta.ws.rs.GET",
            "jakarta.ws.rs.POST",
            "jakarta.ws.rs.PUT",
            "jakarta.ws.rs.DELETE",
            "jakarta.ws.rs.PATCH",
            "jakarta.ws.rs.HEAD");

    private final CodegenContext ctx;

    /**
     * Creates a new validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public HttpVerbValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates all non-{@code default} methods in the interface type element directly (not via the
     * model, since the scanner skips verb-less methods from the model).
     *
     * <p>For each non-{@code default} method that lacks a supported HTTP verb annotation, a
     * compiler error is emitted.
     *
     * @param model the interface model (used to obtain the type element)
     * @return {@code true} when no errors were found; {@code false} if errors were emitted
     */
    public boolean validate(ClientInterfaceModel model) {
        TypeElement clientType = model.clientType();
        boolean valid = true;

        for (Element enclosed : clientType.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            // Skip default methods — they do not need an HTTP verb
            if (method.getModifiers().contains(Modifier.DEFAULT)) {
                continue;
            }
            if (!hasSupportedVerb(method)) {
                ctx.diagnostics()
                        .error(
                                method,
                                ERROR_MESSAGE.formatted(method.getSimpleName().toString()));
                valid = false;
            }
        }
        return valid;
    }

    // --- Private helpers ---

    /**
     * Returns {@code true} when the method carries at least one supported HTTP verb annotation.
     *
     * @param method the method to inspect
     * @return {@code true} if a supported verb annotation is present
     */
    private boolean hasSupportedVerb(ExecutableElement method) {
        for (String fqn : SUPPORTED_VERB_FQNS) {
            if (AnnotationMirrors.isPresent(method, fqn)) {
                return true;
            }
        }
        return false;
    }
}
