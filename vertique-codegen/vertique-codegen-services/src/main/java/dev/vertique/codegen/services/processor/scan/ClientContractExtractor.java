// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.scan;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.services.processor.ServiceAnnotations;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;

/**
 * Extracts a {@link ClientContractModel} from a {@code @ServiceContract} interface alone, with no
 * implementation in sight.
 *
 * <p>This is the contract-only counterpart of {@link DirectImplExtractor}: it runs the same
 * extraction pipeline ({@link AptOperationIdResolver} → {@link MethodExtraction#unwrapReturnType}
 * → {@link AptParamClassifier}) but drives it from the contract interface, so a client proxy can
 * be emitted for every source-root contract whether or not its implementation is compiled in the
 * same unit. The contract method is used as both {@code contractMethod} and {@code handlerMethod}
 * on the produced {@link OperationModel}s (the direct-impl aliasing convention); the client
 * emitter ignores the handler-side components.
 *
 * <p>Two things distinguish it from the impl-rooted path:
 * <ul>
 *   <li><b>Static methods are excluded.</b> {@link MethodExtraction#publicNonObjectMethods} keeps
 *       {@code static} interface methods, but they are never client-dispatchable (a client proxy
 *       intercepts instance methods only), so they are filtered here.</li>
 *   <li><b>Signatures are resolved against the contract.</b> Every candidate method's signature is
 *       viewed as a member of the contract type
 *       ({@link javax.lang.model.util.Types#asMemberOf(DeclaredType, javax.lang.model.element.Element)}),
 *       so a concrete contract inheriting from a generic super-interface
 *       ({@code Child extends Parent<String>}) yields substituted signatures and <em>is</em>
 *       generatable.</li>
 * </ul>
 *
 * <p>A contract is <b>non-generatable</b> when type variables remain after that substitution —
 * the contract declares its own type parameters, or one of its methods is generic. Such contracts
 * are skipped with an informational {@code NOTE} (never uncompilable output); they keep working
 * through the reflective client proxy at runtime.
 *
 * <p>Extraction errors (invalid return type, illegal parameters) are emitted as compiler errors
 * via {@link CodegenContext#diagnostics()} and yield {@link Optional#empty()} — the same
 * "no model, diagnostics already reported" contract the impl-rooted extractors use.
 */
public final class ClientContractExtractor {

    private final CodegenContext ctx;
    private final AptOperationIdResolver opIdResolver;
    private final AptParamClassifier paramClassifier;

    /**
     * Constructs a {@code ClientContractExtractor} bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ClientContractExtractor(CodegenContext ctx) {
        this.ctx = ctx;
        this.opIdResolver = new AptOperationIdResolver(ctx);
        this.paramClassifier = new AptParamClassifier(ctx);
    }

    // --- Extraction ---

    /**
     * Extracts the client-side model for the given {@code @ServiceContract} interface.
     *
     * @param contractType the {@code @ServiceContract}-annotated interface; must not be
     *                     {@code null}
     * @return the extracted model, or {@link Optional#empty()} when the contract is non-generatable
     *         (a {@code NOTE} was emitted) or invalid (errors were emitted)
     */
    public Optional<ClientContractModel> extract(TypeElement contractType) {
        if (!contractType.getTypeParameters().isEmpty()) {
            noteNonGeneratable(contractType);
            return Optional.empty();
        }

        DeclaredType contractDeclared = (DeclaredType) contractType.asType();
        List<OperationModel> operations = new ArrayList<>();
        boolean valid = true;

        for (ExecutableElement contractMethod : MethodExtraction.publicNonObjectMethods(contractType, ctx)) {
            // Static interface methods are never client-dispatchable — a proxy overrides instance
            // methods only. publicNonObjectMethods does not filter them, so the exclusion is here.
            if (contractMethod.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }

            ExecutableType resolved = (ExecutableType) ctx.types().asMemberOf(contractDeclared, contractMethod);
            if (hasUnresolvedTypeVariable(resolved)) {
                noteNonGeneratable(contractType);
                return Optional.empty();
            }

            OperationModel operation = extractOperation(contractMethod, resolved);
            if (operation == null) {
                // Diagnostics already emitted; keep going so one compile reports every problem.
                valid = false;
                continue;
            }
            operations.add(operation);
        }

        if (!valid) {
            return Optional.empty();
        }
        return Optional.of(new ClientContractModel(contractType, List.copyOf(operations)));
    }

    /**
     * Builds the {@link OperationModel} for a single contract method from its resolved signature.
     *
     * @param contractMethod the contract interface method; must not be {@code null}
     * @param resolved       the method's type as a member of the contract; must not be {@code null}
     * @return the operation model, or {@code null} when an error diagnostic was emitted
     */
    private OperationModel extractOperation(ExecutableElement contractMethod, ExecutableType resolved) {
        boolean hasError = false;

        String operationName = opIdResolver.resolveOperationName(contractMethod);
        String stableOpId = opIdResolver.resolveStableOperationId(contractMethod);

        boolean[] returnTypeErrorSink = {false};
        TypeMirror returnType = MethodExtraction.unwrapReturnType(contractMethod, resolved, ctx, returnTypeErrorSink);
        if (returnTypeErrorSink[0]) {
            hasError = true;
        }

        boolean[] paramErrorSink = {false};
        List<ParamModel> params = paramClassifier.classifyContractParams(contractMethod, resolved, paramErrorSink);
        if (paramErrorSink[0]) {
            hasError = true;
        }

        if (hasError) {
            return null;
        }

        TypeMirror payloadType = params.stream()
                .filter(ParamModel::isPayload)
                .map(ParamModel::type)
                .findFirst()
                .orElse(null);

        // Contract-only extraction: the contract method is both sides of the operation, exactly as
        // DirectImplExtractor aliases them for the direct-impl pattern.
        return new OperationModel(
                contractMethod,
                contractMethod,
                operationName,
                stableOpId,
                returnType,
                payloadType,
                List.copyOf(params),
                List.copyOf(params),
                AnnotationMirrors.isPresent(contractMethod, ServiceAnnotations.ONE_WAY));
    }

    // --- Non-generatable detection ---

    /**
     * Emits the informational {@code NOTE} explaining why no client proxy is generated for the
     * given contract.
     *
     * @param contractType the skipped contract; must not be {@code null}
     */
    private void noteNonGeneratable(TypeElement contractType) {
        ctx.diagnostics()
                .note(
                        contractType,
                        "No service client proxy generated for %s: unresolved type variables remain"
                                + " after substitution (generic contract or generic method)."
                                + " Clients for this contract use the reflective proxy at runtime.",
                        contractType.getQualifiedName());
    }

    /**
     * Returns {@code true} when the resolved method signature still mentions a type variable —
     * either because the method itself is generic or because a type variable survived substitution.
     *
     * @param resolved the method's type as a member of the contract; must not be {@code null}
     * @return {@code true} if the signature cannot be emitted verbatim into a proxy
     */
    private static boolean hasUnresolvedTypeVariable(ExecutableType resolved) {
        if (!resolved.getTypeVariables().isEmpty()) {
            return true;
        }
        if (containsTypeVariable(resolved.getReturnType())) {
            return true;
        }
        return resolved.getParameterTypes().stream().anyMatch(ClientContractExtractor::containsTypeVariable);
    }

    /**
     * Recursively reports whether the given type mentions a type variable, descending through
     * declared-type arguments, array components, and wildcard bounds.
     *
     * @param type the type to inspect; may be {@code null}
     * @return {@code true} if a {@link TypeKind#TYPEVAR} occurs anywhere in the type
     */
    private static boolean containsTypeVariable(TypeMirror type) {
        if (type == null) {
            return false;
        }
        return switch (type.getKind()) {
            case TYPEVAR -> true;
            case DECLARED ->
                ((DeclaredType) type)
                        .getTypeArguments().stream().anyMatch(ClientContractExtractor::containsTypeVariable);
            case ARRAY -> containsTypeVariable(((ArrayType) type).getComponentType());
            case WILDCARD ->
                containsTypeVariable(((WildcardType) type).getExtendsBound())
                        || containsTypeVariable(((WildcardType) type).getSuperBound());
            default -> false;
        };
    }
}
