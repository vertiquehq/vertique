// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.cron.processor.validate;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.cron.processor.CronAnnotations;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Validates that a {@code @CronJob}-annotated method's enclosing class participates in the
 * service contract model and that the matched contract method has {@code @ServiceOperation}.
 *
 * <p>Mirrors {@code ContractDiscovery.findContract} exactly: checks the handler pattern
 * ({@code ServiceHandler<C>}) first, then falls back to the direct-implementation pattern
 * (scanning transitive interfaces for a single {@code @ServiceContract}). All violation messages
 * use the same wording as the runtime to ensure users see identical error text at build and
 * run time.
 *
 * <p>Method matching uses name-only equality, mirroring {@code CronJobRegistrar.findMetaForMethod}.
 * The {@code @ServiceOperation} requirement on the matched contract method is unconditional —
 * runtime rejects every annotation-discovered cron job whose contract method lacks it, regardless
 * of {@code mode} or {@code tracked}.
 */
public final class ServiceCouplingValidator {

    private final CodegenContext ctx;

    /** Lazy-resolved {@code ServiceHandler} {@link TypeElement}; {@code null} if unavailable. */
    private TypeElement serviceHandlerElement;

    /** Lazy-resolved erasure of {@link #serviceHandlerElement}'s type. */
    private String serviceHandlerErasure;

    /**
     * Creates a new validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ServiceCouplingValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates the service coupling for a single {@code @CronJob}-annotated method.
     *
     * @param owner  the enclosing class of the annotated method; must not be {@code null}
     * @param method the {@code @CronJob}-annotated method; must not be {@code null}
     */
    public void validate(TypeElement owner, ExecutableElement method) {
        String ownerFqn = owner.getQualifiedName().toString();

        TypeElement contract = resolveContract(owner, ownerFqn, method);
        if (contract == null) {
            return;
        }

        String implMethodName = method.getSimpleName().toString();
        ExecutableElement contractMethod = findContractMethodByName(contract, implMethodName);
        if (contractMethod == null) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@CronJob method %s.%s() has no matching method named '%s' on @ServiceContract %s",
                            ownerFqn,
                            implMethodName,
                            implMethodName,
                            contract.getQualifiedName());
            return;
        }

        var serviceOpMirror = AnnotationMirrors.findByFqn(contractMethod, CronAnnotations.SERVICE_OPERATION);
        if (serviceOpMirror.isEmpty()) {
            ctx.diagnostics()
                    .error(
                            method,
                            "@CronJob on %s.%s() requires the corresponding service contract method"
                                    + " to have @ServiceOperation — cron jobs must have a stable service target",
                            ownerFqn,
                            implMethodName);
            return;
        }
        // Mirror runtime OperationIdResolver.java:62-66: a blank @ServiceOperation value would
        // throw at startup; surface the same failure at compile time.
        String opValue = ctx.annotations()
                .attribute(serviceOpMirror.get(), "value", String.class)
                .orElse("");
        if (opValue.isBlank()) {
            TypeElement contractDeclaring = (TypeElement) contractMethod.getEnclosingElement();
            ctx.diagnostics()
                    .error(
                            method,
                            "Method '%s' on %s has @ServiceOperation with a blank value —"
                                    + " the operation id must be non-blank",
                            contractMethod.getSimpleName(),
                            contractDeclaring.getSimpleName());
        }
    }

    // --- Contract resolution ---

    private TypeElement resolveContract(TypeElement owner, String ownerFqn, ExecutableElement method) {
        TypeElement handler = serviceHandler();
        if (handler != null && implementsServiceHandler(owner)) {
            return resolveHandlerContract(owner, ownerFqn, method, handler);
        }
        return resolveDirectContract(owner, ownerFqn, method);
    }

    private TypeElement resolveHandlerContract(
            TypeElement owner, String ownerFqn, ExecutableElement method, TypeElement handler) {
        TypeMirror contractTypeMirror = ctx.typeResolver()
                .resolveTypeArgument(owner.asType(), handler, 0)
                .filter(t -> t.getKind() == TypeKind.DECLARED)
                .orElse(null);
        if (contractTypeMirror == null) {
            ctx.diagnostics()
                    .error(
                            method,
                            "%s implements ServiceHandler but the contract type parameter could not be resolved"
                                    + " (raw type or unresolved type variable)",
                            ownerFqn);
            return null;
        }

        TypeElement contractElement = (TypeElement) ((DeclaredType) contractTypeMirror).asElement();
        String contractSimpleName = contractElement.getSimpleName().toString();

        if (!AnnotationMirrors.isPresent(contractElement, CronAnnotations.SERVICE_CONTRACT)) {
            ctx.diagnostics()
                    .error(
                            method,
                            "%s implements ServiceHandler<%s> but %s is not annotated with @ServiceContract",
                            ownerFqn,
                            contractSimpleName,
                            contractSimpleName);
            return null;
        }

        if (implementsInterface(owner, contractElement)) {
            ctx.diagnostics()
                    .error(
                            method,
                            "%s implements both ServiceHandler<%s> and %s directly — use one pattern, not both",
                            ownerFqn,
                            contractSimpleName,
                            contractSimpleName);
            return null;
        }

        List<TypeElement> additionalContracts = collectServiceContracts(owner, contractElement);
        if (!additionalContracts.isEmpty()) {
            String names = additionalContracts.stream()
                    .map(e -> e.getSimpleName().toString())
                    .collect(Collectors.joining(", "));
            ctx.diagnostics()
                    .error(
                            method,
                            "%s implements ServiceHandler<%s> but also implements additional"
                                    + " @ServiceContract interface(s): %s",
                            ownerFqn,
                            contractSimpleName,
                            names);
            return null;
        }

        return contractElement;
    }

    private TypeElement resolveDirectContract(TypeElement owner, String ownerFqn, ExecutableElement method) {
        List<TypeElement> contracts = collectServiceContracts(owner, null);

        if (contracts.isEmpty()) {
            ctx.diagnostics().error(method, "%s does not implement any @ServiceContract-annotated interface", ownerFqn);
            return null;
        }

        if (contracts.size() > 1) {
            String names =
                    contracts.stream().map(e -> e.getSimpleName().toString()).collect(Collectors.joining(", "));
            ctx.diagnostics().error(method, "%s implements multiple @ServiceContract interfaces: %s", ownerFqn, names);
            return null;
        }

        return contracts.get(0);
    }

    // --- Type-walking helpers ---

    /**
     * Lazily resolves and caches the {@code ServiceHandler} {@link TypeElement} (and its erasure
     * string) so the lookup is amortised across all methods processed by this validator instance.
     */
    private TypeElement serviceHandler() {
        if (serviceHandlerElement == null) {
            serviceHandlerElement = ctx.elements().getTypeElement(CronAnnotations.SERVICE_HANDLER);
            if (serviceHandlerElement != null) {
                serviceHandlerErasure =
                        ctx.types().erasure(serviceHandlerElement.asType()).toString();
            }
        }
        return serviceHandlerElement;
    }

    private boolean implementsServiceHandler(TypeElement owner) {
        return ctx.typeResolver()
                .allSupertypes(owner.asType())
                .anyMatch(t -> ctx.types().erasure(t).toString().equals(serviceHandlerErasure));
    }

    private boolean implementsInterface(TypeElement owner, TypeElement targetInterface) {
        String targetErasure = ctx.types().erasure(targetInterface.asType()).toString();
        return ctx.typeResolver()
                .allSupertypes(owner.asType())
                .filter(t -> t.getKind() == TypeKind.DECLARED)
                .filter(t -> ((DeclaredType) t).asElement().getKind() == ElementKind.INTERFACE)
                .anyMatch(t -> ctx.types().erasure(t).toString().equals(targetErasure));
    }

    /**
     * Collects all transitive interfaces of {@code owner} annotated {@code @ServiceContract},
     * optionally excluding one interface from the results.
     *
     * <p>Used both to find direct-pattern contracts and to detect additional contracts beyond
     * the handler's {@code C}.
     */
    private List<TypeElement> collectServiceContracts(TypeElement owner, TypeElement exclude) {
        String excludeErasure =
                exclude != null ? ctx.types().erasure(exclude.asType()).toString() : null;
        return ctx.typeResolver()
                .allSupertypes(owner.asType())
                .filter(t -> t.getKind() == TypeKind.DECLARED)
                .filter(t -> ((DeclaredType) t).asElement().getKind() == ElementKind.INTERFACE)
                .filter(t -> excludeErasure == null
                        || !ctx.types().erasure(t).toString().equals(excludeErasure))
                .map(t -> (TypeElement) ((DeclaredType) t).asElement())
                .filter(e -> AnnotationMirrors.isPresent(e, CronAnnotations.SERVICE_CONTRACT))
                .collect(Collectors.toList());
    }

    // --- Method matching ---

    /**
     * Finds the first contract method whose simple name equals {@code methodName}, walking the
     * contract and its super-interfaces via BFS. Mirrors {@code CronJobRegistrar.findMetaForMethod}:
     * parameter signatures are NOT compared.
     */
    private ExecutableElement findContractMethodByName(TypeElement contract, String methodName) {
        Set<String> visited = new HashSet<>();
        Deque<TypeElement> queue = new ArrayDeque<>();
        queue.add(contract);

        while (!queue.isEmpty()) {
            TypeElement current = queue.poll();
            if (!visited.add(current.getQualifiedName().toString())) {
                continue;
            }
            for (var enclosed : current.getEnclosedElements()) {
                if (enclosed.getKind() == ElementKind.METHOD) {
                    ExecutableElement executable = (ExecutableElement) enclosed;
                    if (executable.getSimpleName().toString().equals(methodName)) {
                        return executable;
                    }
                }
            }
            for (TypeMirror superIface : current.getInterfaces()) {
                if (superIface.getKind() == TypeKind.DECLARED) {
                    TypeElement superElement = (TypeElement) ((DeclaredType) superIface).asElement();
                    if (superElement.getKind() == ElementKind.INTERFACE) {
                        queue.add(superElement);
                    }
                }
            }
        }
        return null;
    }
}
