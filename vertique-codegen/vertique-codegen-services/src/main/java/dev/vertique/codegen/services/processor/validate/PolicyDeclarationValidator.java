// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.validate;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.JaxRsAnnotations;
import dev.vertique.codegen.security.AccessPolicyAnnotationResolver;
import dev.vertique.codegen.services.processor.scan.ContractModel;
import dev.vertique.codegen.services.processor.scan.ImplCandidate.ImplKind;
import dev.vertique.codegen.services.processor.scan.OperationModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

/**
 * Validates typed access-policy declarations on service contracts and their implementations at
 * compile time.
 *
 * <p>The contract rules apply the same selection the runtime applies when it registers a service,
 * so a declaration the runtime would reject fails the build instead of startup. For each contract
 * the declarations collected from the contract and every parent interface are checked: a type-level
 * set (reported once on the contract) and a method-level set per operation (reported on the
 * method). A rejection is any of: distinct {@code @RequiresPolicy} references in one set, a
 * {@code @RequiresPolicy} mixed with an inline security annotation, or a referenced policy that is
 * malformed (not a public interface, extends other types, empty or blank roles or scopes, a
 * malformed action, or no requirement at all).
 *
 * <p>Only the contract is collected at runtime, so a {@code @RequiresPolicy} placed on an
 * implementation class, an implementation method, or a handler method would be silently ignored.
 * That placement is rejected with a message naming the contract as the place to declare it. The
 * handler pattern is checked on every class of the handler's class chain, so an overriding method
 * on an abstract base handler is diagnosed too.
 *
 * <p>An implementation that is also a JAX-RS resource (its class chain carries {@code @Path}) may
 * declare {@code @RequiresPolicy} for its REST routes. The class-level annotation is then accepted,
 * and a method-level annotation is accepted on any method that does not correspond to a service
 * operation. A method that does correspond to a service operation is still rejected, as is any
 * placement on an implementation class without {@code @Path}.
 *
 * <p>A type-level rejection is reported on the contract only; the operations below it are not
 * re-reported. The same element and message are never reported twice, so a contract shared by
 * several implementations produces one diagnostic.
 */
public final class PolicyDeclarationValidator {

    private static final String REQUIRES_POLICY = "dev.vertique.security.authz.RequiresPolicy";

    private final CodegenContext ctx;
    private final AccessPolicyAnnotationResolver resolver;
    private final Set<ReportedDiagnostic> reported = new HashSet<>();

    /**
     * Constructs this validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public PolicyDeclarationValidator(CodegenContext ctx) {
        this.ctx = ctx;
        this.resolver = new AccessPolicyAnnotationResolver(ctx.types(), ctx.elements());
    }

    /**
     * Validates the typed access-policy declarations of a contract and its operations.
     *
     * @param contractType the {@code @ServiceContract} interface; must not be {@code null}
     * @param operations   the contract's operations; must not be {@code null}
     * @return {@code true} if every declaration is accepted; {@code false} if any error was emitted
     */
    public boolean validateContract(TypeElement contractType, List<OperationModel> operations) {
        boolean valid = true;
        List<TypeElement> hierarchy = interfaceHierarchy(contractType);
        List<AnnotationMirror> typeSet = typeSecurity(hierarchy);

        String typeRejection = rejection(List.of(), typeSet);
        if (typeRejection != null) {
            report(contractType, "Invalid @RequiresPolicy declaration on contract %s: %s", contractType, typeRejection);
            valid = false;
        }

        for (OperationModel op : operations) {
            if (typeRejection != null) {
                // Already reported once on the contract; the method set cannot make it valid.
                continue;
            }
            ExecutableElement method = op.contractMethod();
            String rejection = rejection(methodSecurity(contractType, hierarchy, method), typeSet);
            if (rejection != null) {
                report(method, "Invalid @RequiresPolicy declaration on %s: %s", method, rejection);
                valid = false;
            }
        }
        return valid;
    }

    /**
     * Rejects a {@code @RequiresPolicy} placed where the runtime never reads it.
     *
     * <p>Checks the implementation class (and its superclasses) and, for each operation, the
     * implementation methods that correspond to it across the class chain (matched on the handler
     * method for the handler pattern). An implementation whose class chain carries {@code @Path} is
     * a REST resource and may keep a class-level {@code @RequiresPolicy} for its routes.
     *
     * @param model the validated contract model for one implementation; must not be {@code null}
     * @return {@code true} if no ignored placement was found; {@code false} if any error was emitted
     */
    public boolean validateImplementation(ContractModel model) {
        boolean valid = true;
        TypeElement implType = model.implType();
        String contractName = model.contractType().getQualifiedName().toString();

        List<TypeElement> chain = classChain(implType);
        boolean restResource =
                chain.stream().anyMatch(type -> AnnotationMirrors.isPresent(type, JaxRsAnnotations.PATH));
        for (TypeElement type : chain) {
            if (!restResource && AnnotationMirrors.isPresent(type, REQUIRES_POLICY)) {
                report(
                        implType,
                        "@RequiresPolicy on implementation class %s is ignored: declare it on the service contract %s",
                        type.getQualifiedName(),
                        contractName);
                valid = false;
            }
        }

        for (OperationModel op : model.operations()) {
            for (ExecutableElement method : implementationMethods(model, op)) {
                if (AnnotationMirrors.isPresent(method, REQUIRES_POLICY)) {
                    report(
                            method,
                            "@RequiresPolicy on implementation method %s is ignored: declare it on the service contract %s",
                            method,
                            contractName);
                    valid = false;
                }
            }
        }
        return valid;
    }

    // --- Implementation-side lookup ---

    private List<ExecutableElement> implementationMethods(ContractModel model, OperationModel op) {
        // The handler method may differ from the contract method (extra parameters), so a handler
        // is matched on its own signature; a direct implementation is matched on the contract's.
        boolean handler = model.kind() == ImplKind.HANDLER;
        ExecutableElement operation = handler ? op.handlerMethod() : op.contractMethod();
        List<ExecutableElement> methods = new ArrayList<>();
        if (handler) {
            methods.add(operation);
        }
        for (TypeElement type : classChain(model.implType())) {
            for (ExecutableElement candidate : ElementFilter.methodsIn(type.getEnclosedElements())) {
                if (!methods.contains(candidate) && resolver.corresponds(model.implType(), operation, candidate)) {
                    methods.add(candidate);
                }
            }
        }
        return methods;
    }

    private static List<TypeElement> classChain(TypeElement type) {
        List<TypeElement> chain = new ArrayList<>();
        TypeElement current = type;
        while (current != null
                && !current.getQualifiedName().contentEquals("java.lang.Object")
                && !chain.contains(current)) {
            chain.add(current);
            current = current.getSuperclass() instanceof DeclaredType declared
                            && declared.asElement() instanceof TypeElement superclass
                    ? superclass
                    : null;
        }
        return chain;
    }

    // --- Contract-side collection ---

    private String rejection(List<AnnotationMirror> methodSet, List<AnnotationMirror> typeSet) {
        try {
            resolver.select(methodSet, typeSet);
            return null;
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }
    }

    private static List<TypeElement> interfaceHierarchy(TypeElement contractType) {
        Set<TypeElement> seen = new LinkedHashSet<>();
        collectInterfaces(contractType, seen);
        return new ArrayList<>(seen);
    }

    private static void collectInterfaces(TypeElement type, Set<TypeElement> seen) {
        if (!seen.add(type)) {
            return;
        }
        for (TypeMirror iface : type.getInterfaces()) {
            if (iface instanceof DeclaredType declared && declared.asElement() instanceof TypeElement parent) {
                collectInterfaces(parent, seen);
            }
        }
    }

    private static List<AnnotationMirror> typeSecurity(List<TypeElement> hierarchy) {
        List<AnnotationMirror> mirrors = new ArrayList<>();
        for (TypeElement type : hierarchy) {
            addSecurityMirrors(mirrors, type);
        }
        return mirrors;
    }

    private List<AnnotationMirror> methodSecurity(
            TypeElement contractType, List<TypeElement> hierarchy, ExecutableElement method) {
        List<AnnotationMirror> mirrors = new ArrayList<>();
        for (TypeElement type : hierarchy) {
            for (ExecutableElement candidate : ElementFilter.methodsIn(type.getEnclosedElements())) {
                if (resolver.corresponds(contractType, method, candidate)) {
                    addSecurityMirrors(mirrors, candidate);
                }
            }
        }
        return mirrors;
    }

    private static void addSecurityMirrors(List<AnnotationMirror> mirrors, Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (!(mirror.getAnnotationType().asElement() instanceof TypeElement type)) {
                continue;
            }
            String name = type.getQualifiedName().toString();
            if (name.equals(REQUIRES_POLICY)
                    || name.equals(JaxRsAnnotations.PERMIT_ALL)
                    || name.equals(JaxRsAnnotations.DENY_ALL)
                    || name.equals(JaxRsAnnotations.ROLES_ALLOWED)
                    || name.equals(JaxRsAnnotations.AUTHORIZED)
                    || name.equals(JaxRsAnnotations.REQUIRES_ACTION)) {
                mirrors.add(mirror);
            }
        }
    }

    // --- Reporting ---

    private void report(Element element, String message, Object... args) {
        String text = String.format(message, args);
        if (reported.add(new ReportedDiagnostic(element, text))) {
            ctx.diagnostics().error(element, "%s", text);
        }
    }

    private record ReportedDiagnostic(Element element, String message) {}
}
