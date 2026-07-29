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
 * Extracts {@link ContractModel} from a {@link ImplKind#DIRECT} candidate.
 *
 * <p>For each public method on the contract interface (excluding {@code Object} methods),
 * builds an {@link OperationModel} where:
 * <ul>
 *   <li>{@link OperationModel#contractMethod()} and {@link OperationModel#handlerMethod()} are
 *       the same contract method.</li>
 *   <li>{@link OperationModel#params()} and {@link OperationModel#handlerParams()} are
 *       identical — the contract-side param classification.</li>
 *   <li>{@link OperationModel#returnType()} is the unwrapped {@code T} from
 *       {@code Future<T>}.</li>
 * </ul>
 *
 * <p>Validation errors encountered during extraction are emitted via
 * {@link CodegenContext#diagnostics()} and reflected in the returned {@code valid} flag. The
 * caller should not emit code for a contract where extraction returned {@code valid = false}.
 */
public final class DirectImplExtractor {

    private final CodegenContext ctx;
    private final AptOperationIdResolver opIdResolver;
    private final AptParamClassifier paramClassifier;

    /**
     * Constructs a {@code DirectImplExtractor} bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public DirectImplExtractor(CodegenContext ctx) {
        this.ctx = ctx;
        this.opIdResolver = new AptOperationIdResolver(ctx);
        this.paramClassifier = new AptParamClassifier(ctx);
    }

    /**
     * Result of an extraction attempt.
     *
     * <p>Every failure this extractor can report is rooted in the <em>contract</em> — it only ever
     * inspects contract methods (return-type unwrapping and contract-parameter classification), the
     * impl type being aliased to them. {@code valid} is therefore also the contract-shape signal the
     * {@code ServiceContractProcessor} uses to decide whether the contract-only client-proxy path
     * would re-report the same diagnostics. Contrast
     * {@link HandlerImplExtractor.ExtractionResult}, which mixes both roots.
     *
     * @param model the extracted model; {@code null} when {@code valid} is {@code false}
     * @param valid {@code false} if any error diagnostic was emitted during extraction
     */
    public record ExtractionResult(ContractModel model, boolean valid) {}

    /**
     * Extracts the {@link ContractModel} for the given direct-impl candidate.
     *
     * @param candidate the candidate to extract; must have {@code kind == DIRECT}
     * @return an {@link ExtractionResult} with {@code valid = false} if any error was encountered
     */
    public ExtractionResult extract(ImplCandidate candidate) {
        TypeElement contractType = candidate.contractType();
        TypeElement implType = candidate.implType();

        List<OperationModel> operations = new ArrayList<>();
        boolean valid = true;

        for (ExecutableElement contractMethod : MethodExtraction.publicNonObjectMethods(contractType, ctx)) {
            boolean hasError = false;

            // Resolve operation name and stable operation id
            String operationName = opIdResolver.resolveOperationName(contractMethod);
            String stableOpId = opIdResolver.resolveStableOperationId(contractMethod);

            // Unwrap return type
            boolean[] returnTypeErrorSink = {false};
            TypeMirror returnType = MethodExtraction.unwrapReturnType(contractMethod, ctx, returnTypeErrorSink);
            if (returnTypeErrorSink[0]) {
                hasError = true;
            }

            // Classify params
            boolean[] paramErrorSink = {false};
            List<ParamModel> params = paramClassifier.classifyContractParams(contractMethod, paramErrorSink);
            if (paramErrorSink[0]) {
                hasError = true;
            }

            // Check @OneWay
            boolean oneWay = AnnotationMirrors.isPresent(contractMethod, ServiceAnnotations.ONE_WAY);

            if (hasError) {
                valid = false;
                continue;
            }

            // Payload type = first PAYLOAD param
            TypeMirror payloadType = params.stream()
                    .filter(ParamModel::isPayload)
                    .map(ParamModel::type)
                    .findFirst()
                    .orElse(null);

            // For direct-impl: handlerMethod == contractMethod, handlerParams == params
            operations.add(new OperationModel(
                    contractMethod,
                    contractMethod,
                    operationName,
                    stableOpId,
                    returnType,
                    payloadType,
                    List.copyOf(params),
                    List.copyOf(params),
                    oneWay));
        }

        if (!valid) {
            return new ExtractionResult(null, false);
        }

        return new ExtractionResult(
                new ContractModel(contractType, implType, ImplKind.DIRECT, List.copyOf(operations)), true);
    }
}
