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
import lombok.extern.slf4j.Slf4j;

/**
 * The opt-in {@code openapi-contract} {@link RequestValidationStrategy}: validates each request against
 * the application's OpenAPI contract using the standalone vertx-openapi {@link RequestValidator}, rather
 * than the annotation-synthesized schemas the {@code web-validation} strategy uses (FR-020). Selecting
 * this strategy (via {@code jaxrs.validationStrategy = "openapi-contract"}) requires
 * {@code vertique-rest-openapi-validation} on the classpath — the only module that depends on the
 * preview {@code vertx-openapi} artifact (FR-025).
 *
 * <p><strong>Contract load is asynchronous and cached.</strong> {@link OpenAPIContract#from(Vertx,
 * String)} returns a {@link Future}; this strategy is a singleton and {@link #gateFor} is called
 * synchronously at router-build time, so the contract is loaded — and the {@link RequestValidator}
 * derived from it — exactly once at construction. The resulting {@code Future} (the loaded contract plus
 * its validator) is cached and composed per request, so a contract that is slow or absent does not block
 * router construction and a load failure surfaces when the strategy is actually used rather than
 * hard-failing the whole application at construction (this strategy is opt-in).
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
 * <p><strong>Single-contract guarantee — fail closed on divergent mounts (W1).</strong> This strategy
 * is a {@link Singleton} that resolves <em>one</em> OpenAPI contract from the global
 * {@link JaxRsConfig#openapiPath()} at construction, and {@link #gateFor} carries no mount context — so
 * the singleton cannot tell which mount an operation belongs to. A multi-mount application whose mounts
 * declare <em>divergent</em> {@code openapiPath}s would therefore validate every operation against the
 * one global contract, silently validating some mounts' operations against the wrong contract. To make
 * that impossible, {@link #bindToMount(MountMeta)} (called once per mount at router-build time) fails
 * fast: it throws a {@link RestConfigurationException} naming the divergent paths whenever a mount's
 * {@code openapiPath} differs from the contract path this strategy actually loaded. A single mount, or
 * multiple mounts sharing one {@code openapiPath}, bind cleanly. (Threading each mount's contract into
 * per-operation gate construction — so divergent mounts each validate against their own contract — was
 * deferred as disproportionate for this opt-in strategy; the fail-closed guard removes the silent-
 * mismatch hazard without a cross-module SPI change.)
 */
@Singleton
@Slf4j
public final class OpenApiContractValidationStrategy implements RequestValidationStrategy {

    /** The selection id for the OpenAPI-contract strategy. */
    public static final String ID = "openapi-contract";

    private final String contractPath;
    private final Future<Loaded> loadedFuture;

    /**
     * The framework conversion resolver, threaded into the {@link DefaultBoundRequest} this strategy
     * builds when triggering the profile first-parse so the gate binds through the same conversion
     * chain as dispatch (the 3-site propagation contract).
     */
    private final ParamConversionResolver paramConversionResolver;

    /**
     * Creates the strategy, starting the asynchronous contract load once and caching the resulting
     * {@code Future} of the loaded contract plus the {@link RequestValidator} derived from it.
     *
     * @param vertx                   the Vert.x instance used to load the contract and create the
     *                                validator
     * @param config                  the JAX-RS runtime configuration; {@link JaxRsConfig#openapiPath()}
     *                                supplies the classpath location of the OpenAPI contract (default
     *                                {@code "openapi.json"})
     * @param paramConversionResolver the framework parameter-conversion resolver
     */
    @Inject
    public OpenApiContractValidationStrategy(
            Vertx vertx, JaxRsConfig config, ParamConversionResolver paramConversionResolver) {
        this.contractPath = config.openapiPath();
        this.paramConversionResolver = paramConversionResolver;
        this.loadedFuture = OpenAPIContract.from(vertx, contractPath)
                .map(contract -> new Loaded(contract, RequestValidator.create(vertx, contract)))
                .onFailure(
                        err -> log.warn("openapi-contract: failed to load OpenAPI contract '{}'", contractPath, err));
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

    @Override
    public String id() {
        return ID;
    }

    /**
     * Fails fast (W1) when a mount's metadata declares an {@code openapiPath} that diverges from the
     * single global contract path this singleton strategy loaded at construction. Because the strategy
     * resolves one contract and {@link #gateFor} has no mount context, validating a mount whose contract
     * path differs from {@link #contractPath} would silently apply the wrong contract; throwing here
     * removes that hazard. Binding a path equal to {@link #contractPath} (the default single-mount case,
     * or several mounts sharing one contract) is allowed and idempotent.
     *
     * <p>Called once per mount at router-build time (startup), single-threaded; {@code synchronized}
     * guards against any concurrent mount construction.
     *
     * @param mountMeta the metadata for the mount being bound; its {@link MountMeta#openapiPath()} is
     *                  compared against the contract path loaded at construction
     * @throws RestConfigurationException when {@link MountMeta#openapiPath()} differs from the loaded
     *     contract path, naming both paths
     */
    @Override
    public synchronized void bindToMount(MountMeta mountMeta) {
        String openapiPath = mountMeta.openapiPath();
        if (!contractPath.equals(openapiPath)) {
            throw new RestConfigurationException(
                    "openapi-contract validation is active but a mount declares a divergent OpenAPI contract path: "
                            + "mount openapiPath='" + openapiPath + "' differs from the loaded contract path '"
                            + contractPath
                            + "'. The openapi-contract strategy resolves a single global contract, so all JAX-RS "
                            + "mounts must declare the same openapiPath (or share the default). Align the mount "
                            + "openapiPath values, or select a per-mount-capable validation strategy.");
        }
    }

    /**
     * Produces the per-operation validation gate. The gate is installed for <em>every</em> operation
     * (the loaded contract — not the synthesized {@code schemas} — drives validation), so this never
     * returns {@link Optional#empty()}.
     *
     * <p>The gate distinguishes two failure classes:
     * <ul>
     *   <li>A {@link ValidatorException} (including {@link SchemaValidationException}) — a client
     *       request-validation failure. The gate calls {@link #mapToRestValidationException} and fails
     *       the context with the resulting {@link RestValidationException}, which the REST error pipeline
     *       renders as a 400.</li>
     *   <li>Any other throwable — a server/config error (e.g. the operationId is absent from the
     *       loaded contract, or the contract itself failed to load). The gate logs the error and fails
     *       the context with the raw throwable via {@code ctx.fail(err)}, which the framework's failure
     *       handler maps to a 500 response.</li>
     * </ul>
     *
     * @param op      the operation descriptor; only its {@link JaxRsOperationDescriptor#operationId()}
     *                is used (to look the operation up in the contract)
     * @param schemas the synthesized parameter/body schemas; ignored by this strategy
     * @return a present gate handler
     */
    @Override
    public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
        String operationId = op.operationId();
        return Optional.of(ctx -> {
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
            loadedFuture
                    .compose(loaded -> {
                        Operation operation = loaded.contract().operation(operationId);
                        if (operation == null) {
                            return Future.failedFuture(new IllegalStateException(
                                    "No operation '" + operationId + "' in OpenAPI contract '" + contractPath + "'"));
                        }
                        // Body-read-once: BodyHandler already buffered the body into ctx.body(). Supply that
                        // buffered body to RequestUtils.extract via the body Supplier overload so it builds the
                        // ValidatableRequest from the buffered request WITHOUT re-reading the request stream.
                        return RequestUtils.extract(ctx.request(), operation, () -> bufferedBody(ctx))
                                .compose(request -> loaded.validator().validate(request, operationId));
                    })
                    .onSuccess(validated -> ctx.next())
                    .onFailure(err -> {
                        if (err instanceof ValidatorException) {
                            // Client request-validation failure (ValidatorException or SchemaValidationException):
                            // map to RestValidationException so the REST error pipeline renders a 400.
                            ctx.fail(mapToRestValidationException((ValidatorException) err));
                        } else {
                            // Server/config error (missing operationId, contract-load failure, etc.):
                            // pass the raw throwable through so the framework failure handler renders a 500.
                            // Log at ERROR so the operator can diagnose the deployment mismatch.
                            log.error(
                                    "openapi-contract: server/config error for operation '{}' (contract '{}') — "
                                            + "this is a deployment misconfiguration, not a client error",
                                    operationId,
                                    contractPath,
                                    err);
                            ctx.fail(err);
                        }
                    });
        });
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
