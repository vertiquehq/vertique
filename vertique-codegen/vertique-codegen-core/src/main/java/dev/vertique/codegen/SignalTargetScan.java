// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

/**
 * INTERNAL seam — shared by the annotation processors of this framework and of other repositories, which call it
 * across modules; not an application contract and outside the maturity promise of {@code vertique-codegen-core}. An
 * application never calls this type.
 *
 * <p>Holds the one scan of {@code dev.vertique.workflow.source.WorkflowSignalTarget} and its repeatable container on
 * the methods of an interface, and the compile rules every transport shares, so that every processor applies one rule
 * set. The annotations are identified by fully qualified name, because this module takes no dependency on the workflow
 * modules. Transport-specific settings stay with the processor of the transport.
 *
 * <p>The scan refuses, on the offending element and naming it: a target on a concrete class, a target inherited from a
 * super-interface, a target method with other than one parameter, a generic message type, two message types on one
 * interface, a signal the contract does not declare, a message type that differs from the signal's payload type, both
 * or neither of {@code key} and {@code businessKey}, a {@code key}, {@code businessKey} or {@code dedupBy} the message
 * type has no accessor for or whose accessor does not return {@code String}, a missing {@code dedupBy} where the
 * {@link DedupRequirement} demands one, and targets that disagree on {@code dedupBy}.
 *
 * <p>An accessor is a public, non-static, no-argument method that returns a value: the method named like the property,
 * which is also a record component, or else {@code get} or {@code is} followed by the capitalized property.
 * {@link #rejectUnsupportedHosts} covers the types the scan never sees, because no processor generates a source for
 * them.
 */
public final class SignalTargetScan {

    /** Which targets must name a {@code dedupBy}. A business-key target always must. */
    public enum DedupRequirement {
        /** Only a target that addresses its instance by {@code businessKey} must name {@code dedupBy}. */
        BUSINESS_KEY_TARGETS,
        /** Every target must name {@code dedupBy}. */
        EVERY_TARGET
    }

    /**
     * One validated target.
     *
     * @param method the interface method that carries the target
     * @param contract the workflow contract that declares the signal
     * @param signal the name of the signal
     * @param key the message accessor that yields the instance key, or empty when the target uses {@code businessKey}
     * @param businessKey the message accessor that yields the business key, or empty when the target uses {@code key}
     * @param dedupBy the message accessor that yields the dedup key, or empty when the target names none
     * @param onNoMatch the name of the {@code SignalNoMatch} constant the target selects
     * @param messageType the type of the method's only parameter, which is the message
     * @param keyMethod the name of the message method that reads {@code key}, or else {@code businessKey}, resolved
     *     once by the scan
     * @param dedupByMethod the name of the message method that reads {@code dedupBy}, or empty when the target names
     *     none
     */
    public record Target(
            ExecutableElement method,
            TypeElement contract,
            String signal,
            String key,
            String businessKey,
            String dedupBy,
            String onNoMatch,
            TypeMirror messageType,
            String keyMethod,
            String dedupByMethod) {}

    private static final String WORKFLOW_CONTRACT_FQN = "dev.vertique.workflow.contract.WorkflowContract";
    private static final String WORKFLOW_SIGNAL_FQN = "dev.vertique.workflow.contract.WorkflowSignal";
    private static final String WORKFLOW_INSTANCE_ID_FQN = "dev.vertique.workflow.ops.WorkflowInstanceId";
    private static final String SIGNAL_SOURCE_FORM_FQN = "dev.vertique.workflow.source.SignalSourceForm";
    private static final Set<String> KEY_PARAMETER_FQNS = Set.of(
            "dev.vertique.workflow.contract.IdempotencyKey",
            "dev.vertique.workflow.contract.BusinessKey",
            "dev.vertique.workflow.contract.SubjectRef",
            "dev.vertique.workflow.contract.SignalDedupKey");

    private SignalTargetScan() {}

    /**
     * Scans the methods of a type for signal targets and validates them.
     *
     * @param context the codegen context that reports the diagnostics; must not be {@code null}
     * @param type the type whose methods are scanned; must not be {@code null}
     * @param requirement which targets must name {@code dedupBy}; must not be {@code null}
     * @return the validated targets in declaration order; empty when the type carries no target, and empty when any
     *     rule was violated, after every violation has been reported
     */
    public static List<Target> scan(CodegenContext context, TypeElement type, DedupRequirement requirement) {
        List<ExecutableElement> methods = SignalListeners.targetMethods(type);
        boolean isInterface = type.getKind() == ElementKind.INTERFACE;
        List<ExecutableElement> inherited = isInterface ? SignalListeners.inheritedTargetMethods(type) : List.of();
        if (methods.isEmpty() && inherited.isEmpty()) {
            return List.of();
        }
        String typeName = type.getQualifiedName().toString();
        if (!isInterface) {
            for (ExecutableElement method : methods) {
                context.diagnostics()
                        .error(
                                method,
                                "%s.%s carries @WorkflowSignalTarget, which is allowed only on a method of an"
                                        + " interface; %s is not an interface",
                                typeName,
                                method.getSimpleName(),
                                typeName);
            }
            return List.of();
        }

        Scanner scanner = new Scanner(context, typeName, requirement);
        for (ExecutableElement method : inherited) {
            scanner.refuseInherited(type, method);
        }
        for (ExecutableElement method : methods) {
            scanner.scanMethod(method);
        }
        return scanner.result();
    }

    /**
     * Reports, on its type, each type of the round that declares a target and that no processor turns into a signal
     * source: a type that is neither annotated {@code @KafkaListener} nor an interface annotated with an annotation
     * that carries {@code @SignalSourceForm}. Without this report the target compiles and does nothing.
     *
     * <p>A type annotated {@code @KafkaListener} is left to the Kafka processor, which reports a class and validates an
     * interface, so no type is reported twice. A processor that sees every type of the round, such as the wiring
     * processor, calls this once per round.
     *
     * @param context the codegen context that reports the diagnostics; must not be {@code null}
     * @param roundEnv the current round; must not be {@code null}
     */
    public static void rejectUnsupportedHosts(CodegenContext context, RoundEnvironment roundEnv) {
        Set<TypeElement> hosts = new LinkedHashSet<>();
        for (String annotationName : List.of(SignalListeners.SIGNAL_TARGET_FQN, SignalListeners.SIGNAL_TARGETS_FQN)) {
            TypeElement annotation = context.elements().getTypeElement(annotationName);
            if (annotation == null) {
                continue;
            }
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element instanceof ExecutableElement && element.getEnclosingElement() instanceof TypeElement host) {
                    hosts.add(host);
                }
            }
        }
        for (TypeElement host : hosts) {
            if (!AnnotationMirrors.isPresent(host, SignalListeners.KAFKA_LISTENER_FQN) && !isSignalSourceForm(host)) {
                context.diagnostics()
                        .error(
                                host,
                                "%s carries @WorkflowSignalTarget, but it is neither a @KafkaListener interface nor"
                                        + " annotated with an annotation that carries @SignalSourceForm, so no signal"
                                        + " source is generated for it and its targets would never run",
                                host.getQualifiedName());
            }
        }
    }

    private static boolean isSignalSourceForm(TypeElement host) {
        return host.getKind() == ElementKind.INTERFACE
                && host.getAnnotationMirrors().stream()
                        .anyMatch(mirror -> AnnotationMirrors.isPresent(
                                mirror.getAnnotationType().asElement(), SIGNAL_SOURCE_FORM_FQN));
    }

    private static List<AnnotationMirror> targetMirrors(ExecutableElement method) {
        List<AnnotationMirror> mirrors = new ArrayList<>();
        for (AnnotationMirror mirror : method.getAnnotationMirrors()) {
            String fqn = ((TypeElement) mirror.getAnnotationType().asElement())
                    .getQualifiedName()
                    .toString();
            if (SignalListeners.SIGNAL_TARGET_FQN.equals(fqn)) {
                mirrors.add(mirror);
            } else if (SignalListeners.SIGNAL_TARGETS_FQN.equals(fqn)) {
                for (AnnotationValue value : valueOf(mirror, "value")) {
                    if (value.getValue() instanceof AnnotationMirror nested) {
                        mirrors.add(nested);
                    }
                }
            }
        }
        return mirrors;
    }

    @SuppressWarnings("unchecked")
    private static List<AnnotationValue> valueOf(AnnotationMirror mirror, String name) {
        return mirror.getElementValues().entrySet().stream()
                .filter(entry -> entry.getKey().getSimpleName().contentEquals(name))
                .map(entry -> entry.getValue().getValue())
                .filter(List.class::isInstance)
                .map(value -> (List<AnnotationValue>) value)
                .findFirst()
                .orElse(List.of());
    }

    /** The state of one scan: the targets accepted so far, the first message type and dedup key seen, and validity. */
    private static final class Scanner {

        private final CodegenContext context;
        private final String typeName;
        private final DedupRequirement requirement;
        private final List<Target> targets = new ArrayList<>();
        private TypeMirror messageType;
        private String dedupBy;
        private boolean valid = true;

        private Scanner(CodegenContext context, String typeName, DedupRequirement requirement) {
            this.context = context;
            this.typeName = typeName;
            this.requirement = requirement;
        }

        List<Target> result() {
            return valid ? List.copyOf(targets) : List.of();
        }

        void scanMethod(ExecutableElement method) {
            String where = typeName + "." + method.getSimpleName();
            if (method.getParameters().size() != 1) {
                fail(
                        method,
                        "%s carries @WorkflowSignalTarget and must declare exactly one parameter, the message, but"
                                + " declares %d",
                        where,
                        method.getParameters().size());
                return;
            }
            TypeMirror message = method.getParameters().get(0).asType();
            if (isGeneric(message)) {
                fail(
                        method,
                        "%s takes message type %s, which is generic; the generated source names its message type by"
                                + " class, so declare a type that has no type parameters",
                        where,
                        message);
                return;
            }
            if (messageType == null) {
                messageType = message;
            } else if (!context.types().isSameType(messageType, message)) {
                fail(
                        method,
                        "%s takes message type %s, but another method of %s takes %s; the targets of one interface"
                                + " share one message type",
                        where,
                        message,
                        typeName,
                        messageType);
                return;
            }
            for (AnnotationMirror mirror : targetMirrors(method)) {
                scanTarget(method, where, message, mirror);
            }
        }

        void refuseInherited(TypeElement type, ExecutableElement inherited) {
            fail(
                    type,
                    "%s inherits @WorkflowSignalTarget on %s.%s from a super-interface; the generated source would"
                            + " drop it, so declare the target on a method of %s itself",
                    typeName,
                    ((TypeElement) inherited.getEnclosingElement()).getQualifiedName(),
                    inherited.getSimpleName(),
                    typeName);
        }

        private static boolean isGeneric(TypeMirror message) {
            if (message.getKind() == TypeKind.TYPEVAR) {
                return true;
            }
            return message instanceof DeclaredType declared
                    && (!declared.getTypeArguments().isEmpty()
                            || !((TypeElement) declared.asElement())
                                    .getTypeParameters()
                                    .isEmpty());
        }

        private void scanTarget(ExecutableElement method, String where, TypeMirror message, AnnotationMirror mirror) {
            String signal = string(mirror, "signal");
            String key = string(mirror, "key");
            String businessKey = string(mirror, "businessKey");
            String targetDedupBy = string(mirror, "dedupBy");
            boolean ok = true;

            TypeElement contract = contractOf(mirror);
            if (contract == null || !AnnotationMirrors.isPresent(contract, WORKFLOW_CONTRACT_FQN)) {
                fail(
                        method,
                        "%s names %s as the contract of signal '%s', but it is not a @WorkflowContract",
                        where,
                        contract == null ? "no contract" : contract.getQualifiedName(),
                        signal);
                ok = false;
            } else {
                ExecutableElement declared = signalMethod(contract, signal);
                if (declared == null) {
                    fail(
                            method,
                            "%s names signal '%s', which contract %s does not declare",
                            where,
                            signal,
                            contract.getQualifiedName());
                    ok = false;
                } else {
                    TypeMirror payload = payloadType(declared);
                    if (payload != null && !context.types().isSameType(payload, message)) {
                        fail(
                                method,
                                "%s takes message type %s, but signal '%s' of %s carries %s; the annotated form maps no"
                                        + " payload, so the types must be the same",
                                where,
                                message,
                                signal,
                                contract.getQualifiedName(),
                                payload);
                        ok = false;
                    }
                }
            }

            if (key.isBlank() == businessKey.isBlank()) {
                fail(
                        method,
                        "%s must set exactly one of key and businessKey on signal '%s', but sets %s",
                        where,
                        signal,
                        key.isBlank() ? "neither" : "both");
                ok = false;
            }
            String keyMethod = resolveAccessor(method, where, "key", key, message);
            String businessKeyMethod = resolveAccessor(method, where, "businessKey", businessKey, message);
            String dedupByMethod = resolveAccessor(method, where, "dedupBy", targetDedupBy, message);
            ok &= keyMethod != null && businessKeyMethod != null && dedupByMethod != null;

            boolean dedupRequired = requirement == DedupRequirement.EVERY_TARGET || !businessKey.isBlank();
            if (dedupRequired && targetDedupBy.isBlank()) {
                fail(
                        method,
                        "%s must set dedupBy on signal '%s'%s",
                        where,
                        signal,
                        requirement == DedupRequirement.EVERY_TARGET
                                ? ", because every target of this source needs a dedup key"
                                : ", because a business-key target needs a dedup key");
                ok = false;
            }

            if (dedupBy == null) {
                dedupBy = targetDedupBy;
            } else if (!dedupBy.equals(targetDedupBy)) {
                fail(
                        method,
                        "%s sets dedupBy '%s' on signal '%s', but another target of %s sets '%s'; a message has one"
                                + " dedup key, so the targets of one interface agree on dedupBy",
                        where,
                        targetDedupBy,
                        signal,
                        typeName,
                        dedupBy);
                ok = false;
            }

            if (ok) {
                String onNoMatch = context.annotations()
                        .attribute(mirror, "onNoMatch", VariableElement.class)
                        .map(constant -> constant.getSimpleName().toString())
                        .orElse("IGNORE");
                targets.add(new Target(
                        method,
                        contract,
                        signal,
                        key,
                        businessKey,
                        targetDedupBy,
                        onNoMatch,
                        message,
                        key.isBlank() ? businessKeyMethod : keyMethod,
                        dedupByMethod));
            }
        }

        /**
         * Resolves the accessor of a property once.
         *
         * @return the name of the accessor method, empty when the property is not set, and {@code null} after the
         *     violation was reported
         */
        private String resolveAccessor(
                ExecutableElement method, String where, String attribute, String name, TypeMirror message) {
            if (name.isBlank()) {
                return "";
            }
            ExecutableElement accessor = findAccessor(message, name);
            if (accessor == null) {
                fail(
                        method,
                        "%s sets %s '%s', but message type %s has no accessor for it",
                        where,
                        attribute,
                        name,
                        message);
                return null;
            }
            if (!isString(accessor.getReturnType())) {
                fail(
                        method,
                        "%s sets %s '%s', but %s.%s() returns %s; the accessor of a key must return String",
                        where,
                        attribute,
                        name,
                        message,
                        accessor.getSimpleName(),
                        accessor.getReturnType());
                return null;
            }
            return accessor.getSimpleName().toString();
        }

        private ExecutableElement findAccessor(TypeMirror message, String property) {
            if (message.getKind() != TypeKind.DECLARED) {
                return null;
            }
            TypeElement element = (TypeElement) ((DeclaredType) message).asElement();
            String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
            List<ExecutableElement> methods =
                    ElementFilter.methodsIn(context.elements().getAllMembers(element));
            for (String candidate : List.of(property, "get" + suffix, "is" + suffix)) {
                for (ExecutableElement method : methods) {
                    if (method.getSimpleName().contentEquals(candidate)
                            && method.getParameters().isEmpty()
                            && method.getReturnType().getKind() != TypeKind.VOID
                            && method.getModifiers().contains(Modifier.PUBLIC)
                            && !method.getModifiers().contains(Modifier.STATIC)) {
                        return method;
                    }
                }
            }
            return null;
        }

        private static boolean isString(TypeMirror type) {
            return type instanceof DeclaredType declared
                    && declared.asElement() instanceof TypeElement element
                    && element.getQualifiedName().contentEquals("java.lang.String");
        }

        private TypeElement contractOf(AnnotationMirror mirror) {
            return context.annotations()
                    .attributeClass(mirror, "contract")
                    .filter(DeclaredType.class::isInstance)
                    .map(type -> (TypeElement) ((DeclaredType) type).asElement())
                    .orElse(null);
        }

        private ExecutableElement signalMethod(TypeElement contract, String signal) {
            for (ExecutableElement method :
                    ElementFilter.methodsIn(context.elements().getAllMembers(contract))) {
                boolean declares = AnnotationMirrors.findByFqn(method, WORKFLOW_SIGNAL_FQN)
                        .flatMap(mirror -> context.annotations().attribute(mirror, "value", String.class))
                        .filter(signal::equals)
                        .isPresent();
                if (declares) {
                    return method;
                }
            }
            return null;
        }

        /** The signal's payload: its only parameter that is neither the instance id nor annotated as a key. */
        private TypeMirror payloadType(ExecutableElement signalMethod) {
            List<TypeMirror> payloads = new ArrayList<>();
            for (VariableElement parameter : signalMethod.getParameters()) {
                boolean keyed =
                        KEY_PARAMETER_FQNS.stream().anyMatch(fqn -> AnnotationMirrors.isPresent(parameter, fqn));
                boolean instanceId = parameter.asType() instanceof DeclaredType declared
                        && declared.asElement() instanceof TypeElement element
                        && element.getQualifiedName().contentEquals(WORKFLOW_INSTANCE_ID_FQN);
                if (!keyed && !instanceId) {
                    payloads.add(parameter.asType());
                }
            }
            return payloads.size() == 1 ? payloads.get(0) : null;
        }

        private String string(AnnotationMirror mirror, String name) {
            return context.annotations().attribute(mirror, name, String.class).orElse("");
        }

        private void fail(Element element, String message, Object... args) {
            valid = false;
            context.diagnostics().error(element, message, args);
        }
    }
}
