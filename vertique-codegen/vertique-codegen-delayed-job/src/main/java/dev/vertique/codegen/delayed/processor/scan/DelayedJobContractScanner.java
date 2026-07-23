// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.delayed.processor.scan;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.delayed.processor.DelayedJobContractModel;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * APT-side scanner that reads a {@code @DelayedJobContract} interface into a
 * {@link DelayedJobContractModel}.
 *
 * <p>Resolves the contract's payload type parameter {@code P} from its {@code DelayedJobClient<P>}
 * supertype using {@link dev.vertique.codegen.TypeResolver}. Resolution returns {@code null} when
 * {@code P} is not a concrete type (raw {@code DelayedJobClient} or a forwarding type variable);
 * validators turn that into a compile error.
 */
public final class DelayedJobContractScanner {

    private static final String DELAYED_JOB_CONTRACT_FQN = "dev.vertique.job.delayed.DelayedJobContract";
    private static final String DELAYED_JOB_CLIENT_FQN = "dev.vertique.job.delayed.DelayedJobClient";

    private static final String ATTR_NAME = "name";
    private static final String ATTR_MAX_ATTEMPTS = "maxAttempts";
    private static final String ATTR_QUEUE = "queue";
    private static final String ATTR_PRIORITY = "priority";

    private final CodegenContext ctx;

    /**
     * Constructs a scanner bound to the given codegen context.
     *
     * @param ctx the codegen context; must not be {@code null}
     */
    public DelayedJobContractScanner(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Scans a {@code @DelayedJobContract} interface into a model.
     *
     * @param contract the contract interface element; must carry {@code @DelayedJobContract}
     * @return the scanned model; its {@code payloadType()} is {@code null} when {@code P} is
     *         unresolvable
     */
    public DelayedJobContractModel scan(TypeElement contract) {
        AnnotationMirror mirror = AnnotationMirrors.findByFqn(contract, DELAYED_JOB_CONTRACT_FQN)
                .orElseThrow(() -> new IllegalStateException(
                        "scan() called on a type without @DelayedJobContract: " + contract.getQualifiedName()));

        String name =
                ctx.annotations().attribute(mirror, ATTR_NAME, String.class).orElse("");
        int maxAttempts = ctx.annotations()
                .attribute(mirror, ATTR_MAX_ATTEMPTS, Integer.class)
                .orElse(3);
        String queue =
                ctx.annotations().attribute(mirror, ATTR_QUEUE, String.class).orElse("default");
        int priority = ctx.annotations()
                .attribute(mirror, ATTR_PRIORITY, Integer.class)
                .orElse(0);

        TypeMirror payloadType = resolvePayloadType(contract);

        return new DelayedJobContractModel(contract, name, maxAttempts, queue, priority, payloadType);
    }

    private TypeMirror resolvePayloadType(TypeElement contract) {
        TypeElement clientElement = ctx.elements().getTypeElement(DELAYED_JOB_CLIENT_FQN);
        if (clientElement == null) {
            return null;
        }
        return ctx.typeResolver()
                .resolveTypeArgument(contract.asType(), clientElement, 0)
                .orElse(null);
    }
}
