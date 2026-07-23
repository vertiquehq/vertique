// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.validate;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.kafka.processor.scan.KafkaParamClassifier;
import dev.vertique.codegen.kafka.processor.scan.KafkaParamModel;
import dev.vertique.codegen.kafka.processor.scan.ListenerModel;
import dev.vertique.codegen.kafka.processor.scan.RouteModel;
import java.util.List;

/**
 * Validates the payload-parameter arity and ordering of each {@code @KafkaHandler} method within
 * a Model 3 router listener (FR-CG006-005).
 *
 * <p>A "payload" parameter is one that is not a recognized context type (i.e., not assignable to
 * {@code KafkaRecordContext} or {@code SecurityContext}, and whose type is not annotated with
 * {@code @DispatchContextValue}). Two rules are enforced (both establishing codegen-vs-reflective
 * parity, since the reflective {@code KafkaConsumerScanner.resolveRouteValueType} reads
 * {@code params[0]} unconditionally as the route value type):
 * <ul>
 *   <li><b>Arity:</b> more than one payload parameter is rejected with
 *       {@link Diagnostics#kafkaHandlerPayloadArity}.</li>
 *   <li><b>Position:</b> if a {@code @KafkaHandler} method has any parameters, the <em>first</em>
 *       must be the payload. A leading context parameter — whether followed by a payload
 *       ({@code (ctx, payload)}) or alone ({@code (ctx)} with no payload) — is rejected with
 *       {@link Diagnostics#kafkaHandlerPayloadNotFirst}, because the reflective path would use that
 *       context type as the value type while codegen would not.</li>
 * </ul>
 *
 * <p>A <strong>zero-parameter</strong> handler (e.g. a no-arg default handler {@code void onUnmatched();})
 * is allowed: both the codegen and reflective paths use {@code Void.class} as its value type. A
 * zero-payload handler that declares a context parameter is NOT allowed (see the position rule above).
 *
 * <p>Context-type classification is delegated to {@link KafkaParamClassifier}. This validator is a
 * no-op for Model 4 direct-handler listeners (they have no routes).
 */
public final class HandlerParamValidator {

    private final CodegenContext ctx;
    private final KafkaParamClassifier classifier;

    /**
     * Constructs the validator bound to the given codegen context and param classifier.
     *
     * @param ctx        the shared codegen context; must not be {@code null}
     * @param classifier the shared param classifier; must not be {@code null}
     */
    public HandlerParamValidator(CodegenContext ctx, KafkaParamClassifier classifier) {
        this.ctx = ctx;
        this.classifier = classifier;
    }

    /**
     * Validates the payload-parameter arity and position of every route in the listener model.
     *
     * <p>Two checks are applied per route:
     * <ol>
     *   <li>Arity: at most one payload parameter; zero or more than one are rejected.</li>
     *   <li>Position: when a payload parameter is present, it must be the first parameter.
     *       A context-type first parameter followed by a payload parameter is rejected.</li>
     * </ol>
     *
     * @param model the scanned listener model to validate; must not be {@code null}
     * @return {@code true} when all routes are valid; {@code false} when at least one diagnostic
     *         was emitted
     */
    public boolean validate(ListenerModel model) {
        if (model.kind() != ListenerModel.Kind.ROUTER) {
            return true; // Model 4 direct handlers have no @KafkaHandler routes to validate.
        }

        String listenerName = model.originType().getSimpleName().toString();
        boolean allOk = true;

        for (RouteModel route : model.routes()) {
            List<KafkaParamModel> params = classifier.classifyHandlerParams(route.method());
            long payloadCount =
                    params.stream().filter(KafkaParamModel::isPayload).count();
            String methodName = route.method().getSimpleName().toString();

            if (payloadCount > 1) {
                ctx.diagnostics()
                        .error(route.method(), Diagnostics.kafkaHandlerPayloadArity(listenerName, methodName, (int)
                                payloadCount));
                allOk = false;
            } else if (!params.isEmpty() && !params.get(0).isPayload()) {
                // When a @KafkaHandler method has parameters, the first MUST be the payload. This covers both
                // a context type leading a payload (e.g. (ctx, payload)) and a context-only handler (e.g.
                // (ctx) with no payload): the reflective KafkaConsumerScanner.resolveRouteValueType uses
                // params[0] unconditionally, while codegen skips context params — so a non-payload params[0]
                // diverges (codegen would emit Void.class or the payload type, reflective uses the context
                // type). Rejecting it keeps params[0] == the payload, preserving generated/reflective parity.
                // A zero-parameter handler (e.g. a no-arg default handler) is allowed — both paths use Void.
                ctx.diagnostics()
                        .error(route.method(), Diagnostics.kafkaHandlerPayloadNotFirst(listenerName, methodName));
                allOk = false;
            }
        }
        return allOk;
    }
}
