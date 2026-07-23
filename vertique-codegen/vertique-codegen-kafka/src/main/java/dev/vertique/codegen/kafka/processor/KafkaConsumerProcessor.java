// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.kafka.processor.emit.BindingMetaEmitter;
import dev.vertique.codegen.kafka.processor.scan.KafkaListenerScanner;
import dev.vertique.codegen.kafka.processor.scan.KafkaParamClassifier;
import dev.vertique.codegen.kafka.processor.scan.KafkaSourceModel;
import dev.vertique.codegen.kafka.processor.scan.KafkaSourceScanner;
import dev.vertique.codegen.kafka.processor.scan.ListenerModel;
import dev.vertique.codegen.kafka.processor.validate.KafkaConsumerValidator;
import dev.vertique.codegen.kafka.processor.validate.KafkaSourceValidator;
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
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Annotation processor that generates {@code {Consumer}_BindingMeta} companions with precomputed
 * Kafka consumer binding metadata, so {@code KafkaConsumerRegistrar} can skip boot-time reflective
 * scanning for generated consumers, and validates consumer shape at compile time.
 *
 * <p>Supported models:
 * <ul>
 *   <li><b>Model 1 {@code @KafkaSource}</b> — a service-implementation class with one or more
 *       {@link dev.vertique.kafka.KafkaSource @KafkaSource}-annotated methods; emits a
 *       {@link dev.vertique.kafka.KafkaBindingMeta.Kind#SOURCE SOURCE} meta per method with
 *       {@code null} valueType and targetService (resolved at runtime from the service registry).</li>
 *   <li><b>Model 3 router</b> — a {@link dev.vertique.kafka.KafkaListener @KafkaListener} interface
 *       with {@link dev.vertique.kafka.KafkaHandler @KafkaHandler} methods; emits a
 *       {@link dev.vertique.kafka.KafkaBindingMeta.Kind#ROUTER ROUTER} binding meta with one
 *       {@link dev.vertique.kafka.KafkaBindingMeta.RouteMeta RouteMeta} per handler method.</li>
 *   <li><b>Model 4 direct handler</b> — a {@code @KafkaListener} class implementing
 *       {@code KafkaRecordHandler<V>}; emits a
 *       {@link dev.vertique.kafka.KafkaBindingMeta.Kind#HANDLER HANDLER} meta with the resolved
 *       value type {@code V}.</li>
 * </ul>
 *
 * <p>Consumer shape is validated at compile time by {@link KafkaConsumerValidator}
 * (FR-CG006-005). Invalid listeners produce diagnostics and are not emitted.
 *
 * <p><b>Dual annotation (aggregated emission):</b> if a class carries both {@code @KafkaListener}
 * (Model 4 direct handler — interfaces cannot carry {@code @KafkaSource} methods) and
 * {@code @KafkaSource} methods, both listener and source metas are aggregated into a single
 * {@code {Type}_BindingMeta} companion. The {@code METAS} list contains the HANDLER entry and all
 * SOURCE entries. This ensures the runtime {@code scanKafkaSources} path finds the SOURCE metas
 * rather than having them silently dropped by a duplicate-write guard.
 *
 * <p>The runtime reflective {@code KafkaConsumerScanner} is preserved as a fallback; an app opts
 * in by adding this leaf to {@code annotationProcessorPaths}.
 *
 * <p>This class is registered via {@code META-INF/services/javax.annotation.processing.Processor}.
 */
@SupportedAnnotationTypes({KafkaConsumerProcessor.KAFKA_LISTENER_FQN, KafkaConsumerProcessor.KAFKA_SOURCE_FQN})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions(CodegenContext.OPTION_OUTPUT_PACKAGE)
public final class KafkaConsumerProcessor extends AbstractProcessor {

    static final String KAFKA_LISTENER_FQN = "dev.vertique.kafka.KafkaListener";
    static final String KAFKA_SOURCE_FQN = "dev.vertique.kafka.KafkaSource";

    private CodegenContext ctx;
    private KafkaListenerScanner scanner;
    private KafkaConsumerValidator validator;
    private KafkaSourceScanner sourceScanner;
    private KafkaSourceValidator sourceValidator;
    private BindingMetaEmitter emitter;

    /** Guard to ensure emission runs at most once (the first non-final round). */
    private boolean emitted;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        ctx = new CodegenContext(env);
        // A single shared classifier instance ensures the erasure cache warms once per round,
        // rather than being duplicated between the scanner and param validator.
        KafkaParamClassifier classifier = new KafkaParamClassifier(ctx);
        scanner = new KafkaListenerScanner(ctx, classifier);
        validator = new KafkaConsumerValidator(ctx, classifier);
        sourceScanner = new KafkaSourceScanner(ctx);
        sourceValidator = new KafkaSourceValidator(ctx);
        emitter = new BindingMetaEmitter(ctx);
        emitted = false;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || emitted) {
            return false;
        }

        // --- @KafkaListener: Model 3 (router) and Model 4 (direct handler) ---

        List<ListenerModel> models = new ArrayList<>();
        TypeElement listenerAnnotation = ctx.elements().getTypeElement(KAFKA_LISTENER_FQN);
        if (listenerAnnotation != null) {
            for (Element element : roundEnv.getElementsAnnotatedWith(listenerAnnotation)) {
                if (element instanceof TypeElement type && AnnotationMirrors.isPresent(type, KAFKA_LISTENER_FQN)) {
                    models.add(scanner.scan(type));
                }
            }
        }
        List<ListenerModel> validModels = validator.validate(models);

        // Build a map from origin TypeElement to valid ListenerModel for O(1) lookup when merging
        // with @KafkaSource models below.
        Map<TypeElement, ListenerModel> listenerByOrigin = new LinkedHashMap<>();
        for (ListenerModel model : validModels) {
            listenerByOrigin.put(model.originType(), model);
        }

        // --- @KafkaSource: Model 1 (service-impl binding) ---

        Map<TypeElement, KafkaSourceModel> rawSourceModels = collectKafkaSourceModels(roundEnv);
        List<KafkaSourceModel> rawList = new ArrayList<>(rawSourceModels.values());
        List<KafkaSourceModel> validSourceModels = sourceValidator.validate(rawList);

        // Build a map from impl TypeElement to valid KafkaSourceModel for the merge pass.
        Map<TypeElement, KafkaSourceModel> sourceByOrigin = new LinkedHashMap<>();
        for (KafkaSourceModel model : validSourceModels) {
            sourceByOrigin.put(model.implType(), model);
        }

        // --- Per-origin emission: aggregate listener + source metas into one companion ---
        //
        // Iterate over the union of all origin types so each type is emitted exactly once.
        // When a type has both a @KafkaListener model and a @KafkaSource model, they are
        // passed together to BindingMetaEmitter.emit(ListenerModel, KafkaSourceModel) which
        // combines them into a single METAS list (HANDLER/ROUTER entry + SOURCE entries).
        Set<TypeElement> allOrigins = new LinkedHashSet<>();
        allOrigins.addAll(listenerByOrigin.keySet());
        allOrigins.addAll(sourceByOrigin.keySet());

        for (TypeElement origin : allOrigins) {
            ListenerModel listenerModel = listenerByOrigin.get(origin);
            KafkaSourceModel sourceModel = sourceByOrigin.get(origin);
            emitter.emit(listenerModel, sourceModel);
        }

        emitted = true;
        return false;
    }

    // --- Internal helpers ---

    /**
     * Collects all elements annotated with {@code @KafkaSource}, groups the annotated methods by
     * their enclosing {@link TypeElement}, and returns one {@link KafkaSourceModel} per impl class.
     *
     * <p>{@code @KafkaSource} is a method-level annotation, so the round environment returns
     * {@link ExecutableElement} instances. We group by enclosing type to produce one model per
     * class and scan all annotated methods for that class together.
     *
     * @param roundEnv the current processing round environment
     * @return a map from impl-class {@link TypeElement} to its scanned {@link KafkaSourceModel},
     *         in encounter order; never {@code null}
     */
    private Map<TypeElement, KafkaSourceModel> collectKafkaSourceModels(RoundEnvironment roundEnv) {
        TypeElement sourceAnnotation = ctx.elements().getTypeElement(KAFKA_SOURCE_FQN);
        if (sourceAnnotation == null) {
            return Map.of();
        }

        // Collect the unique enclosing types that carry @KafkaSource methods
        Set<TypeElement> implTypes = new LinkedHashSet<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(sourceAnnotation)) {
            if (element instanceof ExecutableElement
                    && element.getEnclosingElement() instanceof TypeElement enclosing) {
                implTypes.add(enclosing);
            }
        }

        Map<TypeElement, KafkaSourceModel> result = new LinkedHashMap<>();
        for (TypeElement implType : implTypes) {
            KafkaSourceModel model = sourceScanner.scan(implType);
            if (!model.methods().isEmpty()) {
                result.put(implType, model);
            }
        }
        return result;
    }
}
