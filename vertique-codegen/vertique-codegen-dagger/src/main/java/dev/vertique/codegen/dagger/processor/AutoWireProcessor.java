// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.PackageResolver;
import dev.vertique.codegen.dagger.processor.collect.DelayedJobExecutorScanner;
import dev.vertique.codegen.dagger.processor.collect.KafkaConsumerCollector;
import dev.vertique.codegen.dagger.processor.collect.RegistrationCollector;
import dev.vertique.codegen.dagger.processor.collect.RestClientCollector;
import dev.vertique.codegen.dagger.processor.emit.MultibindingModuleEmitter;
import dev.vertique.codegen.dagger.processor.emit.RegistrationModuleEmitter;
import dev.vertique.codegen.dagger.processor.emit.RestClientModuleEmitter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * Annotation processor that auto-generates Dagger {@code @Module} bindings from framework marker
 * annotations and interface implementations.
 *
 * <p>Supports three qualifier patterns:
 * <ul>
 *   <li>{@code @RestClient} &#8594; {@code GeneratedRestClientsModule} with
 *       {@code @Provides @Singleton InterfaceType provideXxx(RestClientFactory)} methods</li>
 *   <li>{@code @KafkaListener} / {@code @KafkaSource} &#8594; {@code GeneratedKafkaConsumersModule}
 *       with {@code @Provides @IntoSet @KafkaConsumers Object} methods</li>
 *   <li>{@code DelayedJobExecutor} impls &#8594; {@code GeneratedDelayedJobsModule} with
 *       {@code @Provides @IntoSet @DelayedJobs Object} methods</li>
 * </ul>
 *
 * <p>It also consumes the source-retained {@code @RegisterAs} and {@code @RegisterIntoSet}
 * annotations and emits an explicit {@code GeneratedRegistrationsModule} containing abstract
 * {@code @Binds} declarations.
 *
 * <p>JAX-RS resource binding ({@code @Path} &#8594; {@code GeneratedJaxRsResourcesModule}) is
 * owned by {@code JaxRsPipelineProcessor} in {@code vertique-codegen-jaxrs} (CG-010).
 * CG-002 does not own {@code @JaxRsResources} wiring.
 *
 * <p>{@code @ServiceContract} implementations are wired by {@code vertique-codegen-services}
 * (CG-005), which generates contributor classes plus a {@code GeneratedServicesModule}. CG-002 does
 * not emit {@code @Services} bindings.
 *
 * <p>Discovery uses two modes:
 * <ul>
 *   <li><b>Annotation-rooted</b> — {@link RestClientCollector} and {@link KafkaConsumerCollector}
 *       each query {@link RoundEnvironment#getElementsAnnotatedWith} for their respective
 *       marker.</li>
 *   <li><b>Root-element scan</b> — {@link DelayedJobExecutorScanner} walks
 *       {@link RoundEnvironment#getRootElements()} to find concrete implementations.</li>
 * </ul>
 *
 * <p>{@code @SupportedAnnotationTypes("*")} is required because the {@code DelayedJobExecutor}
 * scan walks root elements rather than querying annotated elements; the processor must run every
 * round even when none of its target annotations are present. Side effect: javac's "no processors
 * found for these annotations" warning is suppressed for every annotation in the round. Future
 * {@code vertique-codegen-<feature>} leaves that need only annotation-rooted discovery should
 * declare specific marker FQNs in {@code @SupportedAnnotationTypes} instead of inheriting the
 * wildcard.
 *
 * <p>The processor always returns {@code false} from {@code process()} so that Dagger and other
 * processors continue to see the same elements.
 *
 * <p><b>Multi-round emission:</b> bindings are accumulated in the round where the marker is first
 * observed, and emission happens in that round. The {@code emitted} flag prevents re-collection
 * and re-emission on later rounds (e.g., the round triggered when Dagger writes its own generated
 * sources). Consequence: types that are first introduced by another processor's generated source
 * in a later round are NOT auto-wired. No current consumer hits this case in practice, but if a
 * future codegen leaf chains its output through this processor, switch to processingOver-only
 * emission with cross-round accumulation.
 *
 * <p>Auto-wiring can be globally disabled by passing {@code -Avertique.codegen.autoWire=false}.
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions({"vertique.codegen.package", "vertique.codegen.autoWire"})
public final class AutoWireProcessor extends AbstractProcessor {

    private static final String OPTION_AUTO_WIRE = "vertique.codegen.autoWire";

    private CodegenContext ctx;
    private EnumMap<Qualifier, List<Binding>> accumulator;
    private List<Registration> registrations;
    private boolean disabled;
    private boolean emitted;

    // --- Collectors and scanners (initialized in init) ---
    private RestClientCollector restClientCollector;
    private KafkaConsumerCollector kafkaCollector;
    private RegistrationCollector registrationCollector;
    private DelayedJobExecutorScanner delayedJobScanner;

    // --- Emitters ---
    private MultibindingModuleEmitter multibindingEmitter;
    private RestClientModuleEmitter restClientEmitter;
    private RegistrationModuleEmitter registrationEmitter;

    // --- PackageResolver ---
    private PackageResolver packageResolver;

    /**
     * Constructs a new {@code AutoWireProcessor}. Required by the {@link java.util.ServiceLoader}
     * mechanism used to load annotation processors.
     */
    public AutoWireProcessor() {}

    /**
     * {@inheritDoc}
     *
     * <p>Initializes the shared {@link CodegenContext}, all collectors/scanners, emitters, the
     * binding accumulator, and the {@link PackageResolver}. Also reads the
     * {@code vertique.codegen.autoWire} processor option to determine whether generation is
     * globally disabled.
     */
    @Override
    public synchronized void init(javax.annotation.processing.ProcessingEnvironment env) {
        super.init(env);
        ctx = new CodegenContext(env);

        // Check the global-disable option
        String autoWireOption = env.getOptions().get(OPTION_AUTO_WIRE);
        disabled = "false".equalsIgnoreCase(autoWireOption);

        if (disabled) {
            return;
        }

        emitted = false;

        // Initialize accumulator for all qualifiers
        accumulator = new EnumMap<>(Qualifier.class);
        for (Qualifier q : Qualifier.values()) {
            accumulator.put(q, new ArrayList<>());
        }
        registrations = new ArrayList<>();

        // Collectors
        restClientCollector = new RestClientCollector(ctx);
        kafkaCollector = new KafkaConsumerCollector(ctx);
        registrationCollector = new RegistrationCollector(ctx);

        // Scanners
        delayedJobScanner = new DelayedJobExecutorScanner(ctx);

        // Emitters
        multibindingEmitter = new MultibindingModuleEmitter(ctx);
        restClientEmitter = new RestClientModuleEmitter(ctx);
        registrationEmitter = new RegistrationModuleEmitter(ctx);

        // Package resolver
        packageResolver = new PackageResolver(env);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Collection and emission strategy:
     * <ul>
     *   <li>Round 1 (user sources): collect all bindings, then emit immediately. The generated
     *       modules become available to subsequent processors (e.g., Dagger's
     *       {@code ComponentProcessingStep}) in round 2 via the filer's generated-source
     *       directory.</li>
     *   <li>Round 2+ (generated sources only): the {@code emitted} guard prevents duplicate
     *       writes. Collection is skipped on rounds after the first non-{@code processingOver}
     *       round to avoid double-counting.</li>
     * </ul>
     *
     * <p>Always returns {@code false} so Dagger and other processors continue to see the same
     * elements.
     *
     * @param annotations the set of annotation types present in the round (ignored — this
     *                    processor uses {@code @SupportedAnnotationTypes("*")} and inspects all
     *                    elements directly)
     * @param roundEnv    the round environment for the current processing round
     * @return {@code false} always
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (disabled) {
            return false;
        }

        // Skip collection and emission on the final synthetic round (processingOver) and on any
        // subsequent rounds after we have already emitted
        if (roundEnv.processingOver() || emitted) {
            return false;
        }

        // --- Phase 1: Annotation-rooted collectors ---
        restClientCollector.collect(roundEnv, accumulator.get(Qualifier.REST_CLIENTS));
        kafkaCollector.collect(roundEnv, accumulator.get(Qualifier.KAFKA_CONSUMERS));
        registrationCollector.collect(roundEnv, registrations);

        // --- Phase 2: Root-element scan for delayed-job executors ---
        List<TypeElement> concreteRootElements = collectConcreteRootElements(roundEnv);
        delayedJobScanner.scan(concreteRootElements, accumulator.get(Qualifier.DELAYED_JOBS));

        // --- Emit immediately so Dagger sees the generated modules in the next round ---
        emitAll();
        emitted = true;

        return false;
    }

    // --- Internal helpers ---

    /**
     * Extracts all concrete (non-abstract, non-interface) {@link TypeElement}s from the round's
     * root elements.
     *
     * @param roundEnv the current round environment
     * @return a list of concrete root-element type elements
     */
    private List<TypeElement> collectConcreteRootElements(RoundEnvironment roundEnv) {
        List<TypeElement> result = new ArrayList<>();
        for (Element el : roundEnv.getRootElements()) {
            if (!(el instanceof TypeElement te)) {
                continue;
            }
            // Skip interfaces and abstract classes — only concrete impls are wired
            if (te.getKind() == ElementKind.INTERFACE) {
                continue;
            }
            if (te.getModifiers().contains(Modifier.ABSTRACT)) {
                continue;
            }
            result.add(te);
        }
        return result;
    }

    /**
     * Emits a generated module for each qualifier that has at least one binding.
     *
     * <p>Multibinding qualifiers ({@link Qualifier#KAFKA_CONSUMERS}, {@link Qualifier#DELAYED_JOBS})
     * are handled by {@link MultibindingModuleEmitter}; {@link Qualifier#REST_CLIENTS} is handled
     * by {@link RestClientModuleEmitter}.
     */
    private void emitAll() {
        if (!registrations.isEmpty()) {
            String packageName = packageResolver.resolve(
                    registrations.stream().map(Registration::origin).toList(), ctx);
            if (packageName != null) {
                registrationEmitter.emit(registrations, packageName);
            }
        }

        for (Qualifier qualifier : Qualifier.values()) {
            List<Binding> bindings = accumulator.get(qualifier);
            if (bindings.isEmpty()) {
                continue;
            }

            String packageName = packageResolver.resolve(
                    bindings.stream().map(Binding::origin).toList(), ctx);
            if (packageName == null) {
                // Package resolution emitted an error — skip emission for this qualifier
                continue;
            }

            if (qualifier == Qualifier.REST_CLIENTS) {
                restClientEmitter.emit(bindings, packageName);
            } else {
                multibindingEmitter.emit(qualifier, bindings, packageName);
            }
        }
    }
}
