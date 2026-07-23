// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.JaxRsAnnotations;
import dev.vertique.rest.core.context.RestContextTypes;
import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Classifies JAX-RS resource method parameters into {@link JaxRsParamSource} slots using the
 * same ordered dispatch as the runtime {@code ResourceScanner.resolveParams}.
 *
 * <p>Promoted from the package-private {@code BodyFormValidator.classify(VariableElement)} method
 * (CG-010) so that both the compile-time validator pipeline and the effective-contract resolver
 * can share the same classification logic without coupling to a specific validator class.
 *
 * <p>Dispatch order (must be kept in sync with the runtime {@code ResourceScanner.resolveParams}):
 * <ol>
 *   <li>{@link JaxRsParamSource#CONTEXT} — parameter annotated with {@code @Context}, or whose
 *       erased type is assignable to {@code RoutingContext},
 *       {@code jakarta.ws.rs.core.SecurityContext}, or any {@code ContextValue} subtype
 *       (including the framework {@code dev.vertique.security.SecurityContext}).
 *       {@code @Context} annotation check is performed first so that a parameter bearing
 *       {@code @Context @PathParam} is correctly classified as {@code CONTEXT}, matching the
 *       runtime {@code ResourceScanner.resolveParams} dispatch order.</li>
 *   <li>{@link JaxRsParamSource#PRECONDITIONS} — erased type is assignable to
 *       {@code RequestPreconditions}.</li>
 *   <li>Type annotated with {@code @RequestParams} — composite bean.</li>
 *   <li>Parameter annotated with {@code @BeanParam} — composite bean.</li>
 *   <li>Annotated scalars in runtime order: {@code @PathParam}, {@code @QueryParam},
 *       {@code @HeaderParam}, {@code @CookieParam}, {@code @FormParam}.</li>
 *   <li>Unannotated {@code List<FileUpload>}.</li>
 *   <li>Unannotated {@code List<EntityPart>}.</li>
 *   <li>Fallback: body (JSON deserialization).</li>
 * </ol>
 */
public final class JaxRsParamClassifier {

    // --- FQN for preconditions context type (not covered by RestContextTypes) ---

    private static final String REQUEST_PRECONDITIONS_FQN = "dev.vertique.rest.core.request.RequestPreconditions";

    // --- FQNs for list element types ---

    private static final String LIST_FQN = "java.util.List";
    private static final String FILE_UPLOAD_FQN = "io.vertx.ext.web.FileUpload";
    private static final String ENTITY_PART_FQN = "jakarta.ws.rs.core.EntityPart";

    private JaxRsParamClassifier() {}

    /**
     * Classifies a method parameter into a {@link JaxRsParamSource} slot using the same ordered
     * dispatch as the runtime {@code ResourceScanner.resolveParams}. The order must be kept in
     * sync with the runtime implementation.
     *
     * <p>The {@code @Context} annotation check is performed first (before the type-assignability
     * checks) so that a parameter bearing {@code @Context} is classified as
     * {@link JaxRsParamSource#CONTEXT} regardless of any other annotation it may carry.
     * Context types are then matched:
     * <ul>
     *   <li>{@code RoutingContext} — assignability (subtypes OK; resolver uses {@code isInstance}).
     *   </li>
     *   <li>JAX-RS {@code SecurityContext} — <em>exact type only</em> ({@link Types#isSameType}).
     *       The runtime resolver only handles the exact JAX-RS interface; a subtype would pass
     *       this check but fail at request time, so subtypes are intentionally excluded here.
     *   </li>
     *   <li>{@code ContextValue} — assignability (subtypes are the whole point of this arm).
     *   </li>
     * </ul>
     * A {@code null} context-type mirror (type not on classpath) simply skips that check.
     *
     * @param param    the method parameter to classify; must not be {@code null}
     * @param types    the APT {@link Types} utility; must not be {@code null}
     * @param elements the APT {@link Elements} utility; must not be {@code null}
     * @return the {@link JaxRsParamSource} slot for this parameter; never {@code null}
     */
    public static JaxRsParamSource classify(VariableElement param, Types types, Elements elements) {
        TypeMirror erasedParamType = types.erasure(param.asType());

        // CONTEXT check FIRST: @Context annotation OR assignable to a known injectable type.
        // The @Context annotation check precedes the type check so that a parameter annotated
        // with @Context is always classified as CONTEXT, matching the runtime dispatch order.
        boolean hasContext = AnnotationMirrors.isPresent(param, JaxRsAnnotations.CONTEXT);
        boolean injectable = isAssignableTo(
                        erasedParamType, erasedMirrorOf(RestContextTypes.ROUTING_CONTEXT_FQN, types, elements), types)
                // JAX-RS SecurityContext: exact type only — subtypes are not resolvable by the
                // framework resolver and must not be silently accepted as injectable.
                || isExactly(
                        erasedParamType,
                        erasedMirrorOf(RestContextTypes.JAXRS_SECURITY_CONTEXT_FQN, types, elements),
                        types)
                || isAssignableTo(
                        erasedParamType, erasedMirrorOf(RestContextTypes.CONTEXT_VALUE_FQN, types, elements), types);
        if (hasContext || injectable) return JaxRsParamSource.CONTEXT;

        if (isAssignableTo(erasedParamType, erasedMirrorOf(REQUEST_PRECONDITIONS_FQN, types, elements), types))
            return JaxRsParamSource.PRECONDITIONS;

        TypeMirror rawParamType = param.asType();
        TypeElement paramTypeElement = asTypeElement(rawParamType, types);
        if (paramTypeElement != null
                && AnnotationMirrors.isPresent(paramTypeElement, JaxRsAnnotations.REQUEST_PARAMS)) {
            return JaxRsParamSource.BEAN_PARAM;
        }

        if (AnnotationMirrors.isPresent(param, JaxRsAnnotations.BEAN_PARAM)) return JaxRsParamSource.BEAN_PARAM;

        if (AnnotationMirrors.isPresent(param, JaxRsAnnotations.PATH_PARAM)) return JaxRsParamSource.PATH;
        if (AnnotationMirrors.isPresent(param, JaxRsAnnotations.QUERY_PARAM)) return JaxRsParamSource.QUERY;
        if (AnnotationMirrors.isPresent(param, JaxRsAnnotations.HEADER_PARAM)) return JaxRsParamSource.HEADER;
        if (AnnotationMirrors.isPresent(param, JaxRsAnnotations.COOKIE_PARAM)) return JaxRsParamSource.COOKIE;
        if (AnnotationMirrors.isPresent(param, JaxRsAnnotations.FORM_PARAM)) return JaxRsParamSource.FORM;

        if (isListOf(rawParamType, FILE_UPLOAD_FQN, types, elements)) return JaxRsParamSource.FILE_UPLOADS;
        if (isListOf(rawParamType, ENTITY_PART_FQN, types, elements)) return JaxRsParamSource.ENTITY_PARTS;

        return JaxRsParamSource.BODY;
    }

    // --- Private helpers ---

    /**
     * Returns the erased {@link TypeMirror} for the given fully-qualified class name, or
     * {@code null} if the type is not on the compilation classpath.
     *
     * @param fqn      the fully-qualified class name
     * @param types    the APT {@link Types} utility
     * @param elements the APT {@link Elements} utility
     * @return the erased type mirror, or {@code null}
     */
    private static TypeMirror erasedMirrorOf(String fqn, Types types, Elements elements) {
        TypeElement element = elements.getTypeElement(fqn);
        if (element == null) {
            return null;
        }
        return types.erasure(element.asType());
    }

    /**
     * Returns {@code true} if {@code paramType} is the <em>exact same type</em> as
     * {@code targetMirror} (no subtype or supertype relationship — same erased type only).
     * Returns {@code false} when {@code targetMirror} is {@code null} (type not on classpath).
     *
     * <p>Used for the JAX-RS {@code SecurityContext} arm of the injectable check: the runtime
     * resolver only handles the exact interface, so subtypes must not be accepted here.
     *
     * @param paramType    the erased parameter type mirror
     * @param targetMirror the erased target type mirror, or {@code null}
     * @param types        the APT {@link Types} utility
     * @return {@code true} if {@code paramType} and {@code targetMirror} are the same type
     */
    private static boolean isExactly(TypeMirror paramType, TypeMirror targetMirror, Types types) {
        if (targetMirror == null) {
            return false;
        }
        try {
            return types.isSameType(paramType, targetMirror);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Returns {@code true} if {@code paramType} is assignable to {@code contextMirror}.
     * Returns {@code false} when {@code contextMirror} is {@code null} (type not on classpath).
     *
     * @param paramType     the erased parameter type mirror
     * @param contextMirror the erased context-base-type mirror, or {@code null}
     * @param types         the APT {@link Types} utility
     * @return {@code true} if assignable
     */
    private static boolean isAssignableTo(TypeMirror paramType, TypeMirror contextMirror, Types types) {
        if (contextMirror == null) {
            return false;
        }
        try {
            return types.isAssignable(paramType, contextMirror);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Returns the erased fully-qualified name of the given type mirror.
     *
     * @param type  the type mirror
     * @param types the APT {@link Types} utility
     * @return the erased FQN string
     */
    private static String erasedFqn(TypeMirror type, Types types) {
        TypeMirror erased = types.erasure(type);
        var element = types.asElement(erased);
        if (element instanceof TypeElement te) {
            return te.getQualifiedName().toString();
        }
        return erased.toString();
    }

    /**
     * Returns {@code true} if {@code type} is {@code java.util.List} parameterized with the type
     * whose fully-qualified name is {@code elementFqn}.
     *
     * @param type       the type mirror to inspect
     * @param elementFqn the FQN of the expected list element type
     * @param types      the APT {@link Types} utility
     * @param elements   the APT {@link Elements} utility
     * @return {@code true} if the type is {@code List<elementFqn>}
     */
    private static boolean isListOf(TypeMirror type, String elementFqn, Types types, Elements elements) {
        if (!(type instanceof DeclaredType declared)) {
            return false;
        }
        if (!LIST_FQN.equals(erasedFqn(type, types))) {
            return false;
        }
        List<? extends TypeMirror> args = declared.getTypeArguments();
        if (args.size() != 1) {
            return false;
        }
        TypeElement elementElement = asTypeElement(args.get(0), types);
        if (elementElement == null) {
            return false;
        }
        return elementFqn.equals(elementElement.getQualifiedName().toString());
    }

    /**
     * Returns the {@link TypeElement} for a declared type mirror, or {@code null} for non-declared
     * types.
     *
     * @param type  the type mirror to resolve
     * @param types the APT {@link Types} utility
     * @return the type element, or {@code null}
     */
    private static TypeElement asTypeElement(TypeMirror type, Types types) {
        if (type == null) return null;
        var el = types.asElement(type);
        return el instanceof TypeElement te ? te : null;
    }
}
