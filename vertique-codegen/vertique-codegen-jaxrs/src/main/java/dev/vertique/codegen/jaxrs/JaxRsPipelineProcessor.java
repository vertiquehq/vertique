// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import com.palantir.javapoet.ClassName;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.PackageResolver;
import dev.vertique.codegen.jaxrs.processor.emit.BeanParamModelEmitter;
import dev.vertique.codegen.jaxrs.processor.emit.ExecutionPlanEmitter;
import dev.vertique.codegen.jaxrs.processor.emit.GeneratedJaxRsResourcesModuleEmitter;
import dev.vertique.codegen.jaxrs.processor.emit.JaxRsDescriptorEmitter;
import dev.vertique.codegen.jaxrs.processor.validate.BodyFormValidator;
import dev.vertique.codegen.jaxrs.processor.validate.ContextParamValidator;
import dev.vertique.codegen.jaxrs.processor.validate.HttpVerbValidator;
import dev.vertique.codegen.jaxrs.processor.validate.PathParamAlignmentValidator;
import dev.vertique.codegen.jaxrs.processor.validate.SecurityAnnotationValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/**
 * Unified JAX-RS compile-time pipeline processor (CG-010).
 *
 * <p>Replaces the CG-009 {@code JaxRsResourceProcessor} and absorbs the {@code @Path}-resource
 * binding work previously owned by {@code vertique-codegen-dagger}'s {@code PathResourceCollector}.
 * Registered via {@code META-INF/services/javax.annotation.processing.Processor}; consumers add
 * {@code vertique-codegen-jaxrs} to {@code annotationProcessorPaths} (already required by
 * CG-009).
 *
 * <p>The processor runs in four logical steps per build:
 * <ol>
 *   <li><strong>Discover</strong> — {@link JaxRsCandidateScanner} walks
 *       {@link RoundEnvironment#getRootElements()} and collects concrete classes with an
 *       effective {@code @Path} (direct or via a transitively implemented interface).
 *       {@link JaxRsApplicationScanner} separately walks the same root elements for concrete
 *       {@code jakarta.ws.rs.core.Application} subtypes, top-level or static nested at any
 *       depth.</li>
 *   <li><strong>Resolve</strong> — {@link EffectiveJaxRsContractResolver} builds the
 *       {@link EffectiveResourceContract} for each candidate using the precedence rule:
 *       direct annotations → superclass chain → BFS interfaces.</li>
 *   <li><strong>Validate</strong> — five validators run against each resource contract, plus
 *       {@link JaxRsApplicationScanner#validate} checks every eligible application's construction,
 *       accessibility, and required {@code @ApplicationPath} (via {@link ApplicationPathGrammar})
 *       regardless of the auto-wire setting.</li>
 *   <li><strong>Emit</strong> — four emitters fire per build:
 *       {@link dev.vertique.codegen.jaxrs.processor.emit.GeneratedJaxRsResourcesModuleEmitter} writes the
 *       Dagger DI module — the presence-gated resource bindings, the lazy resource catalog, and the
 *       validated application registrations — only when auto-wiring is enabled;
 *       {@link dev.vertique.codegen.jaxrs.processor.emit.JaxRsDescriptorEmitter} writes a per-resource
 *       {@code _JaxRsDescriptor};
 *       {@link dev.vertique.codegen.jaxrs.processor.emit.BeanParamModelEmitter} writes a per-bean
 *       {@code _BeanParamModel} for every {@code @BeanParam}/{@code @RequestParams} type;
 *       {@link dev.vertique.codegen.jaxrs.processor.emit.ExecutionPlanEmitter} writes a per-method
 *       {@code _<methodName>_<idx>_ExecutionPlan} for each verb-bearing method that passes the
 *       eligibility gate.</li>
 * </ol>
 *
 * <p><strong>Semantic vs. DI candidates:</strong> "Is this a JAX-RS resource?" (semantic) is
 * independent of "Should we generate a Dagger binding for it?" (DI eligibility). Validation and
 * descriptor/bean-param/execution-plan emission apply to all semantic candidates; Dagger module
 * generation applies only to DI-emission candidates (those with an {@code @Inject} constructor that
 * are not annotated {@link dev.vertique.codegen.NoAutoWire}).
 *
 * <p><strong>Module-writing condition and package resolution.</strong> The generated module is
 * written when the unit has at least one DI-eligible resource or at least one eligible,
 * successfully validated application. Its package is resolved from the resources when the unit has
 * any, and from the applications only in an applications-only unit, so adding an
 * {@code Application} never moves an existing module.
 *
 * <p><strong>Single-shot emission:</strong> an {@code emitted} flag prevents re-emission on later
 * Dagger-triggered rounds. This mirrors {@code AutoWireProcessor}'s multi-round guard.
 *
 * <p><strong>Auto-wire kill switch:</strong> passing {@code -Avertique.codegen.autoWire=false}
 * suppresses DI module emission; validation still runs, including every eligible application's
 * construction, accessibility, and required {@code @ApplicationPath}. One warning per eligible,
 * non-{@code @NoAutoWire} application then names it and states that the default
 * {@code @JaxRsResources} mount is used instead. Because no module is written in this mode, an
 * unresolvable package (origins spanning disjoint packages with no common prefix) is never a
 * compile error here, unlike when auto-wiring is enabled; validation still runs against every
 * eligible application, treating the candidate, its enclosing types, and its constructor as
 * reachable only if {@code public} when no package could be determined.
 *
 * <p>{@code @SupportedAnnotationTypes("*")} is required for the dep-JAR-interface case where
 * {@code @Path} lives on an interface in a dependency JAR and never appears in the current round's
 * annotation set. This matches the existing {@code AutoWireProcessor} wildcard pattern.
 *
 * <p>Always returns {@code false} from {@link #process} so Dagger and other processors continue
 * to see the same elements.
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions({"vertique.codegen.package", "vertique.codegen.autoWire"})
public final class JaxRsPipelineProcessor extends AbstractProcessor {

    // --- Option keys ---

    private static final String OPTION_AUTO_WIRE = "vertique.codegen.autoWire";

    // --- State ---

    private CodegenContext ctx;
    private EffectiveJaxRsContractResolver resolver;
    private SecurityAnnotationValidator security;
    private ContextParamValidator contextParam;
    private HttpVerbValidator verbs;
    private PathParamAlignmentValidator path;
    private BodyFormValidator bodyForm;
    private GeneratedJaxRsResourcesModuleEmitter moduleEmitter;
    private JaxRsDescriptorEmitter descriptorEmitter;
    private BeanParamModelEmitter beanParamEmitter;
    private ExecutionPlanEmitter executionPlanEmitter;
    private PackageResolver packageResolver;
    private Elements elements;

    /** {@code true} when {@code -Avertique.codegen.autoWire=false} is set. */
    private boolean autoWireDisabled;

    /** {@code true} after the first non-{@code processingOver} round has emitted. */
    private boolean emitted;

    // --- Constructor ---

    /**
     * Constructs a new {@code JaxRsPipelineProcessor}. Required by the
     * {@link java.util.ServiceLoader} mechanism used to load annotation processors.
     */
    public JaxRsPipelineProcessor() {}

    // --- AbstractProcessor lifecycle ---

    /**
     * {@inheritDoc}
     *
     * <p>Initialises the shared {@link CodegenContext}, the
     * {@link EffectiveJaxRsContractResolver}, all five validators (including
     * {@link dev.vertique.codegen.jaxrs.processor.validate.ContextParamValidator}), the
     * {@link GeneratedJaxRsResourcesModuleEmitter}, the {@link PackageResolver} it shares with
     * {@link JaxRsApplicationScanner}'s validation, the {@link JaxRsDescriptorEmitter},
     * the {@link BeanParamModelEmitter}, the {@link ExecutionPlanEmitter}, and reads the
     * {@code vertique.codegen.autoWire} processor option.
     *
     * @param env the processing environment provided by the compiler
     */
    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        ctx = new CodegenContext(env);
        elements = env.getElementUtils();

        String autoWireOption = env.getOptions().get(OPTION_AUTO_WIRE);
        autoWireDisabled = "false".equalsIgnoreCase(autoWireOption);

        resolver = new EffectiveJaxRsContractResolver(ctx);
        security = new SecurityAnnotationValidator(ctx);
        contextParam = new ContextParamValidator(ctx);
        verbs = new HttpVerbValidator(ctx);
        path = new PathParamAlignmentValidator(ctx);
        bodyForm = new BodyFormValidator(ctx);
        moduleEmitter = new GeneratedJaxRsResourcesModuleEmitter(ctx);
        packageResolver = new PackageResolver(env);
        descriptorEmitter = new JaxRsDescriptorEmitter(ctx);
        beanParamEmitter = new BeanParamModelEmitter(ctx);
        executionPlanEmitter = new ExecutionPlanEmitter(ctx);

        emitted = false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs the four pipeline steps — discover, resolve, validate, emit — in a single shot.
     * Subsequent rounds (triggered by Dagger's generated sources) are no-ops due to the
     * {@code emitted} guard.
     *
     * <p>Steps 4b (descriptor emission), 4c (bean-param model emission), and 4d (execution-plan
     * emission) always run for validated contracts, regardless of the auto-wire setting — these are
     * runtime optimization artifacts, not DI bindings. Step 4a (DI module emission) is skipped
     * when {@code -Avertique.codegen.autoWire=false} is set, but steps 1–3 and 4b–4d still execute
     * so validation errors are always reported. Step 4d runs before 4b so that the descriptor can
     * reference the emitted plan class names in its {@code describe()} body.
     *
     * <p>Within step 4a, {@link JaxRsApplicationScanner#scan} and
     * {@link JaxRsApplicationScanner#validate} always run, so every eligible application's
     * diagnostics (the registration note, the {@code @NoAutoWire} warning, the
     * {@code autoWire=false} warning, and the construction, accessibility, and required-annotation
     * errors) are reported regardless of the auto-wire setting; only the module write itself is
     * skipped when {@code -Avertique.codegen.autoWire=false} is set.
     *
     * @param annotations the annotation types present in the round (not used; this processor
     *                    uses {@code @SupportedAnnotationTypes("*")} and scans root elements)
     * @param roundEnv    the current round environment
     * @return {@code false} always, so other processors continue to run
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || emitted) {
            return false;
        }

        // --- Step 1: Discover semantic candidates ---
        Set<TypeElement> semanticCandidates = JaxRsCandidateScanner.scan(roundEnv, resolver);

        // --- Step 2 + 3: Resolve and validate each semantic candidate ---
        // Collect all bean-param types across validated contracts for deduplication.
        Set<TypeElement> beanParamTypes = new LinkedHashSet<>();
        // Collect validated contracts for step 4b descriptor emission.
        List<EffectiveResourceContract> validatedContracts = new java.util.ArrayList<>();

        // Per-round dedup set for <Ann>$JaxRsLiteral classes materialized by both ExecutionPlanEmitter's
        // and JaxRsDescriptorEmitter's literal-backed parameter annotations (GitHub issue #162).
        // Scoped to a single process() round and shared across every emit(...) call to EITHER
        // emitter in that round, since the same parameter (hence the same annotation occurrence) is
        // visited by both emitters — without a shared set, a runtime-retained annotation type (e.g.
        // @PathParam itself, which is @Retention(RUNTIME)) used on more than one parameter/method
        // visited by either emitter would attempt to write the same <Ann>$JaxRsLiteral class twice in one
        // round, which the Filer rejects as a duplicate-write error.
        Set<String> emittedLiteralFqns = new LinkedHashSet<>();

        for (TypeElement candidate : semanticCandidates) {
            EffectiveResourceContract contract = resolver.resolve(candidate);

            // Class-level conflict already reported by the resolver; skip if signalled as
            // unresolvable (no methods AND no classPath means the resolver hit a conflict).
            if (contract.hasNoMethods() && contract.classPath() == null) {
                continue;
            }

            List<EffectiveMethodContract> methods = contract.methods();

            // Pre-scan: compute verb lists once per method to avoid double work.
            // Also determines whether any verb-bearing method exists — class-level security
            // validation is gated on this, mirroring runtime ResourceScanner.scanResource.
            Map<EffectiveMethodContract, List<String>> verbsByMethod = new LinkedHashMap<>();
            boolean hasAnyVerbMethod = false;
            for (EffectiveMethodContract method : methods) {
                List<String> presentVerbs = verbs.presentVerbs(method);
                verbsByMethod.put(method, presentVerbs);
                if (!presentVerbs.isEmpty()) {
                    hasAnyVerbMethod = true;
                }
            }

            if (hasAnyVerbMethod) {
                security.validateClassLevel(contract);
            }

            boolean contractValid = true;
            for (Map.Entry<EffectiveMethodContract, List<String>> entry : verbsByMethod.entrySet()) {
                EffectiveMethodContract method = entry.getKey();
                List<String> presentVerbs = entry.getValue();
                verbs.validate(method);
                if (presentVerbs.size() == 1) {
                    // Context validation runs first so a param with both @Context and @PathParam
                    // reports only the context-conflict, not a spurious path-alignment error.
                    // Security validation runs independently; both security and context errors are
                    // reported when both are present. Path and body/form validators are suppressed
                    // when either security or context validation rejects the method.
                    boolean contextValid = contextParam.validate(method);
                    if (security.validateMethodLevel(contract, method) && contextValid) {
                        path.validate(contract, method);
                        bodyForm.validate(method);
                    }
                }
            }

            // Collect bean-param types from all params of all methods in this contract
            for (EffectiveMethodContract method : methods) {
                for (EffectiveParamContract pc : method.params()) {
                    if (pc.source() == JaxRsParamSource.BEAN_PARAM && pc.beanParamType() != null) {
                        TypeMirror bpt = pc.beanParamType();
                        ctx.asTypeElement(bpt).ifPresent(beanParamTypes::add);
                    }
                }
            }

            validatedContracts.add(contract);
        }

        // --- Steps 4b + 4d: Descriptor and execution-plan emission ---
        // Execution-plan emission (step 4d) runs BEFORE descriptor emission (step 4b) so that
        // the descriptor can reference the emitted plan class names in its describe() body.
        for (EffectiveResourceContract contract : validatedContracts) {
            // Collect verb-bearing methods for plan emission
            List<EffectiveMethodContract> verbMethods = contract.methods().stream()
                    .filter(m -> m.httpMethod() != null)
                    .toList();

            // Emit one plan per verb-bearing method; collect ClassName (or null) per method.
            // Null entries mean the method is not eligible for plan emission (non-public method,
            // inaccessible types) — the descriptor will pass null for those methods' executionPlan.
            //
            // emittedLiteralFqns (GitHub issue #162) is shared across every emit(...) call to EITHER
            // emitter in this round — see its declaration above.
            List<ClassName> planClassNames = new ArrayList<>(verbMethods.size());
            for (int i = 0; i < verbMethods.size(); i++) {
                ClassName planClass =
                        executionPlanEmitter.emit(contract.concreteClass(), verbMethods.get(i), i, emittedLiteralFqns);
                planClassNames.add(planClass);
            }

            // Emit descriptor, passing plan class names for the full ResourceMethodMeta constructor
            descriptorEmitter.emit(contract, planClassNames, emittedLiteralFqns);
        }

        // --- Step 4c: Bean-param model emission ---
        for (TypeElement beanType : beanParamTypes) {
            beanParamEmitter.emit(beanType);
        }

        // --- Step 4a: DI module emission ---
        Set<TypeElement> diCandidates = JaxRsCandidateScanner.filterDiCandidates(semanticCandidates, elements);
        List<TypeElement> eligibleApplications = JaxRsApplicationScanner.scan(roundEnv, ctx);
        emitModule(diCandidates, eligibleApplications);

        emitted = true;
        return false;
    }

    /**
     * Resolves the generated module's package and writes it when the unit has at least one
     * DI-eligible resource or at least one eligible application to register.
     *
     * <p>Validating every eligible application's construction, accessibility, and required
     * {@code @ApplicationPath} happens here too, regardless of {@code autoWireDisabled} — only the
     * module WRITE itself is skipped when {@code -Avertique.codegen.autoWire=false} is set,
     * mirroring the pre-existing resource-binding behaviour.
     *
     * <p>When auto-wiring is enabled, the package is resolved through {@link PackageResolver}
     * exactly as before: disjoint origin packages with no common prefix and no override are a
     * compile error, because a module must be written. When auto-wiring is disabled, no module is
     * written, so the package is resolved without that failure mode instead (see
     * {@link #resolvePackageWithoutFailing(List)}); validation still runs against whatever package
     * (possibly none) that resolves to.
     */
    private void emitModule(Set<TypeElement> diCandidates, List<TypeElement> eligibleApplications) {
        boolean needsModulePackage = !eligibleApplications.isEmpty() || (!autoWireDisabled && !diCandidates.isEmpty());
        if (!needsModulePackage) {
            return;
        }

        List<TypeElement> packageOrigins = diCandidates.isEmpty() ? eligibleApplications : List.copyOf(diCandidates);
        String modulePackage;
        if (autoWireDisabled) {
            modulePackage = resolvePackageWithoutFailing(packageOrigins);
        } else {
            modulePackage = packageResolver.resolve(packageOrigins, ctx);
            if (modulePackage == null) {
                return;
            }
        }

        List<JaxRsApplicationScanner.Registration> registrations = eligibleApplications.isEmpty()
                ? List.of()
                : JaxRsApplicationScanner.validate(eligibleApplications, modulePackage, autoWireDisabled, ctx);
        if (!autoWireDisabled && (!diCandidates.isEmpty() || !registrations.isEmpty())) {
            moduleEmitter.emit(modulePackage, diCandidates, registrations);
        }
    }

    /**
     * Resolves the generated module's package under {@code -Avertique.codegen.autoWire=false}
     * without ever emitting the disjoint-packages compile error that {@link PackageResolver#resolve}
     * reports, because no module is written in that mode: the {@code -Avertique.codegen.package}
     * override when set; otherwise the origins' longest common package prefix, computed locally,
     * when they share one; otherwise {@code null}.
     *
     * @param origins the DI-eligible resources, or (in an applications-only unit) the eligible
     *                applications; must not be {@code null}
     * @return the resolved package, or {@code null} when none could be determined
     */
    private String resolvePackageWithoutFailing(List<TypeElement> origins) {
        if (origins.isEmpty()) {
            return null;
        }
        String override = processingEnv.getOptions().get(CodegenContext.OPTION_OUTPUT_PACKAGE);
        if (override != null && !override.isBlank()) {
            return override;
        }
        String commonPrefix = null;
        for (TypeElement origin : origins) {
            String originPackage = ctx.packageNameOf(origin);
            commonPrefix = commonPrefix == null
                    ? originPackage
                    : PackageResolver.longestCommonPrefix(commonPrefix, originPackage);
        }
        return (commonPrefix == null || commonPrefix.isEmpty()) ? null : commonPrefix;
    }
}
