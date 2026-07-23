// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.cron.processor;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.cron.processor.validate.CronExpressionValidator;
import dev.vertique.codegen.cron.processor.validate.DuplicateIdValidator;
import dev.vertique.codegen.cron.processor.validate.DuplicateIdValidator.MethodMirror;
import dev.vertique.codegen.cron.processor.validate.PolicyValueValidator;
import dev.vertique.codegen.cron.processor.validate.ServiceCouplingValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * Annotation processor that validates {@code @CronJob}-annotated methods at compile time.
 *
 * <p>Validates four concerns for each annotated method:
 * <ol>
 *   <li>Cron expression syntax — {@link CronExpressionValidator} calls
 *       {@code new CronExpression(expr)} and reports parse failures.</li>
 *   <li>Service contract coupling — {@link ServiceCouplingValidator} mirrors
 *       {@code ContractDiscovery.findContract}: checks the handler pattern
 *       ({@code ServiceHandler<C>}) first, then the direct-implementation pattern; requires a
 *       unique {@code @ServiceContract} and a {@code @ServiceOperation} on the matched contract
 *       method (unconditional).</li>
 *   <li>Policy bounds — {@link PolicyValueValidator} checks blank id, maxAttempts range,
 *       overlap/mode contradiction, and unresolvable timezone (warning).</li>
 *   <li>Duplicate ids — {@link DuplicateIdValidator} checks for duplicate {@code id} values within
 *       the same enclosing class.</li>
 * </ol>
 *
 * <p>Pure validation: no source files are generated. The {@code @CronJob} mirror is resolved once
 * per method and threaded through the per-method validators so attribute reads aren't repeated.
 * Returns {@code false} so other processors continue to see the annotated elements.
 */
@SupportedAnnotationTypes("dev.vertique.job.cron.CronJob")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class CronJobProcessor extends AbstractProcessor {

    private CodegenContext ctx;
    private CronExpressionValidator cron;
    private ServiceCouplingValidator coupling;
    private PolicyValueValidator policy;
    private DuplicateIdValidator duplicates;

    /**
     * Constructs a new {@code CronJobProcessor}. Required by the {@link java.util.ServiceLoader}
     * mechanism used to load annotation processors.
     */
    public CronJobProcessor() {}

    /**
     * {@inheritDoc}
     *
     * <p>Initialises the shared {@link CodegenContext} and all four validators.
     *
     * @param env the processing environment provided by the compiler
     */
    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        ctx = new CodegenContext(env);
        cron = new CronExpressionValidator(ctx);
        coupling = new ServiceCouplingValidator(ctx);
        policy = new PolicyValueValidator(ctx);
        duplicates = new DuplicateIdValidator(ctx);
    }

    /**
     * {@inheritDoc}
     *
     * @param annotations the annotation types being processed
     * @param round       the current round environment
     * @return {@code false} always
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        TypeElement cronJobType = ctx.elements().getTypeElement(CronAnnotations.CRON_JOB);
        if (cronJobType == null) {
            return false;
        }

        Map<TypeElement, List<MethodMirror>> byOwner = new LinkedHashMap<>();
        for (Element e : round.getElementsAnnotatedWith(cronJobType)) {
            if (!(e instanceof ExecutableElement method)) {
                ctx.diagnostics().error(e, "@CronJob is only allowed on methods");
                continue;
            }
            TypeElement owner = (TypeElement) method.getEnclosingElement();
            // Mirror runtime CronJobRegistrar.java:159-162: @CronJob must go on the impl method,
            // not the contract interface. Reject up-front so the rest of the pipeline only sees
            // valid impl-method placements.
            if (owner.getKind() == ElementKind.INTERFACE) {
                ctx.diagnostics()
                        .error(
                                method,
                                "Place @CronJob on the implementation method, not the contract interface: %s.%s()",
                                owner.getSimpleName(),
                                method.getSimpleName());
                continue;
            }
            // Runtime CronJobRegistrar.java:168 scans implClass.getDeclaredMethods() — non-recursive.
            // A @CronJob declared on an abstract base class is silently dropped at runtime; reject
            // it here so the gap surfaces at build time.
            if (owner.getModifiers().contains(Modifier.ABSTRACT)) {
                ctx.diagnostics()
                        .error(
                                method,
                                "@CronJob must be declared on a concrete service implementation, not on the abstract"
                                        + " superclass %s; runtime scans only declared methods of the registered"
                                        + " service instance",
                                owner.getQualifiedName());
                continue;
            }
            AnnotationMirrors.findByFqn(method, CronAnnotations.CRON_JOB)
                    .ifPresent(mirror -> byOwner.computeIfAbsent(owner, k -> new ArrayList<>())
                            .add(new MethodMirror(method, mirror)));
        }

        byOwner.forEach((owner, methodMirrors) -> {
            for (MethodMirror mm : methodMirrors) {
                cron.validate(mm.method(), mm.mirror());
                coupling.validate(owner, mm.method());
                policy.validate(mm.method(), mm.mirror());
            }
            duplicates.validate(methodMirrors);
        });

        return false;
    }
}
