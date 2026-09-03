// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.resilience;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.ProxyabilityValidator;
import dev.vertique.resilience.annotation.Bulkhead;
import dev.vertique.resilience.annotation.CircuitBreaker;
import dev.vertique.resilience.annotation.Resilient;
import dev.vertique.resilience.annotation.Retry;
import dev.vertique.resilience.annotation.Timeout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Compile-time validation for the resilience annotation family. */
@SupportedAnnotationTypes({
    "dev.vertique.resilience.annotation.Resilient",
    "dev.vertique.resilience.annotation.Retry",
    "dev.vertique.resilience.annotation.Timeout",
    "dev.vertique.resilience.annotation.CircuitBreaker",
    "dev.vertique.resilience.annotation.Bulkhead"
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class ResilienceAnnotationProcessor extends AbstractProcessor {

    private static final String SERVICE_HANDLER_FQN = "dev.vertique.services.ServiceHandler";
    private static final String SERVICE_CONTRACT_FQN = "dev.vertique.services.ServiceContract";
    private static final Pattern POLICY_NAME_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final Set<String> RESILIENCE_DECLARATIONS = Set.of(
            Retry.class.getName(), Timeout.class.getName(), CircuitBreaker.class.getName(), Bulkhead.class.getName());

    private CodegenContext context;
    private Types types;
    private Elements elements;
    private ProxyabilityValidator proxyabilityValidator;

    @Override
    public synchronized void init(ProcessingEnvironment environment) {
        super.init(environment);
        context = new CodegenContext(environment);
        types = environment.getTypeUtils();
        elements = environment.getElementUtils();
        proxyabilityValidator = new ProxyabilityValidator(context, "resilient");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        Set<Element> elementsToValidate = new LinkedHashSet<>();
        elementsToValidate.addAll(roundEnvironment.getElementsAnnotatedWith(Resilient.class));
        elementsToValidate.addAll(roundEnvironment.getElementsAnnotatedWith(Retry.class));
        elementsToValidate.addAll(roundEnvironment.getElementsAnnotatedWith(Timeout.class));
        elementsToValidate.addAll(roundEnvironment.getElementsAnnotatedWith(CircuitBreaker.class));
        elementsToValidate.addAll(roundEnvironment.getElementsAnnotatedWith(Bulkhead.class));

        for (Element element : elementsToValidate) {
            if (element.getKind().isClass() || element.getKind() == ElementKind.INTERFACE) {
                validateType(element);
            } else if (element.getKind() == ElementKind.METHOD) {
                validateMethod((ExecutableElement) element);
            }
        }
        return false;
    }

    private void validateType(Element element) {
        validateBounds(element);
        if (element.getKind() != ElementKind.INTERFACE) {
            context.diagnostics().error(element, Diagnostics.resilienceClassLevelDeclaration());
        }
    }

    private void validateMethod(ExecutableElement method) {
        validateBounds(method);

        boolean anchored = AnnotationMirrors.isPresent(method, Resilient.class.getName());
        boolean declaration = hasResilienceDeclaration(method);
        TypeElement enclosingType = (TypeElement) method.getEnclosingElement();

        if (!anchored) {
            if (enclosingType.getKind() != ElementKind.INTERFACE && declaration) {
                context.diagnostics().error(method, Diagnostics.resilienceDeclarationRequiresAnchor());
            }
            return;
        }

        String policy = method.getAnnotation(Resilient.class).policy();
        if (!policy.isEmpty() && !POLICY_NAME_PATTERN.matcher(policy).matches()) {
            context.diagnostics().error(method, Diagnostics.resiliencePolicyName(policy));
        }

        if (enclosingType.getKind() == ElementKind.INTERFACE) {
            if (!isAllowedInterface(enclosingType)) {
                context.diagnostics().error(method, Diagnostics.resilientInterfaceNotAllowed());
            }
            return;
        }

        if (policy.isEmpty() && !declaration) {
            context.diagnostics().error(method, Diagnostics.resilientAnchorRequiresPolicyOrDeclaration());
        }
        proxyabilityValidator.validate(method);
        validateReturnType(method);
        validateServiceDoubleWrap(method, enclosingType);
    }

    private boolean isAllowedInterface(TypeElement type) {
        if (AnnotationMirrors.isPresent(type, SERVICE_CONTRACT_FQN)) {
            return true;
        }
        return type.getAnnotationMirrors().stream()
                .map(mirror -> mirror.getAnnotationType().asElement())
                .filter(TypeElement.class::isInstance)
                .map(TypeElement.class::cast)
                .map(annotation -> annotation.getQualifiedName().toString())
                .anyMatch(name -> name.startsWith("jakarta.ws.rs."));
    }

    private void validateReturnType(ExecutableElement method) {
        TypeMirror returnType = method.getReturnType();
        TypeMirror payload = context.unwrapFuture(returnType);
        boolean concreteFuture = payload != returnType
                && returnType.getKind() == TypeKind.DECLARED
                && payload.getKind() != TypeKind.WILDCARD
                && payload.getKind() != TypeKind.TYPEVAR
                && payload.getKind() != TypeKind.ERROR;
        if (!concreteFuture) {
            context.diagnostics().error(method, Diagnostics.resilientMethodsMustReturnFuture());
        }
    }

    private void validateServiceDoubleWrap(ExecutableElement method, TypeElement implementation) {
        for (DeclaredType contract : serviceContracts(implementation)) {
            TypeElement contractElement = (TypeElement) contract.asElement();
            if (hasTypeLevelResilience(contractElement) || hasMatchingResilience(method, contract)) {
                context.diagnostics().error(method, Diagnostics.resilienceServiceDoubleWrap());
                return;
            }
        }
    }

    private List<DeclaredType> serviceContracts(TypeElement implementation) {
        List<DeclaredType> contracts = new ArrayList<>();
        collectServiceContracts(implementation.asType(), new HashSet<>(), new HashSet<>(), contracts);
        return contracts;
    }

    private void collectServiceContracts(
            TypeMirror current, Set<String> visitedTypes, Set<String> seenContracts, List<DeclaredType> contracts) {
        if (!(current instanceof DeclaredType declared)) {
            return;
        }
        TypeElement currentElement = (TypeElement) declared.asElement();
        String currentName = currentElement.getQualifiedName().toString();
        if (!visitedTypes.add(currentName + declared)) {
            return;
        }

        if (SERVICE_HANDLER_FQN.equals(types.erasure(declared).toString())) {
            List<? extends TypeMirror> arguments = declared.getTypeArguments();
            if (arguments.size() == 1
                    && arguments.getFirst() instanceof DeclaredType contract
                    && isServiceContract((TypeElement) contract.asElement())) {
                addContract(contract, seenContracts, contracts);
            }
        } else if (isServiceContract(currentElement)) {
            addContract(declared, seenContracts, contracts);
        }

        for (TypeMirror supertype : types.directSupertypes(declared)) {
            collectServiceContracts(supertype, visitedTypes, seenContracts, contracts);
        }
    }

    private void addContract(DeclaredType contract, Set<String> seenContracts, List<DeclaredType> contracts) {
        TypeElement contractElement = (TypeElement) contract.asElement();
        if (seenContracts.add(contractElement.getQualifiedName().toString())) {
            contracts.add(contract);
        }
    }

    private boolean isServiceContract(TypeElement type) {
        return AnnotationMirrors.isPresent(type, SERVICE_CONTRACT_FQN);
    }

    private boolean hasTypeLevelResilience(TypeElement contract) {
        if (hasResilienceDeclaration(contract)) {
            return true;
        }
        for (TypeMirror supertype : types.directSupertypes(contract.asType())) {
            if (supertype instanceof DeclaredType declared
                    && hasTypeLevelResilience((TypeElement) declared.asElement())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMatchingResilience(ExecutableElement implementationMethod, DeclaredType contract) {
        for (Element member : elements.getAllMembers((TypeElement) contract.asElement())) {
            if (member.getKind() != ElementKind.METHOD
                    || !sameSignature(implementationMethod, (ExecutableElement) member, contract)) {
                continue;
            }
            if (hasResilienceDeclaration(member) || AnnotationMirrors.isPresent(member, Resilient.class.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean sameSignature(
            ExecutableElement implementationMethod, ExecutableElement contractMethod, DeclaredType contract) {
        if (!implementationMethod.getSimpleName().contentEquals(contractMethod.getSimpleName())
                || implementationMethod.getParameters().size()
                        != contractMethod.getParameters().size()) {
            return false;
        }
        if (!(types.asMemberOf(contract, contractMethod)
                instanceof javax.lang.model.type.ExecutableType contractType)) {
            return false;
        }
        for (int i = 0; i < implementationMethod.getParameters().size(); i++) {
            TypeMirror implementationParameter =
                    types.erasure(implementationMethod.getParameters().get(i).asType());
            TypeMirror contractParameter =
                    types.erasure(contractType.getParameterTypes().get(i));
            if (!types.isSameType(implementationParameter, contractParameter)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasResilienceDeclaration(Element element) {
        return RESILIENCE_DECLARATIONS.stream().anyMatch(fqn -> AnnotationMirrors.isPresent(element, fqn));
    }

    private void validateBounds(Element element) {
        Retry retry = element.getAnnotation(Retry.class);
        if (retry != null) {
            if (retry.maxRetries() < 0 || retry.maxRetries() > 100) {
                context.diagnostics().error(element, Diagnostics.retryMaxRetries());
            }
            if (retry.delayMs() < 0) {
                context.diagnostics().error(element, Diagnostics.retryDelayMs());
            }
            if (retry.backoffMultiplier() < 1.0) {
                context.diagnostics().error(element, Diagnostics.retryBackoffMultiplier());
            }
            if (retry.maxDelayMs() < 0) {
                context.diagnostics().error(element, Diagnostics.retryMaxDelayMs());
            }
        }

        Timeout timeout = element.getAnnotation(Timeout.class);
        if (timeout != null && timeout.value() <= 0) {
            context.diagnostics().error(element, Diagnostics.timeoutValue());
        }

        CircuitBreaker circuitBreaker = element.getAnnotation(CircuitBreaker.class);
        if (circuitBreaker != null) {
            if (circuitBreaker.maxFailures() <= 0) {
                context.diagnostics().error(element, Diagnostics.circuitBreakerMaxFailures());
            }
            if (circuitBreaker.resetTimeoutMs() <= 0) {
                context.diagnostics().error(element, Diagnostics.circuitBreakerResetTimeoutMs());
            }
        }

        Bulkhead bulkhead = element.getAnnotation(Bulkhead.class);
        if (bulkhead != null) {
            if (bulkhead.maxConcurrentCalls() <= 0) {
                context.diagnostics().error(element, Diagnostics.bulkheadMaxConcurrentCalls());
            }
            if (bulkhead.mode() == Bulkhead.Mode.REJECT
                    && (bulkhead.maxQueueSize() != 0 || bulkhead.queueTimeoutMs() != 0)) {
                context.diagnostics().error(element, Diagnostics.bulkheadRejectQueueFields());
            }
            if (bulkhead.mode() == Bulkhead.Mode.QUEUE
                    && (bulkhead.maxQueueSize() < 1 || bulkhead.maxQueueSize() > 1024)) {
                context.diagnostics().error(element, Diagnostics.bulkheadQueueSize());
            }
            if (bulkhead.mode() == Bulkhead.Mode.QUEUE
                    && (bulkhead.queueTimeoutMs() < 1 || bulkhead.queueTimeoutMs() > 60_000)) {
                context.diagnostics().error(element, Diagnostics.bulkheadQueueTimeoutMs());
            }
        }
    }
}
