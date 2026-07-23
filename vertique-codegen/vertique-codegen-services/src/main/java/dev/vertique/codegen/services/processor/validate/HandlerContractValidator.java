// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.validate;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.services.processor.ServiceAnnotations;
import dev.vertique.codegen.services.processor.scan.ContractModel;
import dev.vertique.codegen.services.processor.scan.ImplCandidate.ImplKind;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;

/**
 * Validates structural constraints on handler-pattern implementations.
 *
 * <p>Mirrors {@code dev.vertique.services.ContractDiscovery#findContract}
 * ({@code ContractDiscovery.java:35-76}).
 *
 * <p>Rules enforced for {@link ImplKind#HANDLER} candidates:
 * <ul>
 *   <li>The {@code ServiceHandler<C>} type argument {@code C} must carry
 *       {@code @ServiceContract} (already checked by scanner; defensive re-check here).</li>
 *   <li>Reject double-pattern: impl must NOT also directly implement {@code C}.</li>
 *   <li>Reject handler that declares additional {@code @ServiceContract} interfaces beyond the
 *       resolved contract.</li>
 * </ul>
 *
 * <p>For {@link ImplKind#DIRECT} this validator is a no-op.
 * For {@link ImplKind#DOUBLE_PATTERN} this validator emits the rejection error.
 */
public final class HandlerContractValidator {

    private final CodegenContext ctx;

    /**
     * Constructs this validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public HandlerContractValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Validates handler-pattern structural constraints for the given model.
     *
     * @param model the contract model to validate; must not be {@code null}
     * @return {@code true} if all constraints pass; {@code false} if any error was emitted
     */
    public boolean validate(ContractModel model) {
        if (model.kind() == ImplKind.DIRECT) {
            return true;
        }

        TypeElement implType = model.implType();
        TypeElement contractType = model.contractType();

        // DOUBLE_PATTERN: reject outright
        if (model.kind() == ImplKind.DOUBLE_PATTERN) {
            ctx.diagnostics()
                    .error(
                            implType,
                            "%s implements both ServiceHandler<%s> and %s directly — use one pattern, not both",
                            implType.getSimpleName(),
                            contractType.getSimpleName(),
                            contractType.getSimpleName());
            return false;
        }

        // HANDLER: check for additional @ServiceContract interfaces beyond the resolved contract
        List<TypeElement> additionalContracts = new ArrayList<>();
        for (var supertype : ctx.typeResolver().allSupertypes(implType.asType()).toList()) {
            if (!(supertype instanceof DeclaredType dt)) continue;
            TypeElement el = (TypeElement) dt.asElement();
            if (el.getKind() != ElementKind.INTERFACE) continue;
            if (ctx.types().isSameType(el.asType(), contractType.asType())) continue;
            if (AnnotationMirrors.isPresent(el, ServiceAnnotations.SERVICE_CONTRACT)) {
                additionalContracts.add(el);
            }
        }

        if (!additionalContracts.isEmpty()) {
            String names = additionalContracts.stream()
                    .map(e -> e.getSimpleName().toString())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            ctx.diagnostics()
                    .error(
                            implType,
                            "%s implements ServiceHandler<%s> but also implements additional @ServiceContract"
                                    + " interface(s): %s",
                            implType.getSimpleName(),
                            contractType.getSimpleName(),
                            names);
            return false;
        }

        return true;
    }
}
