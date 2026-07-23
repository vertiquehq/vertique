// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

/**
 * Parameter classification slots for JAX-RS resource method parameters, mirroring
 * {@code ResourceMethodMeta.ParamSource} from the runtime module.
 *
 * <p>The 11 values correspond exactly to the ordered dispatch slots in
 * {@link JaxRsParamClassifier#classify} and in the runtime
 * {@code ResourceScanner.resolveParams}. The dispatch order in the classifier must
 * be kept in sync with the runtime implementation to guarantee compile-time/runtime parity.
 *
 * <p>Promoted from the package-private nested {@code BodyFormValidator.ParamSource} enum
 * (CG-010) so that both the compile-time validator pipeline and the
 * {@link EffectiveParamContract} record can reference it without coupling to a specific
 * validator class.
 */
public enum JaxRsParamSource {
    /**
     * Resolved via the {@code RestContextResolver} chain; carries the declared type.
     *
     * <p>Matches any parameter annotated with {@code @Context} or whose declared type is
     * {@code RoutingContext} (or a subtype), <em>exactly</em>
     * {@code jakarta.ws.rs.core.SecurityContext}, or any {@link dev.vertique.core.context.ContextValue}
     * subtype (including the framework {@code dev.vertique.security.SecurityContext}). The
     * JAX-RS {@code SecurityContext} is matched exactly — the framework resolver only bridges that
     * exact type, so subtypes are not injectable.
     * Emits a {@code support.resolveContext(CTX_i, ctx, resourceClass, methodName)} call in
     * the generated execution plan (no per-request {@code Class.forName}).
     */
    CONTEXT,
    /** Injected {@code dev.vertique.rest.core.request.RequestPreconditions}. */
    PRECONDITIONS,
    /** Composite parameter object ({@code @BeanParam} or {@code @RequestParams}-annotated type). */
    BEAN_PARAM,
    /** {@code @PathParam}-annotated scalar. */
    PATH,
    /** {@code @QueryParam}-annotated scalar. */
    QUERY,
    /** {@code @HeaderParam}-annotated scalar. */
    HEADER,
    /** {@code @CookieParam}-annotated scalar. */
    COOKIE,
    /** {@code @FormParam}-annotated scalar. */
    FORM,
    /** Unannotated {@code List<FileUpload>}. */
    FILE_UPLOADS,
    /** Unannotated {@code List<EntityPart>}. */
    ENTITY_PARTS,
    /** Unannotated body parameter (JSON deserialization). */
    BODY
}
