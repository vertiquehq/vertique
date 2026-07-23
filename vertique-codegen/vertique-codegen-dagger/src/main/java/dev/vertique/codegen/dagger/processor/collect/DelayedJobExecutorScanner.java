// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor.collect;

import com.palantir.javapoet.ClassName;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.NoAutoWire;
import dev.vertique.codegen.dagger.processor.Binding;
import dev.vertique.codegen.dagger.processor.support.Filters;
import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Root-element scanner that discovers concrete implementations of
 * {@code dev.vertique.job.delayed.DelayedJobExecutor}.
 *
 * <p>{@code DelayedJobExecutor<P, C>} is a plain interface with no marker annotation, so
 * annotation-rooted discovery cannot be used. This scanner walks every root element in the round
 * and checks whether the type is assignable to the erased {@code DelayedJobExecutor} type via
 * {@link dev.vertique.codegen.TypeResolver#isAssignable}.
 *
 * <p>If {@code DelayedJobExecutor} is not on the compile classpath (e.g., the module is not a
 * dependency of the current compilation), the scanner is a no-op.
 *
 * <p>Binding filter rules:
 * <ul>
 *   <li>Skip interfaces and abstract classes (only concrete impls are wired).</li>
 *   <li>Skip types annotated {@link NoAutoWire}.</li>
 *   <li>Skip types with no {@code @Inject} constructor (emit NOTE).</li>
 *   <li>Error on types with multiple {@code @Inject} constructors.</li>
 * </ul>
 *
 * <p>Collected bindings are contributed to the
 * {@link dev.vertique.codegen.dagger.processor.Qualifier#DELAYED_JOBS} qualifier.
 */
public final class DelayedJobExecutorScanner {

    private static final String DELAYED_JOB_EXECUTOR_FQN = "dev.vertique.job.delayed.DelayedJobExecutor";

    private final CodegenContext ctx;

    /**
     * Constructs a {@code DelayedJobExecutorScanner} bound to the given context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public DelayedJobExecutorScanner(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Scans the given candidate {@link TypeElement}s and appends any discovered
     * {@code DelayedJobExecutor} implementation bindings to the accumulator.
     *
     * @param candidates  the pre-filtered list of concrete root-element type elements to inspect;
     *                    must not be {@code null}
     * @param accumulator the list to which discovered bindings are appended; must not be
     *                    {@code null}
     */
    public void scan(List<TypeElement> candidates, List<Binding> accumulator) {
        TypeElement executorType = ctx.elements().getTypeElement(DELAYED_JOB_EXECUTOR_FQN);
        if (executorType == null) {
            // DelayedJobExecutor not on compile classpath — no-op
            return;
        }

        for (TypeElement candidate : candidates) {
            if (!isAssignableToExecutor(candidate, executorType)) {
                continue;
            }
            if (Filters.isOptedOut(candidate)) {
                continue;
            }
            if (!Filters.validateSingleInjectConstructor(candidate, ctx, "implements DelayedJobExecutor")) {
                continue;
            }
            accumulator.add(new Binding(candidate, ClassName.get(candidate)));
        }
    }

    // --- Internal helpers ---

    /**
     * Returns {@code true} if {@code candidate} is assignable to the erased
     * {@code DelayedJobExecutor} type using APT-layer erasure comparison.
     *
     * <p>Uses {@link javax.lang.model.util.Types#isAssignable} with erasure on both sides so that
     * parameterized types ({@code DelayedJobExecutor<P, C>}) are compared by raw type only.
     * This is the same strategy as {@link dev.vertique.codegen.TypeResolver#isAssignable} but
     * avoids the {@link Class}-based variant (which requires a runtime-loadable class) in favour
     * of the APT {@link TypeElement} form.
     *
     * @param candidate    the type to test
     * @param executorType the {@code DelayedJobExecutor} {@link TypeElement} resolved by APT
     * @return {@code true} when {@code candidate} is a subtype of the erased executor type
     */
    private boolean isAssignableToExecutor(TypeElement candidate, TypeElement executorType) {
        TypeMirror subErasure = ctx.types().erasure(candidate.asType());
        TypeMirror superErasure = ctx.types().erasure(executorType.asType());
        return ctx.types().isAssignable(subErasure, superErasure);
    }
}
