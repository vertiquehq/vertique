// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.application;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;

/**
 * Annotation processor that generates an application's {@link dev.vertique.core.VertiqueComponentFactory}
 * implementation — plus its {@code META-INF/services} registration — from a single
 * {@link dev.vertique.application.VertiqueApp}-annotated Dagger {@code @Component}.
 *
 * <p>For the one valid {@code @VertiqueApp} component the processor delegates emission to
 * {@link ComponentFactoryEmitter}, which produces a {@code final class
 * <ComponentSimpleName>VertiqueComponentFactory} in the component's package whose {@code build(…)}
 * delegates to {@code Dagger<ComponentSimpleName>} (referenced by name — Dagger generates it in the
 * same compilation). The processor returns {@code false} from {@code process()} so that Dagger's own
 * processor continues to see the same elements and emits {@code Dagger<ComponentSimpleName>}; the
 * by-name reference then resolves at the final javac compile with no reflection.
 *
 * <p>Validation — on any violation the processor emits a clear {@code Messager} error and generates
 * nothing:
 * <ol>
 *   <li>the {@code @VertiqueApp} target must be an <em>interface</em>;</li>
 *   <li>the target must be a <em>top-level</em> type — a nested {@code @VertiqueApp} component is
 *       rejected because Dagger names its generated builder {@code DaggerOuter_Inner} for a nested
 *       {@code Outer.Inner}, which the by-simple-name reference in the generated factory cannot
 *       resolve;</li>
 *   <li>it must be annotated with {@code dagger.Component};</li>
 *   <li>its interface hierarchy must include
 *       {@link dev.vertique.application.VertiqueApplicationComponent} (transitively is acceptable);</li>
 *   <li>the component must not declare a nested {@code @dagger.Component.Factory} or
 *       {@code @dagger.Component.Builder} — the processor generates code that calls
 *       {@code Dagger<Component>.builder().vertxModule(...).build()}, which is only valid with the
 *       default Dagger builder; a custom factory or builder would make the generated code fail to
 *       compile with a confusing error. Users who need a custom factory/builder must hand-write a
 *       {@link dev.vertique.core.VertiqueComponentFactory} and register it via
 *       {@code META-INF/services} instead of {@code @VertiqueApp}; and</li>
 *   <li>at most one {@code @VertiqueApp} may appear per <em>compilation</em> — the distinct count is
 *       accumulated across processing rounds, so two {@code @VertiqueApp} types reject whether they
 *       appear in the same round or in different rounds.</li>
 * </ol>
 *
 * <p>Emission is idempotent across rounds: each distinct component is emitted at most once, so a
 * component re-presented in a later round does not trigger a duplicate {@code Filer} write.
 *
 * <p>The component's inclusion of {@link dev.vertique.core.VertxModule} is <em>not</em> checked
 * here: a component missing it yields a clear compile error on the generated factory's
 * {@code .vertxModule(…)} call. The requirement is documented on {@link dev.vertique.application.VertiqueApp}.
 */
@SupportedAnnotationTypes(VertiqueAppProcessor.VERTIQUE_APP_FQN)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class VertiqueAppProcessor extends AbstractProcessor {

    /** Fully-qualified name of the {@code @VertiqueApp} annotation this processor reacts to. */
    static final String VERTIQUE_APP_FQN = "dev.vertique.application.VertiqueApp";

    /** Fully-qualified name of the Dagger {@code @Component} annotation a target must carry. */
    private static final String DAGGER_COMPONENT_FQN = "dagger.Component";

    /** Fully-qualified name of the contract a target's interface hierarchy must include. */
    private static final String APPLICATION_COMPONENT_FQN = "dev.vertique.application.VertiqueApplicationComponent";

    /** Fully-qualified name of the {@code dagger.Component.Factory} annotation. */
    private static final String DAGGER_COMPONENT_FACTORY_FQN = "dagger.Component.Factory";

    /** Fully-qualified name of the {@code dagger.Component.Builder} annotation. */
    private static final String DAGGER_COMPONENT_BUILDER_FQN = "dagger.Component.Builder";

    private CodegenContext ctx;
    private ComponentFactoryEmitter emitter;

    /**
     * Fully-qualified names of every valid {@code @VertiqueApp} component seen across all processing
     * rounds. Accumulated so the exactly-one rule is enforced per <em>compilation</em> rather than
     * per round. Insertion-ordered so the duplicate diagnostic can name a stable "other" offender.
     */
    private final Set<String> seenComponents = new LinkedHashSet<>();

    /**
     * Fully-qualified names of components whose factory + SPI registration have already been emitted.
     * Guards emission against re-presenting the same component in a later round, which would throw a
     * {@link javax.annotation.processing.FilerException} from a duplicate write instead of a clean
     * diagnostic.
     */
    private final Set<String> emitted = new HashSet<>();

    /**
     * Constructs a new {@code VertiqueAppProcessor}. Required by the {@link java.util.ServiceLoader}
     * mechanism used to load annotation processors.
     */
    public VertiqueAppProcessor() {}

    /**
     * {@inheritDoc}
     *
     * <p>Initializes the shared {@link CodegenContext} and the {@link ComponentFactoryEmitter}.
     */
    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.ctx = new CodegenContext(env);
        this.emitter = new ComponentFactoryEmitter(ctx);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Validates every {@code @VertiqueApp}-annotated type element in the round, accumulates the
     * valid ones into {@link #seenComponents} (so the exactly-one rule spans all rounds of the
     * compilation), enforces the exactly-one rule against that running set, and emits the single
     * survivor's factory exactly once across rounds. Always returns {@code false} so Dagger and
     * other processors continue to see the same elements.
     *
     * @param annotations the annotation types present in the round (only {@code @VertiqueApp})
     * @param roundEnv    the round environment for the current processing round
     * @return {@code false} always
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }

        TypeElement appAnnotation = ctx.elements().getTypeElement(VERTIQUE_APP_FQN);
        if (appAnnotation == null) {
            return false;
        }

        // Validate this round's annotated types and record the valid ones in the cross-round set.
        List<TypeElement> valid = roundEnv.getElementsAnnotatedWith(appAnnotation).stream()
                .filter(e -> e instanceof TypeElement)
                .map(e -> (TypeElement) e)
                .filter(this::validate)
                .toList();
        valid.forEach(c -> seenComponents.add(c.getQualifiedName().toString()));

        // Validation 6: exactly one @VertiqueApp per application — distinct count across all rounds.
        if (seenComponents.size() > 1) {
            valid.forEach(this::reportExactlyOneViolation);
            return false;
        }

        // Emit each newly-validated component at most once across rounds.
        valid.forEach(component -> {
            String fqn = component.getQualifiedName().toString();
            if (emitted.add(fqn)) {
                emitter.emit(component);
            }
        });
        return false;
    }

    // --- Internal helpers ---

    /**
     * Validates a single {@code @VertiqueApp} target against validations 1–5. Emits a {@code Messager}
     * error on the first failing check and returns {@code false}; returns {@code true} when all pass.
     *
     * @param component the candidate component type element; must not be {@code null}
     * @return {@code true} when the target is a valid {@code @VertiqueApp} component
     */
    private boolean validate(TypeElement component) {
        // Validation 1: target must be an interface.
        if (component.getKind() != ElementKind.INTERFACE) {
            ctx.diagnostics()
                    .error(
                            component,
                            "@VertiqueApp must be placed on an interface (the Dagger @Component); %s is a %s",
                            component.getQualifiedName(),
                            component.getKind());
            return false;
        }

        // Validation 2: target must be top-level — Dagger names a nested component's builder
        // DaggerOuter_Inner, which the generated factory's by-simple-name reference cannot resolve.
        if (component.getNestingKind() != NestingKind.TOP_LEVEL) {
            ctx.diagnostics()
                    .error(
                            component,
                            "@VertiqueApp must be placed on a top-level interface; %s is nested",
                            component.getQualifiedName());
            return false;
        }

        // Validation 3: target must be annotated with dagger.Component.
        if (!AnnotationMirrors.isPresent(component, DAGGER_COMPONENT_FQN)) {
            ctx.diagnostics()
                    .error(
                            component,
                            "@VertiqueApp %s must be annotated with @dagger.Component",
                            component.getQualifiedName());
            return false;
        }

        // Validation 4: interface hierarchy must include VertiqueApplicationComponent.
        if (!extendsApplicationComponent(component)) {
            ctx.diagnostics()
                    .error(
                            component,
                            "@VertiqueApp %s must extend %s",
                            component.getQualifiedName(),
                            APPLICATION_COMPONENT_FQN);
            return false;
        }

        // Validation 5: must not declare a custom @Component.Factory or @Component.Builder.
        // The generated factory calls Dagger<Component>.builder().vertxModule(...).build(), which
        // is only valid when Dagger emits the default builder. A custom factory/builder produces a
        // different generated API and causes a confusing downstream compile error.
        if (hasCustomDaggerBuilderOrFactory(component)) {
            ctx.diagnostics()
                    .error(
                            component,
                            "@VertiqueApp requires the default Dagger component builder (the processor generates"
                                    + " Dagger%s.builder().vertxModule(...).build()), but %s declares a custom"
                                    + " @Component.Factory/@Component.Builder, which is not supported."
                                    + " Remove the custom factory/builder, or hand-write a VertiqueComponentFactory"
                                    + " and register it via META-INF/services instead of @VertiqueApp.",
                            component.getSimpleName(),
                            component.getQualifiedName());
            return false;
        }

        return true;
    }

    /**
     * Reports the exactly-one violation against a single {@code @VertiqueApp} target, naming one of
     * the other offenders (drawn from the cross-round {@link #seenComponents} set) so the developer
     * can locate the duplicate.
     *
     * @param element the {@code @VertiqueApp}-annotated type element to attribute the error to; must
     *                not be {@code null}
     */
    private void reportExactlyOneViolation(TypeElement element) {
        String self = element.getQualifiedName().toString();
        String other = seenComponents.stream()
                .filter(fqn -> !fqn.equals(self))
                .findFirst()
                .orElse(self);
        ctx.diagnostics().error(element, "exactly one @VertiqueApp per application; found also %s", other);
    }

    /**
     * Returns {@code true} when the given component's transitive interface hierarchy includes
     * {@link #APPLICATION_COMPONENT_FQN}, comparing by erasure FQN.
     *
     * @param component the component type element; must not be {@code null}
     * @return {@code true} when {@code VertiqueApplicationComponent} is in the hierarchy
     */
    private boolean extendsApplicationComponent(TypeElement component) {
        return ctx.typeResolver()
                .allSupertypes(component.asType())
                .map(t -> ctx.types().erasure(t).toString())
                .anyMatch(APPLICATION_COMPONENT_FQN::equals);
    }

    /**
     * Returns {@code true} when the given component declares a nested type annotated with
     * {@code @dagger.Component.Factory} or {@code @dagger.Component.Builder}.
     *
     * <p>Detection uses {@link ElementFilter#typesIn} over the component's enclosed elements
     * and checks each nested type for the factory/builder annotations by FQN via
     * {@link AnnotationMirrors#isPresent}. This avoids a hard compile-time dependency on the
     * Dagger annotation classes in the processor's own classpath.
     *
     * @param component the component type element; must not be {@code null}
     * @return {@code true} when a custom {@code @Component.Factory} or {@code @Component.Builder}
     *         is present
     */
    private boolean hasCustomDaggerBuilderOrFactory(TypeElement component) {
        return ElementFilter.typesIn(component.getEnclosedElements()).stream()
                .anyMatch(nested -> AnnotationMirrors.isPresent(nested, DAGGER_COMPONENT_FACTORY_FQN)
                        || AnnotationMirrors.isPresent(nested, DAGGER_COMPONENT_BUILDER_FQN));
    }
}
