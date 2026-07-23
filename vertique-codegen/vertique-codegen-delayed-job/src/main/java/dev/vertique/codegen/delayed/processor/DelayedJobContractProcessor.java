// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.delayed.processor;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.delayed.processor.emit.DelayedJobProxyEmitter;
import dev.vertique.codegen.delayed.processor.scan.DelayedJobContractScanner;
import dev.vertique.codegen.delayed.processor.validate.DelayedJobValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/**
 * Annotation processor that generates a static {@code {Contract}_DelayedJobProxy} for each
 * {@code @DelayedJobContract} interface, replacing the per-call JDK dynamic proxy built by
 * {@code DelayedJobClientFactory}, and validates contract/executor shape at compile time.
 *
 * <p>The runtime reflective proxy ({@code DelayedJobClientProxy}) is preserved as a fallback; an app
 * opts in by adding this leaf to {@code annotationProcessorPaths}.
 *
 * <p>This class is registered via {@code META-INF/services/javax.annotation.processing.Processor}.
 */
@SupportedAnnotationTypes(DelayedJobContractProcessor.DELAYED_JOB_CONTRACT_FQN)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions({CodegenContext.OPTION_OUTPUT_PACKAGE, DelayedJobContractProcessor.OPTION_REQUIRE_EXECUTOR})
public final class DelayedJobContractProcessor extends AbstractProcessor {

    static final String DELAYED_JOB_CONTRACT_FQN = "dev.vertique.job.delayed.DelayedJobContract";

    /**
     * Processor option that promotes the "no {@code DelayedJobExecutor} in this compilation unit" warning
     * to a hard error. Off by default; intended as a single-module convenience.
     */
    public static final String OPTION_REQUIRE_EXECUTOR = "vertique.codegen.delayedjob.requireExecutor";

    private CodegenContext ctx;
    private DelayedJobContractScanner scanner;
    private DelayedJobValidator validator;
    private DelayedJobProxyEmitter emitter;
    private boolean emitted;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        ctx = new CodegenContext(env);
        scanner = new DelayedJobContractScanner(ctx);
        boolean requireExecutor = "true".equals(env.getOptions().get(OPTION_REQUIRE_EXECUTOR));
        validator = new DelayedJobValidator(ctx, requireExecutor);
        emitter = new DelayedJobProxyEmitter(ctx);
        emitted = false;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || emitted) {
            return false;
        }

        List<DelayedJobContractModel> models = new ArrayList<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(contractAnnotationElement())) {
            if (element instanceof TypeElement contract) {
                models.add(scanner.scan(contract));
            }
        }

        List<TypeElement> rootTypes = roundEnv.getRootElements().stream()
                .filter(TypeElement.class::isInstance)
                .map(TypeElement.class::cast)
                .toList();
        List<DelayedJobContractModel> validModels = validator.validate(models, rootTypes);
        for (DelayedJobContractModel model : validModels) {
            emitter.emit(model);
        }

        emitted = true;
        return false;
    }

    private TypeElement contractAnnotationElement() {
        return ctx.elements().getTypeElement(DELAYED_JOB_CONTRACT_FQN);
    }
}
