// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.services.processor.emit.ClientProxyEmitter;
import dev.vertique.codegen.services.processor.emit.ContributorEmitter;
import dev.vertique.codegen.services.processor.emit.ContributorModuleEmitter;
import dev.vertique.codegen.services.processor.scan.ClientContractExtractor;
import dev.vertique.codegen.services.processor.scan.ClientContractModel;
import dev.vertique.codegen.services.processor.scan.ContractModel;
import dev.vertique.codegen.services.processor.scan.DirectImplExtractor;
import dev.vertique.codegen.services.processor.scan.HandlerImplExtractor;
import dev.vertique.codegen.services.processor.scan.ImplCandidate;
import dev.vertique.codegen.services.processor.scan.ImplCandidateScanner;
import dev.vertique.codegen.services.processor.validate.ConditionalRequiredOnNonDefaultValidator;
import dev.vertique.codegen.services.processor.validate.ContractOverloadValidator;
import dev.vertique.codegen.services.processor.validate.HandlerContractValidator;
import dev.vertique.codegen.services.processor.validate.HandlerMatchValidator;
import dev.vertique.codegen.services.processor.validate.HandlerOverloadValidator;
import dev.vertique.codegen.services.processor.validate.MultipleUnconditionalImplValidator;
import dev.vertique.codegen.services.processor.validate.OperationCollisionValidator;
import dev.vertique.codegen.services.processor.validate.OperationValueValidator;
import dev.vertique.codegen.services.processor.validate.PayloadParamValidator;
import dev.vertique.codegen.services.processor.validate.ReturnTypeValidator;
import dev.vertique.codegen.validate.InjectConstructorValidator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

/**
 * Annotation processor (CG-005, extended in CG-011 W4) that generates
 * {@code ServiceContractContributor} implementations and a Dagger {@code @Module} for
 * {@code @ServiceContract}-annotated interfaces.
 *
 * <p>For each contract group (all concrete implementations of the same {@code @ServiceContract}
 * interface found in the current compilation unit), this processor:
 * <ol>
 *   <li>Scans root elements for candidate implementations via {@link ImplCandidateScanner}.</li>
 *   <li>Classifies each as {@code DIRECT}, {@code HANDLER}, or {@code DOUBLE_PATTERN}.</li>
 *   <li>Validates each candidate structurally (return types, overloads, handler match,
 *       {@code @Inject} constructor, operation name collisions).</li>
 *   <li>Groups valid candidates by contract type.</li>
 *   <li>Validates each group (at most one unconditional candidate per multi-impl group;
 *       non-default candidates must carry {@code @ConditionalOnProperty}).</li>
 *   <li>Emits exactly one {@code {Contract}_ContractContributor} per valid contract group
 *       via {@link ContributorEmitter}.</li>
 *   <li>Emits one {@code GeneratedServicesModule} per round via
 *       {@link ContributorModuleEmitter}.</li>
 * </ol>
 *
 * <p>The processor runs whenever {@code vertique-codegen-services} is on the
 * {@code <annotationProcessorPaths>} of the consuming module. There is no opt-in flag;
 * the presence of the processor on the path is the opt-in. Per-impl opt-out is via
 * {@code @NoAutoWire} (handled by {@link ImplCandidateScanner}).
 *
 * <p>Processor options:
 * <ul>
 *   <li>{@code vertique.codegen.package} — overrides the output package for
 *       {@code GeneratedServicesModule}; individual contributors always go in
 *       the contract's own package.</li>
 * </ul>
 *
 * <p>Always returns {@code false} so other processors continue to see the same elements.
 *
 * <p>Uses {@code @SupportedAnnotationTypes("*")} because discovery is root-element-rooted:
 * {@code @ServiceContract} lives on the <em>interface</em> and the processor must find the
 * <em>concrete impl</em>. Annotation-rooted discovery would miss impls compiled when the
 * interface is a pre-compiled classpath type.
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions({CodegenContext.OPTION_OUTPUT_PACKAGE})
public final class ServiceContractProcessor extends AbstractProcessor {

    private CodegenContext ctx;
    private ImplCandidateScanner scanner;
    private DirectImplExtractor directExtractor;
    private HandlerImplExtractor handlerExtractor;
    private ClientContractExtractor clientContractExtractor;

    // --- Per-model validators ---
    private ReturnTypeValidator returnTypeValidator;
    private PayloadParamValidator payloadParamValidator;
    private ContractOverloadValidator contractOverloadValidator;
    private HandlerOverloadValidator handlerOverloadValidator;
    private OperationValueValidator operationValueValidator;
    private OperationCollisionValidator operationCollisionValidator;
    private InjectConstructorValidator injectConstructorValidator;
    private HandlerContractValidator handlerContractValidator;
    private HandlerMatchValidator handlerMatchValidator;

    // --- Group-level validators (CG-011 W4) ---
    private MultipleUnconditionalImplValidator multipleUnconditionalImplValidator;
    private ConditionalRequiredOnNonDefaultValidator conditionalRequiredOnNonDefaultValidator;

    // --- Emitters ---
    private ContributorEmitter contributorEmitter;
    private ContributorModuleEmitter moduleEmitter;
    private ClientProxyEmitter clientProxyEmitter;

    // --- State ---
    private boolean emitted;

    /**
     * Constructs a new {@code ServiceContractProcessor}. Required by the
     * {@link java.util.ServiceLoader} mechanism used to load annotation processors.
     */
    public ServiceContractProcessor() {}

    /**
     * {@inheritDoc}
     *
     * <p>Initialises all scanners, validators, and emitters.
     *
     * @param env the processing environment provided by the compiler
     */
    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        ctx = new CodegenContext(env);

        scanner = new ImplCandidateScanner(ctx);
        directExtractor = new DirectImplExtractor(ctx);
        handlerExtractor = new HandlerImplExtractor(ctx);
        clientContractExtractor = new ClientContractExtractor(ctx);

        returnTypeValidator = new ReturnTypeValidator(ctx);
        payloadParamValidator = new PayloadParamValidator(ctx);
        contractOverloadValidator = new ContractOverloadValidator(ctx);
        handlerOverloadValidator = new HandlerOverloadValidator(ctx);
        operationValueValidator = new OperationValueValidator(ctx);
        operationCollisionValidator = new OperationCollisionValidator(ctx);
        injectConstructorValidator = new InjectConstructorValidator(ctx);
        handlerContractValidator = new HandlerContractValidator(ctx);
        handlerMatchValidator = new HandlerMatchValidator(ctx);

        multipleUnconditionalImplValidator = new MultipleUnconditionalImplValidator(ctx);
        conditionalRequiredOnNonDefaultValidator = new ConditionalRequiredOnNonDefaultValidator(ctx);

        contributorEmitter = new ContributorEmitter(ctx);
        moduleEmitter = new ContributorModuleEmitter(ctx);
        clientProxyEmitter = new ClientProxyEmitter(ctx);

        emitted = false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Scans, validates, and emits in the first non-{@code processingOver} round.
     * The {@code emitted} guard prevents re-emission on subsequent rounds.
     *
     * @param annotations the annotation types being processed in this round
     * @param roundEnv    the round environment
     * @return {@code false} always
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || emitted) {
            return false;
        }

        List<ImplCandidate> candidates = scanner.scan(roundEnv);
        List<ContractModel> validModels = new ArrayList<>();

        for (ImplCandidate candidate : candidates) {
            // Reject double-pattern before extraction
            if (candidate.kind() == ImplCandidate.ImplKind.DOUBLE_PATTERN) {
                ctx.diagnostics()
                        .error(
                                candidate.implType(),
                                "%s implements both ServiceHandler<%s> and %s directly — use one pattern, not both",
                                candidate.implType().getSimpleName(),
                                candidate.contractType().getSimpleName(),
                                candidate.contractType().getSimpleName());
                continue;
            }

            // Extract model; errors are emitted by the extractor on failure
            ContractModel model;
            if (candidate.kind() == ImplCandidate.ImplKind.DIRECT) {
                var result = directExtractor.extract(candidate);
                if (!result.valid()) {
                    continue;
                }
                model = result.model();
            } else {
                var result = handlerExtractor.extract(candidate);
                if (!result.valid()) {
                    continue;
                }
                model = result.model();
            }

            // Use & (not &&) so every validator runs and contributes its diagnostics in one compile.
            // The five contract-shape validators take explicit inputs so the contract-only client
            // path can reuse them without a ContractModel (which always carries an impl type).
            boolean valid = handlerContractValidator.validate(model)
                    & contractOverloadValidator.validate(model.contractType())
                    & handlerOverloadValidator.validate(model)
                    & returnTypeValidator.validate(model.contractType(), model.operations())
                    & payloadParamValidator.validate(model.contractType(), model.operations())
                    & operationValueValidator.validate(model.contractType(), model.operations())
                    & operationCollisionValidator.validate(model.contractType(), model.operations())
                    & injectConstructorValidator.validate(model.implType())
                    & handlerMatchValidator.validate(model);

            if (valid) {
                validModels.add(model);
            }
        }

        // Group valid models by contract type (keyed on FQN for stable cross-round identity).
        // Insertion-ordered so the generated source order is deterministic.
        Map<String, List<ContractModel>> groups = new LinkedHashMap<>();
        for (ContractModel model : validModels) {
            String contractFqn = model.contractType().getQualifiedName().toString();
            groups.computeIfAbsent(contractFqn, k -> new ArrayList<>()).add(model);
        }

        // Run group-level validators; exclude groups that fail from emission.
        List<List<ContractModel>> emittableGroups = new ArrayList<>();
        for (List<ContractModel> group : groups.values()) {
            // Use & (not &&) so both validators run and each emits its own diagnostics.
            boolean groupValid = multipleUnconditionalImplValidator.validate(group)
                    & conditionalRequiredOnNonDefaultValidator.validate(group);
            if (groupValid) {
                emittableGroups.add(group);
            }
        }

        // Emit one contributor per contract group; add one representative to the module emitter.
        for (List<ContractModel> group : emittableGroups) {
            contributorEmitter.emit(group);
            moduleEmitter.add(group.get(0));
        }

        if (moduleEmitter.hasModels()) {
            moduleEmitter.emit();
        }

        // Contracts whose impl-loop processing failed (extraction, per-model validators, group
        // validators, or DOUBLE_PATTERN rejection) already have their diagnostics reported above;
        // the impl-rooted candidate FQNs that never made it into an emittable group are exactly
        // those contracts.
        Set<String> candidateContractFqns = candidates.stream()
                .map(c -> c.contractType().getQualifiedName().toString())
                .collect(Collectors.toSet());
        Set<String> emittedContractFqns = emittableGroups.stream()
                .map(group -> group.get(0).contractType().getQualifiedName().toString())
                .collect(Collectors.toSet());
        Set<String> failedContractFqns = new HashSet<>(candidateContractFqns);
        failedContractFqns.removeAll(emittedContractFqns);

        emitClientProxies(roundEnv, failedContractFqns);

        emitted = true;
        return false;
    }

    // --- Contract-only client proxy emission (CG-015 Track D) ---

    /**
     * Emits one {@code {Contract}_ServiceClientProxy} per {@code @ServiceContract} interface in this
     * round.
     *
     * <p>Discovery is annotation-rooted rather than impl-rooted: a client proxy is emitted for every
     * source contract interface, whether or not an implementation is compiled alongside it, so a
     * contract that <em>does</em> have an impl produces both a contributor and a client proxy.
     * Contracts are processed in fully-qualified-name order so the generated output is deterministic.
     *
     * <p>Only the five contract-shape validators gate emission — the impl-coupled ones
     * ({@code Handler*}, {@code @Inject} constructor, group-level) have no meaning without an impl.
     *
     * <p>Contracts whose impl-loop processing failed in this round are skipped here (see
     * {@code failedContractFqns}) so their contract-shape errors are not reported twice.
     *
     * @param roundEnv           the current round environment; must not be {@code null}
     * @param failedContractFqns FQNs of contracts whose impl-loop processing failed in this round
     *                           (an {@link ImplCandidate} existed but its model never reached an
     *                           emittable group); must not be {@code null}
     */
    private void emitClientProxies(RoundEnvironment roundEnv, Set<String> failedContractFqns) {
        TypeElement serviceContractAnnotation = ctx.elements().getTypeElement(ServiceAnnotations.SERVICE_CONTRACT);
        if (serviceContractAnnotation == null) {
            // @ServiceContract is not on the processor classpath — nothing can be annotated with it.
            return;
        }

        List<TypeElement> contracts = roundEnv.getElementsAnnotatedWith(serviceContractAnnotation).stream()
                .filter(e -> e.getKind() == ElementKind.INTERFACE)
                .map(TypeElement.class::cast)
                // Diagnostics already reported by the impl loop; the compilation is failing anyway,
                // so no proxy is needed.
                .filter(t -> !failedContractFqns.contains(t.getQualifiedName().toString()))
                .sorted(Comparator.comparing(t -> t.getQualifiedName().toString()))
                .toList();

        for (TypeElement contractType : contracts) {
            Optional<ClientContractModel> extracted = clientContractExtractor.extract(contractType);
            if (extracted.isEmpty()) {
                // Non-generatable (NOTE emitted) or invalid (errors emitted) — nothing to emit.
                continue;
            }
            ClientContractModel model = extracted.get();

            // Use & (not &&) so every validator runs and contributes its diagnostics in one compile.
            boolean valid = contractOverloadValidator.validate(model.contractType())
                    & returnTypeValidator.validate(model.contractType(), model.operations())
                    & payloadParamValidator.validate(model.contractType(), model.operations())
                    & operationValueValidator.validate(model.contractType(), model.operations())
                    & operationCollisionValidator.validate(model.contractType(), model.operations());

            if (valid) {
                clientProxyEmitter.emit(model);
            }
        }
    }
}
