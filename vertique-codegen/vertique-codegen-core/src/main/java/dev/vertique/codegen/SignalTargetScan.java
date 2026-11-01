// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
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
 * <p>The scan refuses, on the offending element and naming it: a target on a concrete class, a target method with other
 * than one parameter, two message types on one interface, a signal the contract does not declare, a message type that
 * differs from the signal's payload type, both or neither of {@code key} and {@code businessKey}, a {@code key},
 * {@code businessKey} or {@code dedupBy} the message type has no accessor for, a missing {@code dedupBy} where the
 * {@link DedupRequirement} demands one, and targets that disagree on {@code dedupBy}.
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
     */
    public record Target(
            ExecutableElement method,
            TypeElement contract,
            String signal,
            String key,
            String businessKey,
            String dedupBy,
            String onNoMatch,
            TypeMirror messageType) {}

    private static final String WORKFLOW_CONTRACT_FQN = "dev.vertique.workflow.contract.WorkflowContract";
    private static final String WORKFLOW_SIGNAL_FQN = "dev.vertique.workflow.contract.WorkflowSignal";
    private static final String WORKFLOW_INSTANCE_ID_FQN = "dev.vertique.workflow.ops.WorkflowInstanceId";
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
        List<ExecutableElement> methods = ElementFilter.methodsIn(type.getEnclosedElements()).stream()
                .filter(method -> !targetMirrors(method).isEmpty())
                .toList();
        if (methods.isEmpty()) {
            return List.of();
        }
        String typeName = type.getQualifiedName().toString();
        if (type.getKind() != ElementKind.INTERFACE) {
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
        for (ExecutableElement method : methods) {
            scanner.scanMethod(method);
        }
        return scanner.result();
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
            ok &= requireAccessor(method, where, "key", key, message);
            ok &= requireAccessor(method, where, "businessKey", businessKey, message);
            ok &= requireAccessor(method, where, "dedupBy", targetDedupBy, message);

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
                targets.add(new Target(method, contract, signal, key, businessKey, targetDedupBy, onNoMatch, message));
            }
        }

        private boolean requireAccessor(
                ExecutableElement method, String where, String attribute, String name, TypeMirror message) {
            if (name.isBlank() || hasAccessor(message, name)) {
                return true;
            }
            fail(
                    method,
                    "%s sets %s '%s', but message type %s has no accessor for it",
                    where,
                    attribute,
                    name,
                    message);
            return false;
        }

        private boolean hasAccessor(TypeMirror message, String property) {
            if (message.getKind() != TypeKind.DECLARED) {
                return false;
            }
            TypeElement element = (TypeElement) ((DeclaredType) message).asElement();
            boolean bareName = element.getKind() == ElementKind.RECORD;
            String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
            for (ExecutableElement candidate :
                    ElementFilter.methodsIn(context.elements().getAllMembers(element))) {
                String name = candidate.getSimpleName().toString();
                if ((bareName && name.equals(property)) || name.equals("get" + suffix) || name.equals("is" + suffix)) {
                    if (candidate.getParameters().isEmpty()
                            && candidate.getReturnType().getKind() != TypeKind.VOID
                            && candidate.getModifiers().contains(Modifier.PUBLIC)
                            && !candidate.getModifiers().contains(Modifier.STATIC)) {
                        return true;
                    }
                }
            }
            return false;
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

        private void fail(ExecutableElement method, String message, Object... args) {
            valid = false;
            context.diagnostics().error(method, message, args);
        }
    }
}
