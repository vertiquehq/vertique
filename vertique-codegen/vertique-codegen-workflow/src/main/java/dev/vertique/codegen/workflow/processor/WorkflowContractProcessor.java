// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.workflow.processor.emit.WorkflowProxyEmitter;
import dev.vertique.codegen.workflow.processor.emit.WorkflowTypeCatalogEmitter;
import dev.vertique.codegen.workflow.processor.scan.ContractModel;
import dev.vertique.codegen.workflow.processor.scan.ContractScanner;
import dev.vertique.codegen.workflow.processor.scan.TypeCatalogScanner;
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
 * {@code WorkflowClientFactory}) and validates contract shape at compile time. It generates no
 * Dagger providers of typed clients: applications obtain clients from
 * {@code WorkflowClientFactory}.
 *
 * <p>It also generates a {@code GeneratedWorkflowTypeCatalogModule} Dagger module for every
 * compilation that declares {@code @WorkflowContract} interfaces or {@code @WorkflowType} classes,
 * including a compilation that declares only {@code @WorkflowType} classes. The module contributes a
 * {@code WorkflowTypeCatalog} that lists them, which is the set of classes a document loaded at
 * runtime can name.
 *
 * <p>The runtime {@code WorkflowClientFactory} / {@code WorkflowProxyValidator} are preserved as the
 * fallback and authoritative validation; an app opts in by adding this leaf to
 * {@code annotationProcessorPaths} and including the generated type-catalog module in its
 * {@code @Component}.
 *
 * <p>This class is registered via {@code META-INF/services/javax.annotation.processing.Processor}.
 */
@SupportedAnnotationTypes({WorkflowContractProcessor.WORKFLOW_CONTRACT_FQN, WorkflowContractProcessor.WORKFLOW_TYPE_FQN
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions(CodegenContext.OPTION_OUTPUT_PACKAGE)
public final class WorkflowContractProcessor extends AbstractProcessor {

    static final String WORKFLOW_CONTRACT_FQN = "dev.vertique.workflow.contract.WorkflowContract";
    static final String WORKFLOW_TYPE_FQN = "dev.vertique.workflow.types.WorkflowType";

    private CodegenContext ctx;
    private ContractScanner scanner;
    private ContractShapeValidator shapeValidator;
    private ReturnTypeValidator returnTypeValidator;
    private ParamAnnotationValidator paramAnnotationValidator;
    private WorkflowProxyEmitter emitter;
    private TypeCatalogScanner catalogScanner;
    private WorkflowTypeCatalogEmitter catalogEmitter;
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
        catalogScanner = new TypeCatalogScanner(ctx);
        catalogEmitter = new WorkflowTypeCatalogEmitter(ctx);
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

        List<TypeCatalogScanner.Entry> catalogEntries = new ArrayList<>();
        for (ContractModel m : validContracts) {
            catalogEntries.add(catalogScanner.contractEntry(m.contractType()));
        }
        TypeElement typeAnnotation = ctx.elements().getTypeElement(WORKFLOW_TYPE_FQN);
        if (typeAnnotation != null) {
            for (Element element : roundEnv.getElementsAnnotatedWith(typeAnnotation)) {
                if (element instanceof TypeElement type) {
                    catalogScanner.typeEntry(type).ifPresent(catalogEntries::add);
                }
            }
        }
        catalogEmitter.emit(catalogEntries);

        emitted = true;
        return false;
    }
}
