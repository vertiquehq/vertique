// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.sanitization.processor;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.sanitization.processor.emit.InputProcessorEmitter;
import dev.vertique.codegen.sanitization.processor.scan.AnnotationCollector;
import dev.vertique.codegen.sanitization.processor.scan.DtoModel;
import dev.vertique.codegen.sanitization.processor.scan.DtoScanner;
import dev.vertique.codegen.sanitization.processor.scan.RestBodyDiscovery;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

/**
 * Annotation processor (CG-008) that generates {@code {DTO}_InputProcessor} classes for REST
 * request body DTOs discovered via JAX-RS resource methods.
 *
 * <p>Discovery is anchored on HTTP-verb annotations ({@code @GET}, {@code @POST}, etc.) on
 * methods of {@code @Path}-annotated resource classes — not on sanitization annotations
 * directly. This broad trigger set ensures that DTOs carrying only {@code @SkipCanonicalization}
 * or {@code @SkipSanitization} (and compilation units where the resource and DTO share the same
 * CU but the DTO carries annotations indirectly via meta-annotations) are not missed.
 * Mirrors {@code AutoWireProcessor}'s {@code @SupportedAnnotationTypes("*")} approach.
 *
 * <p>Processing flow per round:
 * <ol>
 *   <li>{@link RestBodyDiscovery#findRoots} discovers direct {@code @BODY} parameter types
 *       from resource methods in the current round.</li>
 *   <li>{@link DtoScanner#scanTransitive} computes the transitive closure of participating
 *       types, applying the participation rule (direct roots unconditionally; nested types only
 *       when their subtree carries sanitization annotations) and the external-type cutoff.</li>
 *   <li>For each type not yet emitted, {@link InputProcessorEmitter#emit} writes the source
 *       file.</li>
 * </ol>
 *
 * <p>Always returns {@code false} so that other processors continue to see the same elements.
 *
 * <p>The {@code vertique.codegen.package} option is accepted for convention consistency (other
 * CG processors accept it) but is intentionally ignored by the emitter: generated processors
 * must live in the same package as the source DTO for package-private access compatibility.
 *
 * <p>This processor emits no Dagger module: the
 * {@link dev.vertique.rest.core.request.GeneratedInputProcessorDispatcher} self-populates via
 * classloader lookup, making CG-008 a transparent optimization that requires no extra wiring.
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions({CodegenContext.OPTION_OUTPUT_PACKAGE})
public final class SanitizationProcessor extends AbstractProcessor {

    private CodegenContext ctx;
    private RestBodyDiscovery bodyDiscovery;
    private DtoScanner dtoScanner;
    private InputProcessorEmitter emitter;

    /**
     * Track FQNs already emitted across rounds to avoid duplicate source file writes.
     */
    private final Set<String> alreadyEmitted = new HashSet<>();

    /**
     * Constructs a new {@code SanitizationProcessor}. Required by the
     * {@link java.util.ServiceLoader} mechanism used to load annotation processors.
     */
    public SanitizationProcessor() {}

    /**
     * {@inheritDoc}
     *
     * <p>Initialises the codegen context and all scanners and emitters.
     *
     * @param env the processing environment provided by the compiler
     */
    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        ctx = new CodegenContext(env);
        AnnotationCollector annotationCollector = new AnnotationCollector(ctx);
        bodyDiscovery = new RestBodyDiscovery(ctx);
        dtoScanner = new DtoScanner(ctx, annotationCollector);
        emitter = new InputProcessorEmitter(ctx);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Discovers body types, scans the transitive closure, and emits processors for any type
     * not yet emitted. The {@code processingOver} round is skipped — nothing to emit at that
     * stage.
     *
     * @param annotations the annotation types being processed in this round
     * @param roundEnv    the round environment
     * @return {@code false} always, so other processors continue to see the same elements
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }

        Set<TypeElement> roots = bodyDiscovery.findRoots(roundEnv);
        Set<DtoModel> emittable = dtoScanner.scanTransitive(roots);

        for (DtoModel model : emittable) {
            if (alreadyEmitted.add(model.qualifiedName())) {
                emitter.emit(model);
            }
        }

        return false;
    }
}
