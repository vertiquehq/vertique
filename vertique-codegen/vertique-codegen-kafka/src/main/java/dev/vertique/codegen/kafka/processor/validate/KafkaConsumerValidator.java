// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.kafka.processor.scan.KafkaParamClassifier;
import dev.vertique.codegen.kafka.processor.scan.ListenerModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates compile-time validation of scanned {@link ListenerModel} instances
 * (FR-CG006-005), combining the results of all individual validators.
 *
 * <p>All validators run for every model regardless of whether an earlier one has already found a
 * problem (using {@code &} not {@code &&}). This ensures that all diagnostics surface in a single
 * compilation, rather than forcing the developer through multiple rounds of error-fix cycles.
 *
 * <p>Validators are applied in the following order:
 * <ol>
 *   <li>{@link DirectHandlerValidator} — rejects listeners that are neither a router nor a direct
 *       handler (Slice 6)</li>
 *   <li>{@link ListenerTopicValidator} — rejects blank {@code topic()}</li>
 *   <li>{@link HandlerMatchValidator} — rejects invalid match-rule combinations per route</li>
 *   <li>{@link HandlerParamValidator} — rejects incorrect payload-parameter arity per route</li>
 *   <li>{@link HandlerReturnTypeValidator} — rejects non-void / non-{@code Future<Void>} return
 *       types per route</li>
 * </ol>
 *
 * <p>Note: routers are <em>not</em> required to have distinct payload types per route. Route
 * selection is by {@code matchHeader}/{@code matchProperty}/{@code defaultHandler} (see
 * {@code KafkaRecordDispatcher.resolveRoute}), never by payload type; the payload type is used only
 * to deserialize after a route is chosen. Two routes that share a payload schema but match on
 * different header/property values are a valid, supported shape, so no duplicate-payload check is
 * applied.
 *
 * <p>A model is returned in the valid list only when <em>all</em> validators pass. Callers
 * (i.e., {@link dev.vertique.codegen.kafka.processor.KafkaConsumerProcessor}) should emit a
 * {@code _BindingMeta} only for models in the returned list.
 */
public final class KafkaConsumerValidator {

    private final DirectHandlerValidator directHandlerValidator;
    private final ListenerTopicValidator topicValidator;
    private final HandlerMatchValidator matchValidator;
    private final HandlerParamValidator paramValidator;
    private final HandlerReturnTypeValidator returnTypeValidator;

    /**
     * Constructs the validator orchestrator, initializing all individual validators with a shared
     * {@link KafkaParamClassifier} instance so its erasure cache warms only once per processing round.
     *
     * @param ctx        the shared codegen context; must not be {@code null}
     * @param classifier the shared param classifier; must not be {@code null}
     */
    public KafkaConsumerValidator(CodegenContext ctx, KafkaParamClassifier classifier) {
        this.directHandlerValidator = new DirectHandlerValidator(ctx);
        this.topicValidator = new ListenerTopicValidator(ctx);
        this.matchValidator = new HandlerMatchValidator(ctx);
        this.paramValidator = new HandlerParamValidator(ctx, classifier);
        this.returnTypeValidator = new HandlerReturnTypeValidator(ctx);
    }

    /**
     * Validates every model in the list and returns those that passed all checks.
     *
     * <p>All validators run for each model regardless of intermediate failures, so that all
     * diagnostics are emitted in one compilation round. A model is included in the returned list
     * only when it passes every validator.
     *
     * @param models the scanned listener models for this round; must not be {@code null}
     * @return the subset of {@code models} that passed all validation checks
     */
    public List<ListenerModel> validate(List<ListenerModel> models) {
        List<ListenerModel> valid = new ArrayList<>();
        for (ListenerModel model : models) {
            boolean ok = directHandlerValidator.validate(model)
                    & topicValidator.validate(model)
                    & matchValidator.validate(model)
                    & paramValidator.validate(model)
                    & returnTypeValidator.validate(model);
            if (ok) {
                valid.add(model);
            }
        }
        return valid;
    }
}
