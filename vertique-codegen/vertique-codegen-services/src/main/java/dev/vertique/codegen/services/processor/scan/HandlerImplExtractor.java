// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.scan;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.services.processor.ServiceAnnotations;
import dev.vertique.codegen.services.processor.scan.ImplCandidate.ImplKind;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Extracts {@link ContractModel} from a {@link ImplKind#HANDLER} candidate.
 *
 * <p>For each public method on the contract interface (excluding {@code Object} methods),
 * finds the matching handler method by name and builds an {@link OperationModel} where:
 * <ul>
 *   <li>{@link OperationModel#contractMethod()} is the contract interface method.</li>
 *   <li>{@link OperationModel#handlerMethod()} is the matching method on the handler class.</li>
 *   <li>{@link OperationModel#params()} contains only contract-side (payload) params.</li>
 *   <li>{@link OperationModel#handlerParams()} contains all handler params including extra
 *       {@code DISPATCH_CONTEXT} ones ({@code SecurityContext} or {@code @DispatchContextValue}).</li>
 * </ul>
 *
 * <p>Errors during extraction (missing handler method, ambiguous overload, return type mismatch)
 * are emitted as diagnostics and reflected in the returned {@code valid} flag.
 */
public final class HandlerImplExtractor {

    private final CodegenContext ctx;
    private final AptOperationIdResolver opIdResolver;
    private final AptParamClassifier paramClassifier;

    /**
     * Constructs a {@code HandlerImplExtractor} bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public HandlerImplExtractor(CodegenContext ctx) {
        this.ctx = ctx;
        this.opIdResolver = new AptOperationIdResolver(ctx);
        this.paramClassifier = new AptParamClassifier(ctx);
    }

    /**
     * Result of an extraction attempt.
     *
     * @param model the extracted model; {@code null} when {@code valid} is {@code false}
     * @param valid {@code false} if any error diagnostic was emitted during extraction
     */
    public record ExtractionResult(ContractModel model, boolean valid) {}

    /**
     * Extracts the {@link ContractModel} for the given handler-pattern candidate.
     *
     * @param candidate the candidate to extract; must have {@code kind == HANDLER}
     * @return an {@link ExtractionResult} with {@code valid = false} if any error was encountered
     */
    public ExtractionResult extract(ImplCandidate candidate) {
        TypeElement contractType = candidate.contractType();
        TypeElement implType = candidate.implType();

        List<OperationModel> operations = new ArrayList<>();
        boolean valid = true;

        List<ExecutableElement> contractMethods = MethodExtraction.publicNonObjectMethods(contractType, ctx);
        List<ExecutableElement> handlerMethods = MethodExtraction.publicNonObjectMethods(implType, ctx);

        for (ExecutableElement contractMethod : contractMethods) {
            boolean hasError = false;

            // Find handler method by name — uniqueness validated by HandlerOverloadValidator
            List<ExecutableElement> candidates = handlerMethods.stream()
                    .filter(m -> m.getSimpleName()
                            .toString()
                            .equals(contractMethod.getSimpleName().toString()))
                    .toList();

            if (candidates.isEmpty()) {
                ctx.diagnostics()
                        .error(
                                implType,
                                "%s does not provide handler method for contract method %s.%s()",
                                implType.getSimpleName(),
                                contractType.getSimpleName(),
                                contractMethod.getSimpleName());
                valid = false;
                continue;
            }

            if (candidates.size() > 1) {
                // HandlerOverloadValidator will also catch this; report here too for early feedback
                ctx.diagnostics()
                        .error(
                                implType,
                                "%s has %d overloaded methods named '%s' — exactly one handler method per"
                                        + " operation is required",
                                implType.getSimpleName(),
                                candidates.size(),
                                contractMethod.getSimpleName());
                valid = false;
                continue;
            }

            ExecutableElement handlerMethod = candidates.get(0);

            // Resolve operation name and stable id from contract method
            String operationName = opIdResolver.resolveOperationName(contractMethod);
            String stableOpId = opIdResolver.resolveStableOperationId(contractMethod);

            // Unwrap return type from contract method
            boolean[] returnTypeErrorSink = {false};
            TypeMirror returnType = MethodExtraction.unwrapReturnType(contractMethod, ctx, returnTypeErrorSink);
            if (returnTypeErrorSink[0]) {
                hasError = true;
            }

            // Classify contract params (payload only)
            boolean[] contractParamErrorSink = {false};
            List<ParamModel> contractParams =
                    paramClassifier.classifyContractParams(contractMethod, contractParamErrorSink);
            if (contractParamErrorSink[0]) {
                hasError = true;
            }

            // Classify handler params (payload + context)
            boolean[] handlerParamErrorSink = {false};
            List<ParamModel> handlerParams =
                    paramClassifier.classifyHandlerParams(handlerMethod, handlerParamErrorSink);
            if (handlerParamErrorSink[0]) {
                hasError = true;
            }

            // Check @OneWay on contract method
            boolean oneWay = AnnotationMirrors.isPresent(contractMethod, ServiceAnnotations.ONE_WAY);

            if (hasError) {
                valid = false;
                continue;
            }

            // Payload type = first PAYLOAD param from contract
            TypeMirror payloadType = contractParams.stream()
                    .filter(ParamModel::isPayload)
                    .map(ParamModel::type)
                    .findFirst()
                    .orElse(null);

            operations.add(new OperationModel(
                    contractMethod,
                    handlerMethod,
                    operationName,
                    stableOpId,
                    returnType,
                    payloadType,
                    List.copyOf(contractParams),
                    List.copyOf(handlerParams),
                    oneWay));
        }

        if (!valid) {
            return new ExtractionResult(null, false);
        }

        return new ExtractionResult(
                new ContractModel(contractType, implType, ImplKind.HANDLER, List.copyOf(operations)), true);
    }
}
