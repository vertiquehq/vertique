// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.delayed.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.delayed.processor.DelayedJobContractModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Compile-time validators for scanned {@code @DelayedJobContract} interfaces.
 *
 * <p>Enforces, within a single compilation unit:
 * <ul>
 *   <li><b>FR-CG007-004</b> — the contract must extend {@code DelayedJobClient<P>} with a resolvable
 *       concrete payload type, and no two contracts may share a {@code name()}.</li>
 *   <li><b>FR-CG007-005</b> — emits a warning (or an error under
 *       {@code -Avertique.codegen.delayedjob.requireExecutor=true}) when no {@code DelayedJobExecutor}
 *       for the contract exists in the same compilation unit.</li>
 *   <li><b>FR-CG007-006</b> — emits an error when a same-unit executor's payload type {@code P} does
 *       not match the contract's.</li>
 * </ul>
 *
 * <p>Cross-module checks are out of scope (APT sees only the current unit); the runtime
 * {@code DelayedJobContractContributor} remains the global safety net.
 */
public final class DelayedJobValidator {

    private static final String DELAYED_JOB_CLIENT_FQN = "dev.vertique.job.delayed.DelayedJobClient";
    private static final String DELAYED_JOB_EXECUTOR_FQN = "dev.vertique.job.delayed.DelayedJobExecutor";

    private final CodegenContext ctx;
    private final boolean requireExecutor;

    /**
     * Constructs a validator bound to the given context.
     *
     * @param ctx             the codegen context; must not be {@code null}
     * @param requireExecutor when {@code true}, a missing same-unit executor is an error rather than a
     *                        warning (the {@code requireExecutor} processor option)
     */
    public DelayedJobValidator(CodegenContext ctx, boolean requireExecutor) {
        this.ctx = ctx;
        this.requireExecutor = requireExecutor;
    }

    /**
     * Validates every scanned contract model for the round and returns those eligible for emission.
     *
     * <p>Shape and duplicate-name failures (FR-CG007-004) exclude a model from the returned list so
     * its proxy is not emitted. Executor-alignment diagnostics (FR-CG007-005/006) are reported but do
     * not, by themselves, block emission of an otherwise well-formed proxy.
     *
     * @param models    the scanned contract models for this round
     * @param rootTypes all top-level types in the round (candidate executors are discovered among them)
     * @return the subset of {@code models} whose shape and name are valid
     */
    public List<DelayedJobContractModel> validate(List<DelayedJobContractModel> models, List<TypeElement> rootTypes) {
        // Resolve the SPI types once per round — they are invariant across all models and root types.
        TypeMirror clientErasure = erasureOf(DELAYED_JOB_CLIENT_FQN);
        TypeElement executorElement = ctx.elements().getTypeElement(DELAYED_JOB_EXECUTOR_FQN);
        List<ResolvedExecutor> executors = executorElement == null
                ? List.of()
                : rootTypes.stream()
                        .map(rootType -> resolveExecutor(rootType, executorElement))
                        .filter(Objects::nonNull)
                        .toList();

        Map<String, TypeElement> namesSeen = new LinkedHashMap<>();
        List<DelayedJobContractModel> valid = new ArrayList<>();

        TypeElement clientElement = ctx.elements().getTypeElement(DELAYED_JOB_CLIENT_FQN);
        for (DelayedJobContractModel model : models) {
            boolean shapeOk = validateShape(model, clientErasure);
            boolean methodsOk = validateNoExtraMethods(model, clientElement);
            boolean nameOk = validateName(model, namesSeen);
            validateExecutorAlignment(model, executors);
            if (shapeOk && methodsOk && nameOk) {
                valid.add(model);
            }
        }
        return valid;
    }

    /**
     * Rejects a contract that exposes any instance method other than {@code DelayedJobClient}'s six
     * {@code enqueue} overloads — whether declared directly, inherited through an intermediate interface,
     * {@code abstract}, or {@code default}. The generated proxy implements only the six known overloads, so:
     * an extra {@code abstract} method leaves the generated class uncompilable, and a {@code default} method
     * would execute in the generated proxy but throw {@code UnsupportedOperationException} in the reflective
     * proxy. Scanning all members (not just declared ones) keeps both routes failing early and consistently.
     *
     * <p>Static methods and {@link Object} methods are allowed. Inherited {@code enqueue} overloads are
     * recognized by their declaring type ({@code DelayedJobClient}).
     */
    private boolean validateNoExtraMethods(DelayedJobContractModel model, TypeElement clientElement) {
        Name clientName = clientElement == null ? null : clientElement.getQualifiedName();
        boolean ok = true;
        for (Element member : ctx.elements().getAllMembers(model.contractType())) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) member;
            if (method.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }
            if (method.getEnclosingElement() instanceof TypeElement declaring) {
                Name declaringName = declaring.getQualifiedName();
                if (declaringName.contentEquals("java.lang.Object")
                        || (clientName != null && declaringName.equals(clientName))) {
                    continue; // Object methods and DelayedJobClient's own enqueue overloads are expected.
                }
            }
            ctx.diagnostics()
                    .error(
                            method,
                            Diagnostics.delayedJobContractDeclaresMethod(
                                    fqn(model.contractType()),
                                    method.getSimpleName().toString()));
            ok = false;
        }
        return ok;
    }

    /**
     * FR-CG007-004 contract-shape: must be an interface, must extend {@code DelayedJobClient<P>} with a
     * concrete {@code P}.
     *
     * <p>The interface check runs first so that an abstract class annotated with
     * {@code @DelayedJobContract} yields the clear diagnostic rather than a downstream generated-source
     * compile error from {@code implements SomeClass}.
     */
    private boolean validateShape(DelayedJobContractModel model, TypeMirror clientErasure) {
        TypeElement contract = model.contractType();
        if (contract.getKind() != ElementKind.INTERFACE) {
            ctx.diagnostics().error(contract, Diagnostics.delayedJobMustBeInterface(fqn(contract)));
            return false;
        }
        if (clientErasure == null || !ctx.types().isAssignable(ctx.types().erasure(contract.asType()), clientErasure)) {
            ctx.diagnostics().error(contract, Diagnostics.delayedJobMustExtendClient(fqn(contract)));
            return false;
        }
        if (model.payloadType() == null) {
            ctx.diagnostics().error(contract, Diagnostics.delayedJobUnresolvablePayload(fqn(contract)));
            return false;
        }
        return true;
    }

    /** FR-CG007-004 duplicate name within the compilation unit. */
    private boolean validateName(DelayedJobContractModel model, Map<String, TypeElement> namesSeen) {
        TypeElement existing = namesSeen.putIfAbsent(model.name(), model.contractType());
        if (existing != null) {
            ctx.diagnostics()
                    .error(
                            model.contractType(),
                            Diagnostics.duplicateDelayedJobName(
                                    model.name(), fqn(existing), fqn(model.contractType())));
            return false;
        }
        return true;
    }

    /**
     * FR-CG007-005 / FR-CG007-006 same-unit executor presence and payload alignment.
     *
     * <p>Note: the payload-mismatch error (FR-CG007-006) is defense-in-depth. The runtime SPI bound
     * {@code DelayedJobExecutor<P, C extends DelayedJobClient<P>>} already forces {@code P} to equal the
     * contract's payload type, so {@code javac} rejects a mismatch before this processor runs. The check
     * is retained so a future relaxation of that bound (or an erased/raw executor binding) cannot slip a
     * mismatch through silently.
     */
    private void validateExecutorAlignment(DelayedJobContractModel model, List<ResolvedExecutor> executors) {
        TypeMirror contractErasure = ctx.types().erasure(model.contractType().asType());
        List<ResolvedExecutor> matching = executors.stream()
                .filter(e -> ctx.types().isSameType(ctx.types().erasure(e.contractType()), contractErasure))
                .toList();

        if (matching.isEmpty()) {
            String message = Diagnostics.delayedJobNoExecutorInUnit(fqn(model.contractType()));
            if (requireExecutor) {
                ctx.diagnostics().error(model.contractType(), message);
            } else {
                ctx.diagnostics().warning(model.contractType(), message);
            }
            return;
        }

        if (model.payloadType() == null) {
            return; // shape error already reported; nothing to compare against
        }
        for (ResolvedExecutor executor : matching) {
            if (executor.payloadType() == null) {
                continue; // executor payload unresolvable; do not emit a false mismatch
            }
            if (!ctx.types().isSameType(executor.payloadType(), model.payloadType())) {
                ctx.diagnostics()
                        .error(
                                executor.type(),
                                Diagnostics.delayedJobExecutorPayloadMismatch(
                                        fqn(executor.type()),
                                        executor.payloadType().toString(),
                                        fqn(model.contractType()),
                                        model.payloadType().toString()));
            }
        }
    }

    /** Returns the erasure of the type with the given FQN, or {@code null} when the type is not on the path. */
    private TypeMirror erasureOf(String fqn) {
        TypeElement element = ctx.elements().getTypeElement(fqn);
        return element == null ? null : ctx.types().erasure(element.asType());
    }

    private ResolvedExecutor resolveExecutor(TypeElement candidate, TypeElement executorElement) {
        TypeMirror contractArg = ctx.typeResolver()
                .resolveTypeArgument(candidate.asType(), executorElement, 1)
                .orElse(null);
        if (contractArg == null) {
            return null; // not a DelayedJobExecutor, or its contract type is unresolvable
        }
        TypeMirror payloadArg = ctx.typeResolver()
                .resolveTypeArgument(candidate.asType(), executorElement, 0)
                .orElse(null);
        return new ResolvedExecutor(candidate, payloadArg, contractArg);
    }

    private static String fqn(TypeElement type) {
        return type.getQualifiedName().toString();
    }

    /**
     * A {@code DelayedJobExecutor} implementation discovered in the round with its resolved
     * {@code DelayedJobExecutor<P, C>} type arguments.
     *
     * @param type        the executor implementation element
     * @param payloadType the resolved payload type {@code P}, or {@code null} when unresolvable
     * @param contractType the resolved contract type {@code C}
     */
    private record ResolvedExecutor(TypeElement type, TypeMirror payloadType, TypeMirror contractType) {}
}
