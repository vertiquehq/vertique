// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.scan;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.services.processor.ServiceAnnotations;
import dev.vertique.codegen.services.processor.scan.ImplCandidate.ImplKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Walks {@link RoundEnvironment#getRootElements()} and classifies concrete
 * {@link TypeElement}s as potential {@code @ServiceContract} implementations.
 *
 * <p>Mirrors the runtime discovery semantics in {@code ContractDiscovery.java:31-105}.
 *
 * <p>Classification rules:
 * <ul>
 *   <li>Skip interfaces and abstract classes — only concrete impls are scanned.</li>
 *   <li>Skip types annotated with {@code @NoAutoWire}.</li>
 *   <li>For each remaining type:
 *     <ol>
 *       <li>Check if any direct supertype is {@code ServiceHandler<C>} where {@code C} carries
 *           {@code @ServiceContract} → {@link ImplKind#HANDLER}.</li>
 *       <li>Check if any supertype interface carries {@code @ServiceContract} directly →
 *           {@link ImplKind#DIRECT}.</li>
 *       <li>If both → {@link ImplKind#DOUBLE_PATTERN} (structural error, collected for validator).</li>
 *       <li>If neither → skip silently.</li>
 *     </ol>
 *   </li>
 * </ul>
 *
 * <p>{@code @Inject} constructor validation is deliberately deferred to
 * {@link dev.vertique.codegen.validate.InjectConstructorValidator} so that
 * an invalid impl produces an error (not a silent skip).
 */
public final class ImplCandidateScanner {

    private final CodegenContext ctx;

    /**
     * Constructs a scanner bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ImplCandidateScanner(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Scans the root elements in the current round and returns all classified candidates.
     *
     * @param roundEnv the current round environment; must not be {@code null}
     * @return list of classified candidates; never {@code null}
     */
    public List<ImplCandidate> scan(RoundEnvironment roundEnv) {
        TypeElement serviceHandlerElement = ctx.elements().getTypeElement(ServiceAnnotations.SERVICE_HANDLER);

        List<ImplCandidate> result = new ArrayList<>();

        for (Element el : roundEnv.getRootElements()) {
            if (!(el instanceof TypeElement te)) {
                continue;
            }
            // Skip interfaces and abstract classes
            if (te.getKind() == ElementKind.INTERFACE || te.getModifiers().contains(Modifier.ABSTRACT)) {
                continue;
            }
            // Respect @NoAutoWire opt-out, but warn first if the user also added
            // @ConditionalOnProperty — that combination is a no-op (CG-011 W4) since the
            // codegen path is bypassed for @NoAutoWire types.
            if (AnnotationMirrors.isPresent(te, ServiceAnnotations.NO_AUTO_WIRE)) {
                if (AnnotationMirrors.isPresent(te, ServiceAnnotations.CONDITIONAL_ON_PROPERTY)
                        || AnnotationMirrors.isPresent(te, ServiceAnnotations.CONDITIONAL_ON_PROPERTIES)) {
                    ctx.diagnostics()
                            .warning(
                                    te,
                                    "@ConditionalOnProperty has no effect on @NoAutoWire types — manual wiring is responsible for selection");
                }
                continue;
            }

            // Determine handler-pattern: implements ServiceHandler<C>
            Optional<TypeElement> handlerContract =
                    serviceHandlerElement != null ? findHandlerContract(te, serviceHandlerElement) : Optional.empty();

            // Determine direct-pattern: any supertype interface carries @ServiceContract
            Optional<TypeElement> directContract = findDirectContract(te);

            ImplKind kind;
            TypeElement contractType;

            if (handlerContract.isPresent() && directContract.isPresent()) {
                // Double-pattern: impl uses both; report using the handler contract as contract
                kind = ImplKind.DOUBLE_PATTERN;
                contractType = handlerContract.get();
            } else if (handlerContract.isPresent()) {
                kind = ImplKind.HANDLER;
                contractType = handlerContract.get();
            } else if (directContract.isPresent()) {
                kind = ImplKind.DIRECT;
                contractType = directContract.get();
            } else {
                // Not a service — skip silently
                continue;
            }

            result.add(new ImplCandidate(kind, te, contractType));
        }

        return result;
    }

    // --- Internal helpers ---

    /**
     * Returns the {@code @ServiceContract}-annotated type argument {@code C} if the given type
     * implements {@code ServiceHandler<C>}, otherwise returns empty.
     *
     * <p>Mirrors {@code ContractDiscovery.java:35-50}.
     *
     * @param type                 the type to inspect
     * @param serviceHandlerElement the {@code ServiceHandler} type element
     * @return the contract type argument if present; empty otherwise
     */
    private Optional<TypeElement> findHandlerContract(TypeElement type, TypeElement serviceHandlerElement) {
        Optional<TypeMirror> contractMirror =
                ctx.typeResolver().resolveTypeArgument(type.asType(), serviceHandlerElement, 0);
        if (contractMirror.isEmpty()) {
            return Optional.empty();
        }
        TypeMirror resolved = contractMirror.get();
        if (!(resolved instanceof DeclaredType dt)) {
            return Optional.empty();
        }
        TypeElement contractElement = (TypeElement) dt.asElement();
        if (!AnnotationMirrors.isPresent(contractElement, ServiceAnnotations.SERVICE_CONTRACT)) {
            return Optional.empty();
        }
        return Optional.of(contractElement);
    }

    /**
     * Returns the single {@code @ServiceContract}-annotated supertype interface of the given type,
     * if exactly one exists in the hierarchy.
     *
     * <p>Mirrors {@code ContractDiscovery.java:79-104}: scans all interfaces, returns first found.
     * Multiple contracts → the validator ({@code HandlerContractValidator}) handles the rejection;
     * here we just return the first one. If none → empty.
     *
     * @param type the type to inspect
     * @return the first {@code @ServiceContract} interface found; empty if none
     */
    private Optional<TypeElement> findDirectContract(TypeElement type) {
        Set<TypeMirror> visited = new java.util.HashSet<>();
        return ctx.typeResolver()
                .allSupertypes(type.asType())
                .filter(t -> {
                    if (!(t instanceof DeclaredType dt)) return false;
                    TypeElement el = (TypeElement) dt.asElement();
                    if (el.getKind() != ElementKind.INTERFACE) return false;
                    // Skip the type itself
                    if (ctx.types().isSameType(t, type.asType())) return false;
                    return AnnotationMirrors.isPresent(el, ServiceAnnotations.SERVICE_CONTRACT);
                })
                .map(t -> (TypeElement) ((DeclaredType) t).asElement())
                .findFirst();
    }
}
