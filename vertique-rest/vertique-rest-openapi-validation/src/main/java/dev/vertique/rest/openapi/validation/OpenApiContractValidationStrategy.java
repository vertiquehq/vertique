// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.request.DefaultBoundRequest;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import dev.vertique.rest.jaxrs.validation.SchemaErrorKeywords;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import io.vertx.json.schema.OutputUnit;
import io.vertx.openapi.contract.OpenAPIContract;
import io.vertx.openapi.contract.Operation;
import io.vertx.openapi.validation.RequestUtils;
import io.vertx.openapi.validation.RequestValidator;
import io.vertx.openapi.validation.SchemaValidationException;
import io.vertx.openapi.validation.ValidatableRequest;
import io.vertx.openapi.validation.ValidatorException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;

/**
 * The opt-in {@code openapi-contract} {@link RequestValidationStrategy}: validates each request against
 * the application's OpenAPI contract using the standalone vertx-openapi {@link RequestValidator}, rather
 * than the annotation-synthesized schemas the {@code web-validation} strategy uses (FR-020). Selecting
 * this strategy (via {@code jaxrs.validationStrategy = "openapi-contract"}) requires
 * {@code vertique-rest-openapi-validation} on the classpath — the only module that depends on the
 * preview {@code vertx-openapi} artifact (FR-025).
 *
 * <p><strong>Contract loads are asynchronous and cached per {@code openapiPath}.</strong>
 * {@link OpenAPIContract#from(Vertx, String)} returns a {@link Future}; this strategy is a singleton and
 * {@link #gateFor} is called synchronously at router-build time, so each distinct contract path is loaded
 * — together with the {@link RequestValidator} derived from it — exactly once, and the resulting
 * {@code Future} is cached in {@link #contracts}. A contract that is slow or absent therefore does not
 * block router construction, and a load failure surfaces when the strategy is actually used rather than
 * hard-failing the whole application at construction (this strategy is opt-in). A failed load stays
 * cached as a failed future and is never retried, so a broken contract fails that path's requests
 * deterministically instead of re-loading per request.
 *
 * <p><strong>Body-read-once.</strong> The gate runs after {@code BodyHandler} (which has already
 * buffered the request body into {@code ctx.body()}) and before dispatch (which reads {@code ctx.body()}
 * again). It therefore must not re-read the request stream. The gate builds a {@link ValidatableRequest}
 * from the already-buffered request via
 * {@link RequestUtils#extract(io.vertx.core.http.HttpServerRequest, Operation, java.util.function.Supplier)}
 * — the body-{@link java.util.function.Supplier} overload — feeding it the buffered body from
 * {@code ctx.body().buffer()} wrapped in a succeeded {@link Future}, so the body is read from the
 * buffer rather than from the request stream. It then calls
 * {@link RequestValidator#validate(ValidatableRequest, String)} — <em>not</em> the
 * {@link RequestValidator#validate(io.vertx.core.http.HttpServerRequest, String)} overload, which would
 * re-read the stream.
 *
 * <p><strong>Strictness.</strong> Because validation is delegated to the standalone
 * {@link RequestValidator} against the contract, the strategy inherits the validator's strictness —
 * content-type and schema enforcement match the contract, reproducing the behavior of the
 * preview-router pipeline this strategy replaces.
 *
 * <p><strong>Error mapping — two distinct failure classes.</strong> The gate distinguishes client
 * errors from server/config errors:
 * <ul>
 *   <li>A {@link ValidatorException} (or its {@link SchemaValidationException} subclass) is a genuine
 *       request-validation failure — the client sent a non-conforming request. The gate maps it to a
 *       {@link RestValidationException} so the REST error pipeline renders a 400
 *       {@code application/problem+json} response.</li>
 *   <li>Any <em>other</em> throwable — most notably the {@link IllegalStateException} produced when
 *       the requested operationId is absent from the loaded contract — is a <strong>server/config
 *       error</strong>: the JAX-RS resource and the {@code openapi.json} disagree (a deployment
 *       misconfiguration). These failures are passed through as-is via {@code ctx.fail(err)}, which
 *       the framework's failure handler maps to a 500 response. They are also logged at {@code ERROR}
 *       level so the operator can diagnose the mismatch.</li>
 * </ul>
 * A conforming request flows on via {@code ctx.next()}.
 *
 * <p><strong>Per-mount contracts.</strong> This strategy is a {@link Singleton}, but it resolves the
 * contract <em>per mount</em>: {@link #bindToMount(MountMeta)} caches one loaded contract plus validator
 * per distinct {@link MountMeta#openapiPath()}, and
 * {@link #gateFor(JaxRsOperationDescriptor, OperationSchemas, MountMeta)} — the form the framework calls
 * — builds each operation's gate against the contract of the mount that registers it. Mounts declaring
 * divergent {@code openapiPath}s are therefore supported and each validates against its own contract; a
 * mount declaring <em>no</em> {@code openapiPath} is rejected at bind time with a
 * {@link RestConfigurationException}, because this strategy cannot validate without a contract. The
 * global {@link JaxRsConfig#openapiPath()} is pre-warmed at construction (when non-null) so the usual
 * single-mount deployment keeps loading its contract as early as it did before.
 *
 * <p><strong>Threading (multi-instance deployments).</strong> {@code HttpVerticle} may be deployed with
 * {@code instances > 1}, so binds for the same mount can race across event loops. Cache population goes
 * through {@link ConcurrentMap#computeIfAbsent} so exactly one load starts per path and every instance
 * shares its future. Request-time continuation never runs on a foreign event loop: a gate reads an
 * already-completed contract future synchronously on the request's own {@link Context}, re-dispatches a
 * still-pending completion onto that context, and issues the request's single terminal call
 * ({@code ctx.next()} or {@code ctx.fail(...)}) on that context — the standalone {@link RequestValidator}
 * completes its own futures on a context derived from the {@link Vertx} instance it was created from,
 * which is not necessarily the one serving the request.
 */
@Singleton
@Slf4j
public final class OpenApiContractValidationStrategy implements RequestValidationStrategy {

    /** The selection id for the OpenAPI-contract strategy. */
    public static final String ID = "openapi-contract";

    /**
     * The mount path substituted for a mount identity in the 500 log emitted by the legacy,
     * mount-agnostic gate, which validates against the global contract and knows no mount.
     */
    private static final String LEGACY_MOUNT_PATH = "<global>";

    /** The Vert.x instance every contract load and validator creation goes through. */
    private final Vertx vertx;

    /**
     * The global {@link JaxRsConfig#openapiPath()}: pre-warmed at construction and used by the legacy,
     * mount-agnostic {@link #gateFor(JaxRsOperationDescriptor, OperationSchemas)}. May be {@code null}
     * when the application explicitly configures no global contract path, in which case nothing is
     * pre-warmed and only per-mount gates are available.
     */
    private final String contractPath;

    /**
     * One loaded contract plus its validator per distinct {@code openapiPath}, keyed by that path.
     * Populated by the construction pre-warm and by {@link #bindToMount(MountMeta)} through
     * {@link ConcurrentMap#computeIfAbsent}, so exactly one load starts per path even when mounts bind
     * concurrently from several event loops. Bounded by the number of distinct configured contract
     * paths (mounts are fixed at startup); a failed load is retained as a failed future and never
     * retried.
     */
    private final ConcurrentMap<String, Future<Loaded>> contracts = new ConcurrentHashMap<>();

    /**
     * The framework conversion resolver, threaded into the {@link DefaultBoundRequest} this strategy
     * builds when triggering the profile first-parse so the gate binds through the same conversion
     * chain as dispatch (the 3-site propagation contract).
     */
    private final ParamConversionResolver paramConversionResolver;

    /**
     * Creates the strategy and pre-warms the global contract: when {@link JaxRsConfig#openapiPath()} is
     * non-null its asynchronous load starts here and the resulting {@code Future} of the loaded contract
     * plus the {@link RequestValidator} derived from it is cached under that path. An explicitly null
     * global path loads nothing; each mount's own contract is then loaded at
     * {@link #bindToMount(MountMeta)}.
     *
     * @param vertx                   the Vert.x instance used to load contracts and create validators
     * @param config                  the JAX-RS runtime configuration; {@link JaxRsConfig#openapiPath()}
     *                                supplies the classpath location of the global OpenAPI contract
     *                                (default {@code "openapi.json"})
     * @param paramConversionResolver the framework parameter-conversion resolver
     */
    @Inject
    public OpenApiContractValidationStrategy(
            Vertx vertx, JaxRsConfig config, ParamConversionResolver paramConversionResolver) {
        this.vertx = vertx;
        this.contractPath = config.openapiPath();
        this.paramConversionResolver = paramConversionResolver;
        if (contractPath != null) {
            contracts.computeIfAbsent(contractPath, this::load);
        }
    }

    /**
     * Convenience constructor that defaults the parameter-conversion resolver to the framework
     * built-ins-only resolver. Used by tests that construct the strategy directly without a
     * Dagger-managed resolver; production wiring uses the three-arg {@code @Inject} constructor.
     *
     * @param vertx  the Vert.x instance used to load the contract and create the validator
     * @param config the JAX-RS runtime configuration
     */
    public OpenApiContractValidationStrategy(Vertx vertx, JaxRsConfig config) {
        this(vertx, config, dev.vertique.rest.jaxrs.convert.ConversionContexts.defaultResolver());
    }

    /**
     * The cached, once-loaded contract and the {@link RequestValidator} derived from it. The contract is
     * retained alongside the validator so the gate can look up an {@link Operation} by id when extracting
     * a {@link ValidatableRequest} from the already-buffered request.
     *
     * @param contract  the loaded OpenAPI contract
     * @param validator the request validator created from {@code contract}
     */
    private record Loaded(OpenAPIContract contract, RequestValidator validator) {}

    /**
     * Starts the asynchronous load of the contract at {@code path} and derives its
     * {@link RequestValidator}. Used as the {@link ConcurrentMap#computeIfAbsent} mapping function: it
     * only starts the load and returns its {@code Future}, never blocking and never touching the cache.
     *
     * @param path the classpath location of the OpenAPI contract to load
     * @return the future of the loaded contract plus its validator; a failed load is WARN-logged and the
     *     failed future is what gets cached (a broken contract is never retried)
     */
    private Future<Loaded> load(String path) {
        return load(path, null);
    }

    /**
     * Starts the asynchronous load of the contract at {@code path} on behalf of {@code mountId} (or of
     * the construction pre-warm when {@code mountId} is {@code null}), naming the mount in the failure
     * WARN so an operator can tell which mount's contract is broken.
     *
     * @param path    the classpath location of the OpenAPI contract to load
     * @param mountId the id of the mount whose bind triggered this load, or {@code null} for the global
     *                pre-warm
     * @return the future of the loaded contract plus its validator
     */
    private Future<Loaded> load(String path, String mountId) {
        return OpenAPIContract.from(vertx, path)
                .map(contract -> new Loaded(contract, RequestValidator.create(vertx, contract)))
                .onFailure(err -> {
                    if (mountId == null) {
                        log.warn("openapi-contract: failed to load OpenAPI contract '{}'", path, err);
                    } else {
                        log.warn(
                                "openapi-contract: mount '{}' failed to load OpenAPI contract '{}'",
                                mountId,
                                path,
                                err);
                    }
                });
    }

    @Override
    public String id() {
        return ID;
    }

    /**
     * Binds this strategy to one mount by making sure that mount's OpenAPI contract is loaded, or is
     * loading, before any of the mount's gates are built. The mount's {@link MountMeta#openapiPath()} is
     * the cache key: the first mount to declare a given path starts its load, every later mount
     * declaring the same path reuses that load, and mounts declaring divergent paths each get their own
     * contract. Binding is therefore idempotent, and divergent paths are supported rather than rejected.
     *
     * <p>A mount that declares <em>no</em> contract path is rejected: this strategy validates every
     * operation against a contract, so a missing path is a deployment misconfiguration that must fail at
     * startup rather than silently validate the mount against another mount's contract.
     *
     * <p>Called once per mount at router-build time (startup). {@code HttpVerticle} may be deployed with
     * several instances, so binds for the same mount can race across event loops; {@code synchronized}
     * plus the cache's {@link ConcurrentMap#computeIfAbsent} keep exactly one load per path.
     *
     * <p>A contract that fails to load is WARN-logged naming the mount id and path and its failed future
     * is cached: the failure is not retried and surfaces as HTTP 500 on that mount's own operations when
     * a request reaches one of its gates. Other mounts are unaffected.
     *
     * @param mountMeta the metadata for the mount being bound; its {@link MountMeta#openapiPath()} keys
     *                  the contract cache
     * @throws RestConfigurationException when {@link MountMeta#openapiPath()} is {@code null}, naming the
     *     mount id
     */
    @Override
    public synchronized void bindToMount(MountMeta mountMeta) {
        String openapiPath = mountMeta.openapiPath();
        if (openapiPath == null) {
            throw new RestConfigurationException(
                    "openapi-contract validation is active but mount '" + mountMeta.mountId()
                            + "' declares no OpenAPI contract path. The openapi-contract strategy validates every "
                            + "operation against that mount's contract, so each mount must declare an openapiPath. "
                            + "Give the mount an openapiPath, or select a validation strategy that needs no contract.");
        }
        contracts.computeIfAbsent(openapiPath, path -> load(path, mountMeta.mountId()));
    }

    /**
     * Produces the per-operation validation gate for the mount registering the operation — the form the
     * framework calls. The gate validates against <em>that mount's</em> contract, the one
     * {@link #bindToMount(MountMeta)} cached under {@link MountMeta#openapiPath()}, so mounts declaring
     * divergent contract paths each validate against their own contract. The gate is installed for
     * <em>every</em> operation (the loaded contract — not the synthesized {@code schemas} — drives
     * validation), so this never returns {@link Optional#empty()}.
     *
     * <p>The gate distinguishes two failure classes:
     * <ul>
     *   <li>A {@link ValidatorException} (including {@link SchemaValidationException}) — a client
     *       request-validation failure. The gate calls {@link #mapToRestValidationException} and fails
     *       the context with the resulting {@link RestValidationException}, which the REST error pipeline
     *       renders as a 400.</li>
     *   <li>Any other throwable — a server/config error (e.g. the operationId is absent from the
     *       mount's contract, or that contract failed to load). The gate logs the error naming the
     *       mount path and the contract path, and fails the context with the raw throwable via
     *       {@code ctx.fail(err)}, which the framework's failure handler maps to a 500 response.</li>
     * </ul>
     *
     * @param op      the operation descriptor; only its {@link JaxRsOperationDescriptor#operationId()}
     *                is used (to look the operation up in the contract)
     * @param schemas the synthesized parameter/body schemas; ignored by this strategy
     * @param mount   the metadata of the mount registering this operation; its
     *                {@link MountMeta#openapiPath()} selects the contract to validate against
     * @return a present gate handler
     * @throws IllegalStateException when the mount declares no {@code openapiPath}, or when no contract
     *     is cached for it — both mean {@link #bindToMount(MountMeta)} did not run for this mount, which
     *     is fatal at router-build time rather than silently validated against another contract
     */
    @Override
    public Optional<Handler<RoutingContext>> gateFor(
            JaxRsOperationDescriptor op, OperationSchemas schemas, MountMeta mount) {
        String mountContractPath = mount.openapiPath();
        if (mountContractPath == null) {
            throw new IllegalStateException("openapi-contract: mount '" + mount.mountId()
                    + "' declares no OpenAPI contract path, so no validation gate can be built for operation '"
                    + op.operationId() + "'.");
        }
        Future<Loaded> loaded = contracts.get(mountContractPath);
        if (loaded == null) {
            throw new IllegalStateException("openapi-contract: no contract is bound for mount '" + mount.mountId()
                    + "' (openapiPath '" + mountContractPath + "'); bindToMount must run for a mount before its "
                    + "operations' gates are built.");
        }
        return Optional.of(gate(op, loaded, mountContractPath, mount.mountPath()));
    }

    /**
     * Legacy, mount-agnostic gate construction against the global {@link JaxRsConfig#openapiPath()}
     * contract. The framework never calls this form — {@code JaxRsRouteRegistrar} calls
     * {@link #gateFor(JaxRsOperationDescriptor, OperationSchemas, MountMeta)} — so it exists only for a
     * caller (e.g. a decorating strategy) that still holds the two-argument SPI form.
     *
     * <p>It fails closed rather than silently validating against the wrong contract: once any mount has
     * bound a contract path other than the global one, this form has no way to tell which mount an
     * operation belongs to, so it throws an {@link IllegalStateException} naming the bound paths. Use
     * the three-argument form, which resolves each mount's own contract.
     *
     * @param op      the operation descriptor; only its {@link JaxRsOperationDescriptor#operationId()}
     *                is used (to look the operation up in the contract)
     * @param schemas the synthesized parameter/body schemas; ignored by this strategy
     * @return a present gate handler validating against the global contract
     * @throws IllegalStateException when no global {@code jaxrs.openapiPath} is configured, or when a
     *     mount declaring a divergent contract path has been bound
     */
    @Override
    public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
        return Optional.of(gate(op, globalContract(), contractPath, LEGACY_MOUNT_PATH));
    }

    /**
     * Resolves the global contract for the legacy, mount-agnostic gate, failing closed when it cannot be
     * the right contract for every operation.
     *
     * @return the cached future of the global contract
     * @throws IllegalStateException when no global contract path is configured, or when mounts with
     *     divergent contract paths are bound (the message names the bound paths)
     */
    private Future<Loaded> globalContract() {
        Set<String> divergent = new TreeSet<>(contracts.keySet());
        if (contractPath == null) {
            throw new IllegalStateException(
                    "openapi-contract: the mount-agnostic gateFor(op, schemas) validates against the global "
                            + "jaxrs.openapiPath contract, but no global openapiPath is configured (bound mount "
                            + "contract paths: " + divergent
                            + "). Use gateFor(op, schemas, mount) so each mount validates against its own contract.");
        }
        divergent.remove(contractPath);
        if (!divergent.isEmpty()) {
            throw new IllegalStateException(
                    "openapi-contract: the mount-agnostic gateFor(op, schemas) would validate every operation "
                            + "against the global contract '" + contractPath + "', but mounts declaring divergent "
                            + "contract paths " + divergent + " are bound. Use gateFor(op, schemas, mount) so each "
                            + "mount validates against its own contract.");
        }
        Future<Loaded> loaded = contracts.get(contractPath);
        if (loaded == null) {
            throw new IllegalStateException("openapi-contract: the global contract '" + contractPath
                    + "' is not loaded, so no mount-agnostic validation gate can be built.");
        }
        return loaded;
    }

    /**
     * Builds the request-time gate for one operation against one already-cached contract future.
     *
     * @param op            the operation descriptor whose {@code operationId} is looked up in the contract
     * @param loaded        the cached future of the contract plus validator this gate validates against
     * @param boundContractPath  the contract path {@code loaded} was loaded from, used in log and error text
     * @param mountPath     the mount path the operation is served under, named in the 500 log
     * @return the gate handler
     */
    private Handler<RoutingContext> gate(
            JaxRsOperationDescriptor op, Future<Loaded> loaded, String boundContractPath, String mountPath) {
        String operationId = op.operationId();
        return ctx -> {
            // FR-JSON-024/024A — profile first-parse contract (review finding W-A). When a non-vertx JSON
            // profile mapper was stashed on the context (by JaxRsRouteRegistrar, ahead of this gate, for
            // EVERY strategy), the profile mapper must own the FIRST PARSE of the request body — applying
            // its strict parser features — BEFORE OpenAPI schema validation runs. Trigger it here so a
            // strict-parse rejection surfaces as a 400 uniformly across strategies (the web-validation
            // strategy already does this via its DefaultBoundRequest construction). When no profile mapper
            // is present (the vertx default), this is a no-op and the path below is unchanged.
            if (!triggerProfileFirstParse(ctx, op)) {
                return; // profile parse rejected the body; ctx already failed with a 400 ValidationException
            }
            Context requestContext = requestContext(ctx);
            if (loaded.isComplete()) {
                // Steady state: the contract finished loading at startup. Read the result synchronously so
                // the request continues inline, on its own context — never on the context that loaded it.
                validateAndContinue(ctx, requestContext, operationId, loaded, boundContractPath, mountPath);
            } else {
                // Still loading: the completion fires on the loading context, so hop back onto the request's
                // own context before continuing or failing the request.
                loaded.onComplete(result -> requestContext.runOnContext(ignored ->
                        validateAndContinue(ctx, requestContext, operationId, result, boundContractPath, mountPath)));
            }
        };
    }

    /**
     * Validates the request against the loaded contract and issues the request's single terminal call —
     * {@code ctx.next()} for a conforming request, {@code ctx.fail(...)} otherwise. Always invoked on
     * {@code requestContext}, and every terminal call is issued on it too.
     *
     * @param ctx            the routing context being gated
     * @param requestContext the context this request is being handled on; every continuation runs on it
     * @param operationId    the contract operation to validate against
     * @param loadResult     the outcome of this mount's contract load
     * @param boundContractPath the contract path, used in log and error text
     * @param mountPath      the mount path the operation is served under, named in the 500 log
     */
    private void validateAndContinue(
            RoutingContext ctx,
            Context requestContext,
            String operationId,
            AsyncResult<Loaded> loadResult,
            String boundContractPath,
            String mountPath) {
        if (loadResult.failed()) {
            failServerError(ctx, requestContext, operationId, boundContractPath, mountPath, loadResult.cause());
            return;
        }
        Loaded loaded = loadResult.result();
        Operation operation = loaded.contract().operation(operationId);
        if (operation == null) {
            failServerError(
                    ctx,
                    requestContext,
                    operationId,
                    boundContractPath,
                    mountPath,
                    new IllegalStateException(
                            "No operation '" + operationId + "' in OpenAPI contract '" + boundContractPath + "'"));
            return;
        }
        // Body-read-once: BodyHandler already buffered the body into ctx.body(). Supply that buffered body
        // to RequestUtils.extract via the body Supplier overload so it builds the ValidatableRequest from
        // the buffered request WITHOUT re-reading the request stream.
        RequestUtils.extract(ctx.request(), operation, () -> bufferedBody(ctx))
                .compose(request -> loaded.validator().validate(request, operationId))
                .onSuccess(validated -> onRequestContext(requestContext, ctx::next))
                .onFailure(err -> {
                    if (err instanceof ValidatorException) {
                        // Client request-validation failure (ValidatorException or SchemaValidationException):
                        // map to RestValidationException so the REST error pipeline renders a 400.
                        onRequestContext(
                                requestContext, () -> ctx.fail(mapToRestValidationException((ValidatorException) err)));
                    } else {
                        failServerError(ctx, requestContext, operationId, boundContractPath, mountPath, err);
                    }
                });
    }

    /**
     * Fails the request with a server/configuration error (a missing operationId, a contract that failed
     * to load, or any non-{@link ValidatorException} failure). The raw throwable is passed through so the
     * framework failure handler renders a 500, and the cause is logged at {@code ERROR} naming both the
     * mount path and the contract path so the operator can tell which mount is misconfigured.
     *
     * @param ctx            the routing context to fail
     * @param requestContext the context the failure is issued on
     * @param operationId    the operation being validated
     * @param boundContractPath the contract path consulted for this mount
     * @param mountPath      the mount path the operation is served under
     * @param err            the server/configuration failure
     */
    private void failServerError(
            RoutingContext ctx,
            Context requestContext,
            String operationId,
            String boundContractPath,
            String mountPath,
            Throwable err) {
        log.error(
                "openapi-contract: server/config error for operation '{}' on mount '{}' (contract '{}') — "
                        + "this is a deployment misconfiguration, not a client error",
                operationId,
                mountPath,
                boundContractPath,
                err);
        onRequestContext(requestContext, () -> ctx.fail(err));
    }

    /**
     * Returns the {@link Context} the request is being handled on: the current context when the gate runs
     * on an event loop (always the case in production), falling back to the routing context's Vert.x
     * instance otherwise.
     *
     * @param ctx the routing context being gated
     * @return the context every continuation for this request must run on
     */
    private static Context requestContext(RoutingContext ctx) {
        Context current = Vertx.currentContext();
        return current != null ? current : ctx.vertx().getOrCreateContext();
    }

    /**
     * Runs the request's terminal continuation on the request's own context: inline when already there
     * (the production steady state, so no extra dispatch is introduced), and re-dispatched onto that
     * context otherwise. The continuation must never run on a foreign context — neither the context that
     * loaded the contract, nor the context the standalone vertx-openapi {@link RequestValidator}
     * completes its own futures on when it was created from a different {@link Vertx} instance.
     *
     * @param requestContext the context the request is being handled on
     * @param continuation   the single terminal call for this request ({@code next} or {@code fail})
     */
    private static void onRequestContext(Context requestContext, Runnable continuation) {
        if (Vertx.currentContext() == requestContext) {
            continuation.run();
        } else {
            requestContext.runOnContext(ignored -> continuation.run());
        }
    }

    /**
     * Triggers the JSON-profile request-body FIRST PARSE when a non-{@code vertx} profile mapper is
     * stashed on the routing context under {@link BoundRequest#KEY_RESOLVED_BODY_MAPPER}
     * (FR-JSON-024/024A, review finding W-A). The production {@code JaxRsRouteRegistrar} installs that
     * stash ahead of this gate for <em>every</em> validation strategy, so a non-{@code vertx} profile's
     * strict parser features must apply to the body's first parse here — before OpenAPI schema
     * validation — exactly as the {@code web-validation} strategy does via its
     * {@link DefaultBoundRequest} construction.
     *
     * <p>The shared per-request {@link BoundRequest} is reused when already stashed (so the downstream
     * invoker does not re-parse), or constructed and stashed otherwise. {@link DefaultBoundRequest}'s
     * constructor first-parses the body eagerly through the profile mapper; a strict-parse rejection
     * surfaces as a {@code dev.vertique.core.exception.ValidationException} (HTTP 400), which this method
     * routes to {@code ctx.fail(...)} so the OpenAPI schema validation is skipped.
     *
     * <p>When no profile mapper is stashed (the {@code vertx} default), this is a no-op returning
     * {@code true}, leaving the existing OpenAPI-validation path byte-for-byte unchanged.
     *
     * @param ctx the routing context; carries the optional stashed profile mapper and shared bound request
     * @param op  the operation descriptor used to construct the {@link DefaultBoundRequest}
     * @return {@code true} when the body was accepted (or no profile applies) and OpenAPI validation
     *     should proceed; {@code false} when the profile first parse rejected the body and the routing
     *     context was already failed with a 400
     */
    private boolean triggerProfileFirstParse(RoutingContext ctx, JaxRsOperationDescriptor op) {
        ObjectMapper profileMapper = ctx.get(BoundRequest.KEY_RESOLVED_BODY_MAPPER);
        if (profileMapper == null) {
            return true; // vertx default: no profile first parse, OpenAPI validation runs unchanged
        }
        BoundRequest bound = ctx.get(BoundRequest.KEY_META_DATA_BOUND_REQUEST);
        try {
            if (bound == null) {
                // Constructing DefaultBoundRequest first-parses the body through the profile mapper; a
                // strict-parse rejection throws a 400 ValidationException here.
                bound = new DefaultBoundRequest(ctx, op, paramConversionResolver);
                ctx.put(BoundRequest.KEY_META_DATA_BOUND_REQUEST, bound);
            }
            // Force the body value so the first parse runs even if a future binder construction goes lazy;
            // for the eager constructor this is a no-op read of the already-parsed body.
            bound.body().get();
            return true;
        } catch (RuntimeException profileRejection) {
            ctx.fail(profileRejection);
            return false;
        }
    }

    /**
     * Returns the already-buffered request body as a succeeded {@link Future}, without reading the
     * request stream. {@code BodyHandler} runs ahead of this gate and has already materialised the body
     * into {@link RoutingContext#body()}, so this reads the buffered bytes (an empty buffer when the
     * request had no body) rather than consuming the stream a second time.
     *
     * @param ctx the routing context whose body was already buffered by {@code BodyHandler}
     * @return a succeeded future of the buffered body, never the live request stream
     */
    private static Future<Buffer> bufferedBody(RoutingContext ctx) {
        Buffer body = ctx.body() != null ? ctx.body().buffer() : null;
        return Future.succeededFuture(body != null ? body : Buffer.buffer());
    }

    /**
     * The sanitized top-level message carried by every mapped {@link RestValidationException}. The raw
     * vertx-openapi / vertx-json-schema message is intentionally discarded: it can echo the submitted
     * request value (e.g. {@code "String is too long (27 > 4)"}) or the client-supplied undeclared
     * property name (e.g. {@code Property "internalSecretToken" does not match …}). The structured
     * per-field details (sanitized pointer + keyword) carry the actionable context instead.
     */
    private static final String SANITIZED_MESSAGE = "Request validation failed";

    /**
     * Maps a vertx-openapi request-validation failure to a {@link RestValidationException} so the REST
     * error pipeline renders a 400 response. When the failure is a {@link SchemaValidationException},
     * sanitized per-field detail is extracted from its {@link OutputUnit}; for a plain
     * {@link ValidatorException} a sanitized message is carried with an empty error list.
     *
     * <p><strong>Sanitization (W6).</strong> The raw vertx-openapi / vertx-json-schema exception
     * message and per-error messages are <em>never</em> surfaced to the client: they can echo the
     * submitted request value or a client-supplied undeclared property name, and can carry internal
     * library text. The top-level message is replaced with the constant {@link #SANITIZED_MESSAGE}, and
     * each per-field {@code detail} is regenerated from the failed JSON-Schema keyword alone (see
     * {@link #safeDetail}). Only the JSON-Pointer instance location (a field path, not a value) and the
     * failed keyword are propagated — mirroring the {@code web-validation} strategy's {@code safeDetail}
     * discipline.
     *
     * <p>This method <strong>must only be called with a {@link ValidatorException}</strong> (or its
     * {@link SchemaValidationException} subclass). Server/config errors (e.g. missing operationId,
     * contract-load failures) must NOT be routed here — they must be forwarded as-is via
     * {@code ctx.fail(err)} so the framework renders a 500.
     *
     * @param err the request-validation failure; must be a {@link ValidatorException} or its subclass
     * @return a {@link RestValidationException} carrying the sanitized structured field errors
     */
    static RestValidationException mapToRestValidationException(ValidatorException err) {
        if (err instanceof SchemaValidationException sve) {
            return new RestValidationException(SANITIZED_MESSAGE, toErrorDetails(sve.getOutputUnit()), sve);
        }
        return new RestValidationException(SANITIZED_MESSAGE, List.of(), err);
    }

    /**
     * Extracts <em>sanitized</em> per-field {@link ValidationErrorDetail}s from a vertx-json-schema
     * {@link OutputUnit} (W6). The unit's {@code errors} list (when present) is flattened into one detail
     * per concrete reported error; otherwise the unit itself is turned into a single detail. Structural
     * traversal wrapper errors (e.g. {@code properties}, {@code allOf}) are skipped so each detail refers
     * to a concrete violated constraint. {@code additionalProperties} is NOT skipped — it is a concrete
     * client error (an undeclared property was sent) and is retained as a keyword.
     *
     * <p>Each detail carries only the sanitized JSON-Pointer field path and the failed keyword — never
     * the raw vertx-json-schema message (which can echo the submitted value) and never a client-supplied
     * undeclared property name.
     *
     * @param outputUnit the schema validation output unit; may be {@code null}
     * @return a list of sanitized validation error details (possibly empty), never {@code null}
     */
    private static List<ValidationErrorDetail> toErrorDetails(OutputUnit outputUnit) {
        if (outputUnit == null) {
            return List.of();
        }
        List<OutputUnit> errors = outputUnit.getErrors();
        List<ValidationErrorDetail> details = new ArrayList<>();
        if (errors == null || errors.isEmpty()) {
            ValidationErrorDetail single = toDetail(outputUnit);
            if (single != null) {
                details.add(single);
            }
            return details;
        }
        for (OutputUnit error : errors) {
            ValidationErrorDetail detail = toDetail(error);
            if (detail != null) {
                details.add(detail);
            }
        }
        // A schema-violation OutputUnit with only structural wrappers (all skipped) would yield an empty
        // list; emit a single generic, value-free detail so the client still gets actionable structure.
        if (details.isEmpty()) {
            details.add(new ValidationErrorDetail("", "is invalid", "body", null, null));
        }
        return details;
    }

    /**
     * Converts a single {@link OutputUnit} error into a <em>sanitized</em> {@link ValidationErrorDetail},
     * or {@code null} when the error is a structural traversal wrapper (no concrete constraint) and
     * therefore carries nothing actionable.
     *
     * <p>The {@code detail} message is regenerated from the failed keyword alone via {@link #safeDetail}
     * — the raw {@link OutputUnit#getError()} text is discarded because it can echo the submitted request
     * value (e.g. {@code "String is too long (27 > 4)"}). The {@code path} is the sanitized JSON-Pointer
     * instance location with any trailing client-supplied undeclared-property segment stripped, so a
     * client-chosen property name never leaks into the response.
     *
     * <p>The keyword location is parsed <em>once</em> via {@link SchemaErrorKeywords#extractKeyword}
     * to obtain the raw last segment; the structural classification then uses that segment directly,
     * avoiding a redundant second parse of the same location string.
     *
     * @param error the output unit error entry
     * @return the sanitized detail, or {@code null} for a structural wrapper
     */
    private static ValidationErrorDetail toDetail(OutputUnit error) {
        // Parse the keyword location once to obtain the raw last segment.
        String segment = SchemaErrorKeywords.extractKeyword(error.getKeywordLocation());
        // A structural keyword (but NOT additionalProperties, which is a concrete constraint here
        // representing an undeclared property under additionalProperties:false) means this error
        // is a traversal wrapper — skip it.
        if (segment != null
                && SchemaErrorKeywords.STRUCTURAL_KEYWORDS.contains(segment)
                && !"additionalProperties".equals(segment)) {
            return null;
        }
        // segment is either a concrete keyword (non-structural) or null (absent location —
        // treated as no keyword, producing a generic "is invalid" detail).
        String path = sanitizePath(error.getInstanceLocation(), segment);
        return new ValidationErrorDetail(path, safeDetail(segment), "body", segment, null);
    }

    /**
     * Produces a sanitized JSON-Pointer path for an error. For an {@code additionalProperties} violation
     * the instance location's last segment is the client-supplied undeclared property name (e.g.
     * {@code #/internalSecretToken}); that segment is stripped so the path points at the containing
     * object and the client's property name never leaks. All other paths are pass-through (a declared
     * field pointer such as {@code #/pin} is part of the contract, not submitted data).
     *
     * @param instanceLocation the {@link OutputUnit} instance location; may be {@code null}
     * @param keyword          the failed keyword, used to detect the {@code additionalProperties} case
     * @return the sanitized path, never {@code null}
     */
    private static String sanitizePath(String instanceLocation, String keyword) {
        if (instanceLocation == null || instanceLocation.isEmpty()) {
            return "";
        }
        if ("additionalProperties".equals(keyword)) {
            int lastSlash = instanceLocation.lastIndexOf('/');
            return lastSlash <= 0 ? "" : instanceLocation.substring(0, lastSlash);
        }
        return instanceLocation;
    }

    /**
     * Generates a safe, canonical {@code detail} message from the failed JSON-Schema keyword alone —
     * never echoing the submitted value (W6). Mirrors the {@code web-validation} strategy's phrasing
     * vocabulary; because the standalone {@link RequestValidator} path does not surface the constraint
     * value to this mapper, the messages describe the constraint without a numeric bound.
     *
     * @param keyword the failed keyword (e.g. {@code "maxLength"}), or {@code null}
     * @return a value-free detail message
     */
    private static String safeDetail(String keyword) {
        if (keyword == null) {
            return "is invalid";
        }
        return switch (keyword) {
            case "minLength" -> "must be longer";
            case "maxLength" -> "must be shorter";
            case "minimum" -> "must be larger";
            case "maximum" -> "must be smaller";
            case "exclusiveMinimum" -> "must be larger";
            case "exclusiveMaximum" -> "must be smaller";
            case "minItems" -> "must have more items";
            case "maxItems" -> "must have fewer items";
            case "minProperties" -> "must have more properties";
            case "maxProperties" -> "must have fewer properties";
            case "multipleOf" -> "must be a valid multiple";
            case "pattern" -> "must match the required pattern";
            case "format" -> "must match the required format";
            case "required" -> "is missing a required field";
            case "type" -> "must be of the required type";
            case "enum" -> "must be one of the permitted values";
            case "const" -> "must equal the required value";
            case "additionalProperties" -> "contains an undeclared property";
            default -> keyword + " constraint violated";
        };
    }
}
