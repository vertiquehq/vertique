// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.workflow.processor.emit.WorkflowClientsModuleEmitter;
import dev.vertique.codegen.workflow.processor.emit.WorkflowProxyEmitter;
import dev.vertique.codegen.workflow.processor.scan.ContractModel;
import dev.vertique.codegen.workflow.processor.scan.ContractScanner;
import dev.vertique.codegen.workflow.processor.validate.ContractShapeValidator;
import dev.vertique.codegen.workflow.processor.validate.ParamAnnotationValidator;
import dev.vertique.codegen.workflow.processor.validate.ReturnTypeValidator;
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
 * Annotation processor that generates a static {@code {Contract}_WorkflowClientProxy} for each
 * {@code @WorkflowContract} interface (replacing the per-call JDK dynamic proxy built by
 * {@code WorkflowClientFactory}) plus a {@code GeneratedWorkflowClientsModule} Dagger module, and
 * validates contract shape at compile time.
 *
 * <p>The runtime {@code WorkflowClientFactory} / {@code WorkflowProxyValidator} are preserved as the
 * fallback and authoritative validation; an app opts in by adding this leaf to
 * {@code annotationProcessorPaths} and including the generated module in its {@code @Component}.
 *
 * <p>This class is registered via {@code META-INF/services/javax.annotation.processing.Processor}.
 */
@SupportedAnnotationTypes(WorkflowContractProcessor.WORKFLOW_CONTRACT_FQN)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions(CodegenContext.OPTION_OUTPUT_PACKAGE)
public final class WorkflowContractProcessor extends AbstractProcessor {

    static final String WORKFLOW_CONTRACT_FQN = "dev.vertique.workflow.contract.WorkflowContract";

    private CodegenContext ctx;
    private ContractScanner scanner;
    private ContractShapeValidator shapeValidator;
    private ReturnTypeValidator returnTypeValidator;
    private ParamAnnotationValidator paramAnnotationValidator;
    private WorkflowProxyEmitter emitter;
    private WorkflowClientsModuleEmitter moduleEmitter;
    private boolean emitted;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        ctx = new CodegenContext(env);
        scanner = new ContractScanner(ctx);
        shapeValidator = new ContractShapeValidator(ctx);
        returnTypeValidator = new ReturnTypeValidator(ctx);
        paramAnnotationValidator = new ParamAnnotationValidator(ctx);
        emitter = new WorkflowProxyEmitter(ctx);
        moduleEmitter = new WorkflowClientsModuleEmitter(ctx);
        emitted = false;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || emitted) {
            return false;
        }

        TypeElement contractAnnotation = ctx.elements().getTypeElement(WORKFLOW_CONTRACT_FQN);
        List<ContractModel> validContracts = new ArrayList<>();
        if (contractAnnotation != null) {
            for (Element element : roundEnv.getElementsAnnotatedWith(contractAnnotation)) {
                if (element instanceof TypeElement type) {
                    ContractModel m = scanner.scan(type);
                    boolean ok = shapeValidator.validate(m)
                            & returnTypeValidator.validate(m)
                            & paramAnnotationValidator.validate(m);
                    if (ok) {
                        emitter.emit(m);
                        validContracts.add(m);
                    }
                }
            }
        }
        moduleEmitter.emit(validContracts);

        emitted = true;
        return false;
    }
}
