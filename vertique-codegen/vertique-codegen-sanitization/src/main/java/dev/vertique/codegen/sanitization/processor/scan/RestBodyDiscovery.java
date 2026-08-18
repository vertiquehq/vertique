// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.sanitization.processor.scan;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.JaxRsAnnotations;
import dev.vertique.rest.core.context.RestContextTypes;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Discovers server-side {@code @BODY} parameter types from JAX-RS resource methods in the current
 * compilation unit, mirroring the runtime classification logic of
 * {@code ResourceScanner.resolveParams()} (the authoritative server-side body classifier).
 *
 * <p>Discovery algorithm:
 * <ol>
 *   <li>Walk methods annotated with any HTTP-verb annotation ({@code @GET}, {@code @POST},
 *       {@code @PUT}, {@code @DELETE}, {@code @PATCH}, {@code @HEAD}) via
 *       {@link RoundEnvironment#getElementsAnnotatedWith(TypeElement)}.</li>
 *   <li>Filter to methods whose enclosing {@link TypeElement} carries {@code @Path} (directly or
 *       via meta-annotation) — only resource classes host real body parameters.</li>
 *   <li>For each surviving method, classify each parameter; a parameter is a {@code @BODY}
 *       candidate when it carries none of the exclusion markers (context types, JAX-RS binding
 *       annotations, multipart constructs).</li>
 *   <li>For {@code Collection<E>} or {@code E[]} body parameters, record the element type
 *       {@code E}; otherwise record the raw parameter type.</li>
 *   <li>Apply the scalar-root filter: drop types that are scalars (strings, primitives, boxed
 *       types, {@code UUID}, java.time types, enums) because they carry no map structure to
 *       switch on.</li>
 * </ol>
 *
 * <p>Context parameters are excluded from body discovery via two complementary rules that mirror
 * the runtime {@code ResourceScanner} single-CONTEXT source classification:
 * <ul>
 *   <li>Any parameter carrying {@code @Context} (regardless of its declared type) is excluded.
 *       This covers both the framework-injectable types ({@code RoutingContext},
 *       {@code SecurityContext}) and reserved-but-unsupported JAX-RS types
 *       (e.g. {@code UriInfo}).</li>
 *   <li>Any parameter whose declared type is assignable to
 *       {@link dev.vertique.core.context.ContextValue} is excluded, even when the parameter is
 *       unannotated (auto-injected context values are resolved by the dispatch layer, not
 *       treated as request bodies).</li>
 * </ul>
 *
 * <p>This scanner is intentionally conservative: it scans only what the JAX-RS runtime would
 * see at deploy time. Unknown annotation types (not on the processor classpath) are ignored
 * gracefully.
 */
public final class RestBodyDiscovery {

    // --- HTTP verb annotation FQNs ---
    // Mirrors ResourceScanner.resolveHttpMethod in vertique-rest-jaxrs — every verb the
    // runtime treats as a resource method must be discoverable here, otherwise body DTOs
    // referenced only from that verb would not get a generated processor.
    private static final List<String> HTTP_VERB_FQNS = List.of(
            "jakarta.ws.rs.GET",
            "jakarta.ws.rs.POST",
            "jakarta.ws.rs.PUT",
            "jakarta.ws.rs.DELETE",
            "jakarta.ws.rs.PATCH",
            "jakarta.ws.rs.HEAD",
            "jakarta.ws.rs.OPTIONS");

    // --- JAX-RS path annotation FQN ---
    private static final String PATH_FQN = "jakarta.ws.rs.Path";

    // --- Exclusion: JAX-RS parameter binding annotation FQNs ---
    private static final List<String> PARAM_ANNOTATION_FQNS = List.of(
            "jakarta.ws.rs.PathParam",
            "jakarta.ws.rs.QueryParam",
            "jakarta.ws.rs.HeaderParam",
            "jakarta.ws.rs.CookieParam",
            "jakarta.ws.rs.FormParam",
            "jakarta.ws.rs.BeanParam");

    // --- Exclusion: context type FQNs ---
    // ROUTING_CONTEXT_FQN and JAXRS_SECURITY_CONTEXT_FQN sourced from RestContextTypes
    // (single source of truth); CONTEXT_VALUE_FQN likewise.
    private static final String ROUTING_CONTEXT_FQN = RestContextTypes.ROUTING_CONTEXT_FQN;
    private static final String SECURITY_CONTEXT_FQN = "dev.vertique.security.SecurityContext";
    private static final String JAXRS_SECURITY_CONTEXT_FQN = RestContextTypes.JAXRS_SECURITY_CONTEXT_FQN;
    private static final String CONTEXT_VALUE_FQN = RestContextTypes.CONTEXT_VALUE_FQN;
    private static final String REQUEST_PRECONDITIONS_FQN = "dev.vertique.rest.core.request.RequestPreconditions";

    // --- Exclusion: @RequestParams marker (framework @BeanParam analogue) ---
    private static final String REQUEST_PARAMS_FQN = "dev.vertique.rest.core.request.RequestParams";

    // --- Exclusion: multipart types ---
    private static final String FILE_UPLOAD_FQN = "io.vertx.ext.web.FileUpload";
    private static final String ENTITY_PART_FQN = "jakarta.ws.rs.core.EntityPart";

    // --- Collection base ---
    private static final String COLLECTION_FQN = "java.util.Collection";

    private final CodegenContext ctx;

    /**
     * Constructs a {@code RestBodyDiscovery} scanner bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public RestBodyDiscovery(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns the set of {@link TypeElement}s that are direct {@code @BODY} parameter types of
     * resource methods in the current compilation round, filtered through the scalar-root filter.
     *
     * @param roundEnv the current round environment; must not be {@code null}
     * @return the set of discovered body type roots; never {@code null}, may be empty
     */
    public Set<TypeElement> findRoots(RoundEnvironment roundEnv) {
        Set<TypeElement> roots = new LinkedHashSet<>();

        for (String verbFqn : HTTP_VERB_FQNS) {
            TypeElement verbAnnotation = ctx.elements().getTypeElement(verbFqn);
            if (verbAnnotation == null) continue;
            for (Element element : roundEnv.getElementsAnnotatedWith(verbAnnotation)) {
                if (!(element instanceof ExecutableElement method)) continue;
                if (!(method.getEnclosingElement() instanceof TypeElement enclosing)) continue;
                if (!hasPathAnnotation(enclosing)) continue;
                collectBodyRoots(method, roots);
            }
        }

        return roots;
    }

    /**
     * Extracts body parameter roots from the given resource method, applying the exclusion rules
     * that mirror {@code ResourceScanner.resolveParams()}.
     *
     * <p>A parameter is excluded when any of the following holds:
     * <ul>
     *   <li>It carries {@code @Context} — covers framework-injectable types, reserved JAX-RS
     *       types, and non-injectable arbitrary classes that the codegen validator rejects.</li>
     *   <li>Its declared type is a context type (assignable to {@code RoutingContext},
     *       {@code SecurityContext}, or {@link dev.vertique.core.context.ContextValue}).</li>
     *   <li>It carries a JAX-RS parameter-binding annotation ({@code @PathParam},
     *       {@code @QueryParam}, etc.) or is a {@code @RequestParams}-annotated bean.</li>
     *   <li>It is a multipart upload type ({@code List<FileUpload>},
     *       {@code List<EntityPart>}).</li>
     * </ul>
     *
     * @param method the resource method
     * @param roots  the accumulator set to add discovered body types to
     */
    private void collectBodyRoots(ExecutableElement method, Set<TypeElement> roots) {
        for (VariableElement param : method.getParameters()) {
            TypeMirror paramType = param.asType();

            if (AnnotationMirrors.isPresent(param, JaxRsAnnotations.CONTEXT)) continue;
            if (isContextType(paramType)) continue;
            if (isRequestParamsType(paramType)) continue;
            if (hasParamAnnotation(param)) continue;
            if (isFileUploadList(paramType)) continue;
            if (isEntityPartList(paramType)) continue;

            // This is a @BODY candidate — resolve element type for collections/arrays
            TypeMirror rootType = resolveBodyElementType(paramType);
            if (rootType == null) continue;
            if (AnnotationCollector.isScalarOrEnum(rootType)) continue;
            if (rootType.getKind() != TypeKind.DECLARED) continue;
            if (!(((DeclaredType) rootType).asElement() instanceof TypeElement te)) continue;
            roots.add(te);
        }
    }

    /**
     * Returns {@code true} if the enclosing type of a resource method carries the {@code @Path}
     * annotation directly or via meta-annotation.
     *
     * @param type the enclosing type element
     * @return {@code true} when the type is a JAX-RS resource class
     */
    private boolean hasPathAnnotation(TypeElement type) {
        if (AnnotationMirrors.isPresent(type, PATH_FQN)) return true;
        // Check meta-annotation: any annotation on the type that is itself annotated with @Path
        for (javax.lang.model.element.AnnotationMirror mirror : type.getAnnotationMirrors()) {
            Element annotationType = mirror.getAnnotationType().asElement();
            if (AnnotationMirrors.isPresent(annotationType, PATH_FQN)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the parameter type is a framework context type that should be
     * excluded from body classification.
     *
     * <p>Returns {@code true} for:
     * <ul>
     *   <li>Exact matches against {@code RoutingContext}, {@code SecurityContext} (Vertique),
     *       {@code SecurityContext} (JAX-RS), and {@code RequestPreconditions}.</li>
     *   <li>Types assignable to {@code RoutingContext} (subclasses).</li>
     *   <li>Types assignable to {@link dev.vertique.core.context.ContextValue} — covers all
     *       unannotated auto-injected context parameters, matching the runtime
     *       {@code ResourceScanner} CONTEXT classification (FR-REST-168).</li>
     * </ul>
     *
     * @param type the parameter type mirror
     * @return {@code true} for context types
     */
    private boolean isContextType(TypeMirror type) {
        String fqn = AnnotationCollector.typeFqn(type);
        if (ROUTING_CONTEXT_FQN.equals(fqn)) return true;
        if (SECURITY_CONTEXT_FQN.equals(fqn)) return true;
        if (JAXRS_SECURITY_CONTEXT_FQN.equals(fqn)) return true;
        if (REQUEST_PRECONDITIONS_FQN.equals(fqn)) return true;
        // Assignability check for RoutingContext subclasses
        TypeElement routingCtx = ctx.elements().getTypeElement(ROUTING_CONTEXT_FQN);
        if (routingCtx != null) {
            TypeMirror routingErasure = ctx.types().erasure(routingCtx.asType());
            TypeMirror paramErasure = ctx.types().erasure(type);
            if (ctx.types().isAssignable(paramErasure, routingErasure)) return true;
        }
        // Assignability check for ContextValue subtypes (FR-REST-168 parity with runtime classifier)
        TypeElement contextValueEl = ctx.elements().getTypeElement(CONTEXT_VALUE_FQN);
        if (contextValueEl != null) {
            TypeMirror contextValueErasure = ctx.types().erasure(contextValueEl.asType());
            TypeMirror paramErasure = ctx.types().erasure(type);
            if (ctx.types().isAssignable(paramErasure, contextValueErasure)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the parameter type is annotated with {@code @RequestParams}
     * (the framework's {@code @BeanParam} analogue).
     *
     * @param type the parameter type mirror
     * @return {@code true} when the type carries {@code @RequestParams}
     */
    private boolean isRequestParamsType(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return false;
        Element typeElement = ((DeclaredType) type).asElement();
        return AnnotationMirrors.isPresent(typeElement, REQUEST_PARAMS_FQN);
    }

    /**
     * Returns {@code true} if the parameter carries any JAX-RS binding annotation
     * ({@code @PathParam}, {@code @QueryParam}, etc.) or {@code @BeanParam}.
     *
     * @param param the parameter element
     * @return {@code true} when a binding annotation is present
     */
    private boolean hasParamAnnotation(VariableElement param) {
        for (String fqn : PARAM_ANNOTATION_FQNS) {
            if (AnnotationMirrors.isPresent(param, fqn)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the parameter type is {@code List<FileUpload>}.
     *
     * @param type the parameter type mirror
     * @return {@code true} for multipart file-upload list parameters
     */
    private boolean isFileUploadList(TypeMirror type) {
        return isListOf(type, FILE_UPLOAD_FQN);
    }

    /**
     * Returns {@code true} if the parameter type is {@code List<EntityPart>}.
     *
     * @param type the parameter type mirror
     * @return {@code true} for multipart entity-part list parameters
     */
    private boolean isEntityPartList(TypeMirror type) {
        return isListOf(type, ENTITY_PART_FQN);
    }

    /**
     * Returns {@code true} if {@code type} is a parameterized {@link java.util.Collection} type
     * (or {@link java.util.List}) whose element type FQN matches {@code elementFqn}.
     *
     * <p>The element comes from the {@code Collection<E>} supertype binding
     * ({@link AnnotationCollector#collectionElementBinding}), so a subtype that binds its element
     * somewhere other than argument 0 is classified by what it actually holds.
     *
     * @param type       the type to test
     * @param elementFqn the expected element type FQN
     * @return {@code true} when the type is a collection of the given element type
     */
    private boolean isListOf(TypeMirror type, String elementFqn) {
        if (!(type instanceof DeclaredType dt)) return false;
        TypeElement collectionEl = ctx.elements().getTypeElement(COLLECTION_FQN);
        if (collectionEl == null) return false;
        if (!ctx.types().isAssignable(ctx.types().erasure(type), ctx.types().erasure(collectionEl.asType()))) {
            return false;
        }
        if (dt.getTypeArguments().isEmpty()) return false;
        TypeMirror elementArg = AnnotationCollector.collectionElementBinding(ctx, dt);
        return elementArg != null && elementFqn.equals(AnnotationCollector.typeFqn(elementArg));
    }

    /**
     * For a body parameter type, returns the element type to use as the discovery root:
     * <ul>
     *   <li>{@code Collection<E>} → {@code E}, resolved from the {@code Collection<E>} supertype
     *       binding ({@link AnnotationCollector#collectionElementBinding}) rather than from argument
     *       position, so discovery roots the same type the classifier and the decoder bind;</li>
     *   <li>{@code E[]} → {@code E}</li>
     *   <li>Any other type → the type itself</li>
     * </ul>
     *
     * @param paramType the body parameter type mirror
     * @return the root type mirror for discovery, or {@code null} if not determinable
     */
    private TypeMirror resolveBodyElementType(TypeMirror paramType) {
        // Collection<E>
        if (paramType instanceof DeclaredType dt) {
            TypeElement collectionEl = ctx.elements().getTypeElement(COLLECTION_FQN);
            if (collectionEl != null
                    && ctx.types()
                            .isAssignable(
                                    ctx.types().erasure(paramType), ctx.types().erasure(collectionEl.asType()))) {
                if (!dt.getTypeArguments().isEmpty()) {
                    TypeMirror arg = AnnotationCollector.collectionElementBinding(ctx, dt);
                    return (arg != null && arg.getKind() == TypeKind.DECLARED) ? arg : null;
                }
                return null; // raw collection
            }
        }
        // Array E[]
        if (paramType.getKind() == TypeKind.ARRAY) {
            TypeMirror component = ((ArrayType) paramType).getComponentType();
            return (component.getKind() == TypeKind.DECLARED) ? component : null;
        }
        return paramType;
    }
}
