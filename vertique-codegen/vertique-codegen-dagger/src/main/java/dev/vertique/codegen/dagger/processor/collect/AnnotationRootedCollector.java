// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor.collect;

import com.palantir.javapoet.ClassName;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.NoAutoWire;
import dev.vertique.codegen.dagger.processor.Binding;
import dev.vertique.codegen.dagger.processor.support.Filters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * Shared base for annotation-rooted collectors that discover auto-wirable types from
 * {@link RoundEnvironment#getElementsAnnotatedWith}.
 *
 * <p>Each subclass provides one or more marker annotation FQNs via {@link #markerFqns()}. This
 * base class handles the common filtering pipeline:
 * <ol>
 *   <li>Skip non-{@link TypeElement} elements (e.g., annotated methods).</li>
 *   <li>Skip types annotated {@link NoAutoWire}.</li>
 *   <li>For types that require a constructor (i.e., {@link #requiresInjectConstructor()} returns
 *       {@code true}), validate exactly one {@code @Inject} constructor:
 *       <ul>
 *         <li>Zero found → emit a {@code NOTE} and skip.</li>
 *         <li>Multiple found → emit an {@code ERROR} and skip.</li>
 *       </ul>
 *   </li>
 *   <li>Produce a {@link Binding} via {@link #createBinding(TypeElement)}.</li>
 * </ol>
 */
public abstract class AnnotationRootedCollector {

    /** The {@link CodegenContext} shared across the processor. */
    protected final CodegenContext ctx;

    /**
     * Constructs a collector bound to the given context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    protected AnnotationRootedCollector(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns the fully-qualified names of the marker annotations this collector handles.
     *
     * <p>The marker type elements are resolved by FQN via
     * {@link javax.lang.model.util.Elements#getTypeElement} rather than as compile-time class
     * references, so the processor jar does not hard-depend on the marker annotation modules.
     *
     * @return a non-empty, non-null list of FQNs
     */
    protected abstract List<String> markerFqns();

    /**
     * Returns {@code true} if the types discovered by this collector must have exactly one
     * {@code @Inject} constructor.
     *
     * <p>Returns {@code false} for interface-based markers such as {@code @RestClient}, where no
     * constructor is expected.
     *
     * @return {@code true} when constructor validation is required
     */
    protected boolean requiresInjectConstructor() {
        return true;
    }

    /**
     * Validates the kind of an annotated type before the standard filters (abstract-class skip,
     * {@code @Inject} constructor count) run.
     *
     * <p>Subclasses can use this hook to reject types whose kind is incompatible with the marker
     * (e.g., {@code @RestClient} on a class). The default implementation accepts all kinds.
     *
     * @param type the annotated type element; never {@code null}
     * @return {@code true} when the type is acceptable for this marker; {@code false} after
     *         emitting an appropriate diagnostic causes the type to be silently skipped
     */
    protected boolean validateKind(TypeElement type) {
        return true;
    }

    /**
     * Produces a {@link Binding} for the given type element that has passed all filters.
     *
     * <p>Subclasses may return {@code null} to signal that this specific type should be skipped
     * (e.g., {@link KafkaConsumerCollector} returns {@code null} for interfaces). A {@code null}
     * return is treated as a silent skip with no diagnostic.
     *
     * @param type the validated type element; never {@code null}
     * @return the binding to record, or {@code null} to skip this type silently
     */
    protected Binding createBinding(TypeElement type) {
        return new Binding(type, ClassName.get(type));
    }

    /**
     * Runs the collection phase for one processing round, accumulating {@link Binding} records
     * into the provided accumulator list.
     *
     * @param roundEnv   the round environment; must not be {@code null}
     * @param accumulator the list to which discovered bindings are appended; must not be
     *                    {@code null}
     */
    public final void collect(RoundEnvironment roundEnv, List<Binding> accumulator) {
        for (String fqn : markerFqns()) {
            TypeElement markerType = ctx.elements().getTypeElement(fqn);
            if (markerType == null) {
                // Marker annotation not on the compile classpath — skip silently
                continue;
            }

            Set<? extends javax.lang.model.element.Element> annotated = roundEnv.getElementsAnnotatedWith(markerType);

            for (javax.lang.model.element.Element el : annotated) {
                if (!(el instanceof TypeElement type)) {
                    continue;
                }
                if (Filters.isOptedOut(type)) {
                    continue;
                }
                // Subclass kind validation runs FIRST so collectors can emit their own diagnostics
                // for incompatible kinds (e.g., @RestClient on a class) BEFORE the generic
                // abstract-class skip silently drops the type.
                if (!validateKind(type)) {
                    continue;
                }
                // Skip abstract classes — Dagger cannot instantiate them, so emitting a binding
                // would only fail at component build time. Interfaces are NOT skipped here:
                // some markers (e.g. @RestClient) target interfaces deliberately. Subclasses
                // that need different per-kind handling override validateKind() above.
                if (type.getKind() == ElementKind.CLASS && type.getModifiers().contains(Modifier.ABSTRACT)) {
                    continue;
                }
                if (requiresInjectConstructor()
                        && type.getKind() != ElementKind.INTERFACE
                        && !Filters.validateSingleInjectConstructor(type, ctx, "is annotated")) {
                    continue;
                }
                Binding binding = createBinding(type);
                if (binding == null) {
                    continue;
                }
                accumulator.add(binding);
            }
        }
    }

    /**
     * Collects bindings into a fresh list and returns it.
     *
     * @param roundEnv the round environment; must not be {@code null}
     * @return a (possibly empty) list of discovered bindings
     */
    final List<Binding> collect(RoundEnvironment roundEnv) {
        List<Binding> result = new ArrayList<>();
        collect(roundEnv, result);
        return result;
    }
}
