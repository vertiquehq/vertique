// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import dev.vertique.core.async.Combinators;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.request.MediaType;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.request.DefaultBoundRequest;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import dev.vertique.rest.jaxrs.validation.FileVerificationResult;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import dev.vertique.rest.jaxrs.validation.SchemaErrorKeywords;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.Cookie;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.Validator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code web-validation} {@link RequestValidationStrategy}: validates each request body and
 * declared parameter against vertx-json-schema validators synthesized from the operation's
 * {@link OperationSchemas} (FR-005), and validates declared file-part constraints. It is the default
 * request-validation strategy.
 *
 * <p><strong>Validators are built once, at {@code gateFor} time (startup).</strong> For NFR-002
 * efficiency the body validator and each per-parameter validator are compiled when the gate is
 * produced and closed over by the returned handler, so no schema is compiled on the request hot path.
 *
 * <p><strong>Body</strong> validation uses the shared per-request {@link BoundRequest}: the gate
 * obtains it from the routing context (binding and stashing one if absent) so the gate and downstream
 * dispatch share a single bound request and the body buffer is read once.
 *
 * <p><strong>Parameter</strong> validation reads the <em>raw</em> request value for each declared
 * parameter and coerces it to its declared scalar type for validation — falling back to the raw
 * string when coercion is not possible. Numeric coercion is lenient (a non-numeric string falls back
 * to the raw string); boolean coercion is <em>strict</em> — only {@code "true"}/{@code "false"}
 * (case-insensitively) become a JSON boolean, and any other string falls back to the raw string so a
 * {@code type: boolean} schema rejects it rather than the lenient {@link Boolean#valueOf(String)}
 * silently mapping {@code "notabool"} to {@code false}. Validating the raw value (rather than the
 * coerced {@link BoundRequest} parameter maps, whose binding eagerly coerces and would throw on a
 * non-coercible value such as {@code "abc"} for an {@code Integer} path param) means a type mismatch
 * surfaces as a clean schema {@code type} violation — a 400 {@code application/problem+json} response —
 * rather than an uncaught coercion exception.
 *
 * <p><strong>File-part</strong> validation visits each physical upload once in request order, matches
 * it to an exact case-sensitive named constraint or otherwise an aggregate constraint, and checks
 * maximum size before comparing the client-declared media type directionally against the configured
 * allowed types. Only the configured subtype may be a wildcard.
 *
 * <p><strong>Enrichment (FR-010).</strong> Each {@link ValidationErrorDetail} carries:
 * <ul>
 *   <li>{@code path} — the JSON Pointer instance location of the failing value (e.g. {@code /code})</li>
 *   <li>{@code type} — the failed JSON Schema keyword (e.g. {@code "minLength"}, {@code "required"},
 *       {@code "type"}), extracted from the last segment of the {@code keywordLocation} field
 *       returned by the vertx-json-schema {@link OutputUnit}</li>
 *   <li>{@code args} — the expected constraint value for common keywords (e.g.
 *       {@code {"minLength": 3}}), resolved by navigating the schema {@link JsonObject} along the
 *       keyword location; keywords whose value cannot be resolved appear as {@code {keyword: true}}</li>
 *   <li>{@code location} — the parameter-location token ({@code "body"}, {@code "query"}, etc.)</li>
 * </ul>
 *
 * <p>The serialized error response <strong>never</strong> contains the submitted request value;
 * only the schema constraint and its expected value are included in {@code args}.
 *
 * <p><strong>Validation mode (FR-011).</strong> The {@link JaxRsConfig#validationMode()} field
 * selects the collection strategy:
 * <ul>
 *   <li>{@code "aggregate"} (default) — all violations across params, body, and file parts are
 *       collected before failing (gives clients a complete picture)</li>
 *   <li>{@code "failFast"} — stops at the first violation encountered (params first in declaration
 *       order, then the body, then file parts) and returns a single-entry error list</li>
 * </ul>
 *
 * <p>All failures are collected into a single {@link RestValidationException} that the REST error
 * pipeline maps to a 400 response. A conforming request flows on via {@code ctx.next()}.
 *
 * <p>Only parameters present in {@link JaxRsOperationDescriptor#parameters()} are validated; the
 * adapter that builds the descriptor already filters out non-bindable sources (e.g. {@code @Context}),
 * so iterating the declared parameters naturally excludes them.
 */
@Singleton
public final class WebValidationStrategy implements RequestValidationStrategy {

    /** The selection id for the web-validation strategy. */
    public static final String ID = "web-validation";

    private static final JsonSchemaOptions SCHEMA_OPTIONS = new JsonSchemaOptions()
            .setDraft(Draft.DRAFT202012)
            .setBaseUri("https://vertique.local/")
            .setOutputFormat(OutputFormat.Basic);

    // --- Keywords whose constraint value is a scalar and can be navigated from the schema ---

    /**
     * Common JSON Schema keywords whose constraint value is a simple scalar (number or string) that
     * can be extracted from the schema {@link JsonObject} by navigating the path preceding the last
     * segment of the keyword location. Keywords not in this set fall back to {@code {keyword: true}}.
     */
    private static final Set<String> SCALAR_CONSTRAINT_KEYWORDS = Set.of(
            "minLength",
            "maxLength",
            "minimum",
            "maximum",
            "exclusiveMinimum",
            "exclusiveMaximum",
            "pattern",
            "minItems",
            "maxItems",
            "minProperties",
            "maxProperties",
            "multipleOf");

    /** The {@code aggregate} validation-mode literal: collect all violations before failing. */
    private static final String MODE_AGGREGATE = "aggregate";

    /** The {@code failFast} validation-mode literal: stop at the first violation. */
    private static final String MODE_FAIL_FAST = "failFast";

    private final boolean failFast;

    /**
     * The framework conversion resolver, threaded into every {@link DefaultBoundRequest} this strategy's
     * gate constructs so the gate binds through the same conversion chain as dispatch (the 3-site
     * propagation contract).
     */
    private final ParamConversionResolver paramConversionResolver;

    /** Bound file-content verifiers, sorted once in the framework extension order. */
    private final List<FileContentVerifier> fileContentVerifiers;

    /**
     * Creates the web-validation strategy, parsing the validation mode from {@code config} <strong>once
     * at startup</strong> (this is a {@link Singleton}, constructed during Dagger component creation).
     *
     * <p>The mode is parsed strictly and fails closed: only the exact camelCase literals
     * {@code "aggregate"} and {@code "failFast"} are accepted. A blank or {@code null} value falls back
     * to {@code "aggregate"} (matching {@link JaxRsConfig}'s default), but any other value — including a
     * wrong-case variant ({@code "FailFast"}) or a typo ({@code "agregate"}, {@code "fail-fast"}) —
     * fails startup with a {@link RestConfigurationException} rather than silently changing validation
     * behaviour. Parsing here, not per request, keeps the request hot path free of mode resolution.
     *
     * @param config the JAX-RS runtime configuration; {@link JaxRsConfig#validationMode()} selects
     *     either {@code "aggregate"} (collect all violations) or {@code "failFast"} (stop at first
     *     violation)
     * @param paramConversionResolver the framework parameter-conversion resolver, threaded into the
     *     gate's {@link DefaultBoundRequest} construction
     * @param fileContentVerifiers the bound deep file-content verifiers
     * @throws RestConfigurationException if {@code validationMode} is a non-blank value other than the
     *     two accepted literals
     */
    @Inject
    public WebValidationStrategy(
            JaxRsConfig config,
            ParamConversionResolver paramConversionResolver,
            Set<FileContentVerifier> fileContentVerifiers) {
        this.failFast = parseFailFast(config.validationMode());
        this.paramConversionResolver = paramConversionResolver;
        this.fileContentVerifiers = fileContentVerifiers.stream()
                .sorted(OrderedExtension.comparator())
                .toList();
    }

    /**
     * Convenience constructor for direct callers that need the framework conversion chain but no
     * deep file verification. Production injection uses the three-argument constructor.
     *
     * @param config the JAX-RS runtime configuration
     * @param paramConversionResolver the framework parameter-conversion resolver
     */
    public WebValidationStrategy(JaxRsConfig config, ParamConversionResolver paramConversionResolver) {
        this(config, paramConversionResolver, Set.of());
    }

    /**
     * Convenience constructor that defaults the parameter-conversion resolver to the framework
     * built-ins-only resolver. Used by tests that construct the strategy directly without a
     * Dagger-managed resolver; production wiring uses the three-argument {@code @Inject} constructor.
     *
     * @param config the JAX-RS runtime configuration
     * @throws RestConfigurationException if {@code validationMode} is a non-blank value other than the
     *     two accepted literals
     */
    public WebValidationStrategy(JaxRsConfig config) {
        this(config, dev.vertique.rest.jaxrs.convert.ConversionContexts.defaultResolver());
    }

    /**
     * Strictly resolves the configured validation mode to the {@code failFast} flag. A blank or
     * {@code null} value defaults to {@code aggregate} (the {@link JaxRsConfig} default; a blank should
     * not normally reach here). Any other value is rejected fail-closed.
     *
     * @param validationMode the configured mode literal
     * @return {@code true} for {@code "failFast"}, {@code false} for {@code "aggregate"} or blank
     * @throws RestConfigurationException when {@code validationMode} is a non-blank value other than
     *     {@code "aggregate"} or {@code "failFast"}
     */
    private static boolean parseFailFast(String validationMode) {
        if (validationMode == null || validationMode.isBlank()) {
            return false; // default: aggregate
        }
        if (MODE_FAIL_FAST.equals(validationMode)) {
            return true;
        }
        if (MODE_AGGREGATE.equals(validationMode)) {
            return false;
        }
        throw new RestConfigurationException("Invalid jaxrs.validationMode '" + validationMode
                + "'; allowed values are {" + MODE_AGGREGATE + ", " + MODE_FAIL_FAST + "} (case-sensitive)");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean runsFileVerifiers() {
        return true;
    }

    @Override
    public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
        Validator bodyValidator =
                schemas.bodySchema().map(WebValidationStrategy::compile).orElse(null);
        JsonObject bodySchema = schemas.bodySchema().orElse(null);
        List<FilePartDescriptor> fileParts = List.copyOf(op.fileParts());

        List<ParamValidator> paramValidators = new ArrayList<>();
        for (ParamDescriptor param : op.parameters()) {
            schemas.parameterSchema(param.location(), param.name())
                    .ifPresent(schema -> paramValidators.add(new ParamValidator(
                            param.location(),
                            param.name(),
                            param.type(),
                            param.componentType(),
                            compile(schema),
                            schema)));
        }

        if (bodyValidator == null && paramValidators.isEmpty() && fileParts.isEmpty()) {
            // Nothing to validate (e.g. only @Context params, no body) — install no gate.
            return Optional.empty();
        }

        return Optional.of(new GateHandler(
                op,
                bodyValidator,
                bodySchema,
                List.copyOf(paramValidators),
                fileParts,
                fileContentVerifiers,
                failFast,
                paramConversionResolver));
    }

    /**
     * Compiles a vertx-json-schema {@link Validator} from a schema {@link JsonObject} using the
     * spike-proven DRAFT 2020-12 options.
     *
     * @param schema the schema to compile
     * @return a reusable validator
     */
    private static Validator compile(JsonObject schema) {
        return Validator.create(JsonSchema.of(schema), SCHEMA_OPTIONS);
    }

    /**
     * Maps a {@link ParamLocation} to the {@link ValidationErrorDetail} {@code location} token used in
     * the problem-details body.
     *
     * @param location the parameter location
     * @return the location token: {@code "query"}, {@code "header"}, {@code "path"}, {@code "cookie"},
     *     or {@code "form"}
     */
    private static String locationToken(ParamLocation location) {
        return switch (location) {
            case QUERY -> "query";
            case HEADER -> "header";
            case PATH -> "path";
            case COOKIE -> "cookie";
            case FORM -> "form";
        };
    }

    /**
     * Coerces a raw request string to the parameter's declared scalar type for validation, returning
     * the raw string unchanged when coercion is not possible (e.g. {@code "abc"} for an integer type),
     * so the schema validator reports a clean {@code type} violation instead of throwing.
     *
     * <p><strong>Booleans are coerced strictly.</strong> Only {@code "true"} and {@code "false"}
     * (case-insensitively, matching the lowercase JSON boolean literals) become the corresponding
     * JSON boolean. Any other string is returned unchanged so a {@code type: boolean} schema rejects
     * it as a clean {@code type} violation (a 400 response) rather than silently coercing it to
     * {@code false} — the lenient {@link Boolean#valueOf(String)} behaviour would accept invalid
     * input such as {@code "notabool"} as {@code false}.
     *
     * @param value the raw request string
     * @param type  the declared scalar parameter type
     * @return the coerced value, or the original string when it cannot be coerced
     */
    private static Object lenientCoerce(String value, Class<?> type) {
        try {
            if (type == Integer.class || type == int.class) {
                return Integer.valueOf(value);
            }
            if (type == Long.class || type == long.class) {
                return Long.valueOf(value);
            }
            if (type == Double.class || type == double.class) {
                return Double.valueOf(value);
            }
            if (type == Float.class || type == float.class) {
                return Float.valueOf(value);
            }
            if (type == Boolean.class || type == boolean.class) {
                return strictBoolean(value);
            }
        } catch (NumberFormatException notCoercible) {
            return value;
        }
        return value;
    }

    /**
     * Strictly coerces a raw request string to a JSON boolean for schema validation. Accepts only
     * {@code "true"} and {@code "false"} (case-insensitively); any other string is returned unchanged
     * so a {@code type: boolean} schema rejects it as a {@code type} violation rather than the lenient
     * {@link Boolean#valueOf(String)} silently mapping every non-{@code "true"} value to {@code false}.
     *
     * @param value the raw request string
     * @return {@link Boolean#TRUE} or {@link Boolean#FALSE} for the two boolean literals, otherwise the
     *     original string unchanged
     */
    private static Object strictBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return value;
    }

    /**
     * Resolves the constraint value for the given failed keyword by navigating the schema
     * {@link JsonObject} along the keyword location path. For common scalar-valued keywords
     * (e.g. {@code minLength}, {@code maximum}, {@code pattern}), the value is the schema node
     * at that path. For keywords whose value cannot be resolved, returns {@code {keyword: true}}.
     *
     * <p>The keyword location from vertx-json-schema Basic output uses a {@code #/} prefix, e.g.
     * {@code #/properties/code/minLength}. This method strips the {@code #} anchor, navigates to
     * {@code /properties/code}, and reads the {@code minLength} field from the schema sub-object.
     *
     * <p>The returned map <strong>never</strong> includes the submitted request value — only the
     * schema constraint is included.
     *
     * @param keyword         the failed keyword (e.g. {@code "minLength"})
     * @param keywordLocation the full keyword location path from the {@link OutputUnit} (e.g.
     *                        {@code #/properties/code/minLength}); the {@code #} anchor prefix is
     *                        stripped before navigation
     * @param schema          the root schema {@link JsonObject} being navigated; may be {@code null}
     * @return a one-entry map {@code {keyword: constraintValue}}, or {@code {keyword: true}} when
     *         the constraint value cannot be resolved
     */
    static Map<String, Object> resolveConstraintArgs(String keyword, String keywordLocation, JsonObject schema) {
        if (schema == null || keywordLocation == null || keyword == null) {
            return keyword != null ? Map.of(keyword, Boolean.TRUE) : Map.of();
        }
        if (!SCALAR_CONSTRAINT_KEYWORDS.contains(keyword)) {
            return Map.of(keyword, Boolean.TRUE);
        }
        // Strip the JSON Schema anchor prefix '#' from the keyword location before navigating.
        // vertx-json-schema Basic output uses '#/properties/code/minLength', not '/properties/code/minLength'.
        String normalizedLocation = keywordLocation.startsWith("#") ? keywordLocation.substring(1) : keywordLocation;

        // Navigate the schema to the parent of the keyword, then read the keyword field.
        // normalizedLocation: /properties/code/minLength -> navigate /properties/code
        String parentPath = normalizedLocation.substring(0, normalizedLocation.length() - keyword.length());
        // Remove trailing slash if present
        if (parentPath.endsWith("/")) {
            parentPath = parentPath.substring(0, parentPath.length() - 1);
        }
        JsonObject node = navigateSchema(schema, parentPath);
        if (node == null) {
            return Map.of(keyword, Boolean.TRUE);
        }
        Object value = node.getValue(keyword);
        if (value == null) {
            return Map.of(keyword, Boolean.TRUE);
        }
        return Map.of(keyword, value);
    }

    /**
     * Navigates a schema {@link JsonObject} along the given JSON-pointer-style path, returning
     * the nested {@link JsonObject} at that location, or {@code null} when navigation fails.
     *
     * <p>Empty or blank paths (e.g. {@code ""} or {@code "/"}) return the root schema itself.
     *
     * @param schema the root schema node
     * @param path   the slash-delimited path (e.g. {@code /properties/code})
     * @return the schema sub-object at {@code path}, or {@code null} if unreachable
     */
    private static JsonObject navigateSchema(JsonObject schema, String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return schema;
        }
        JsonObject current = schema;
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            Object next = current.getValue(segment);
            if (next instanceof JsonObject jo) {
                current = jo;
            } else {
                return null;
            }
        }
        return current;
    }

    // --- Inner types ---

    /**
     * A compiled validator bound to a single declared parameter's location, name, and declared type.
     *
     * @param location      the parameter location
     * @param name          the declared parameter name
     * @param type          the declared scalar parameter type, used for lenient coercion before validation
     * @param componentType the element type for a collection parameter ({@code List<T>}/{@code Set<T>}/
     *                      array), or {@code null} for a scalar parameter; when non-null the gate
     *                      validates <em>all</em> request values as a JSON array rather than only the
     *                      first value
     * @param validator     the compiled vertx-json-schema validator for the parameter schema
     * @param schema        the raw schema {@link JsonObject} for constraint-value resolution
     */
    private record ParamValidator(
            ParamLocation location,
            String name,
            Class<?> type,
            Class<?> componentType,
            Validator validator,
            JsonObject schema) {}

    /**
     * The per-request gate handler closing over the operation descriptor and the pre-compiled body and
     * parameter validators. Supports both aggregate and fail-fast collection modes.
     */
    private static final class GateHandler implements Handler<RoutingContext> {

        private final JaxRsOperationDescriptor op;
        private final Validator bodyValidator;
        private final JsonObject bodySchema;
        private final List<ParamValidator> paramValidators;
        private final Map<String, PreparedFilePart> namedFileDescriptors;
        private final PreparedFilePart aggregateFileDescriptor;
        private final List<FileContentVerifier> fileContentVerifiers;
        private final boolean failFast;
        private final ParamConversionResolver paramConversionResolver;

        private GateHandler(
                JaxRsOperationDescriptor op,
                Validator bodyValidator,
                JsonObject bodySchema,
                List<ParamValidator> paramValidators,
                List<FilePartDescriptor> fileParts,
                List<FileContentVerifier> fileContentVerifiers,
                boolean failFast,
                ParamConversionResolver paramConversionResolver) {
            this.op = op;
            this.bodyValidator = bodyValidator;
            this.bodySchema = bodySchema;
            this.paramValidators = paramValidators;
            Map<String, PreparedFilePart> namedDescriptors = new HashMap<>();
            PreparedFilePart aggregateDescriptor = null;
            for (FilePartDescriptor descriptor : fileParts) {
                PreparedFilePart prepared = PreparedFilePart.from(descriptor);
                if (descriptor.partName() == null) {
                    aggregateDescriptor = preferConstrained(aggregateDescriptor, prepared);
                } else {
                    namedDescriptors.merge(descriptor.partName(), prepared, GateHandler::preferConstrained);
                }
            }
            this.namedFileDescriptors = Map.copyOf(namedDescriptors);
            this.aggregateFileDescriptor = aggregateDescriptor;
            this.fileContentVerifiers = fileContentVerifiers;
            this.failFast = failFast;
            this.paramConversionResolver = paramConversionResolver;
        }

        @Override
        public void handle(RoutingContext ctx) {
            List<ValidationErrorDetail> failures = new ArrayList<>();
            validateParams(ctx, failures);
            if (failFast && !failures.isEmpty()) {
                ctx.fail(new RestValidationException("Request validation failed", failures));
                return;
            }
            validateBody(ctx, failures);
            if (failFast && !failures.isEmpty()) {
                ctx.fail(new RestValidationException("Request validation failed", failures));
                return;
            }
            List<ApplicableUpload> uploads = snapshotUploads(ctx);
            validateFileParts(uploads, failures);

            if (!failures.isEmpty()) {
                ctx.fail(new RestValidationException("Request validation failed", failures));
                return;
            }

            if (fileContentVerifiers.isEmpty()) {
                ctx.next();
                return;
            }

            if (uploads.isEmpty()) {
                ctx.next();
                return;
            }

            verifyUploads(uploads).onComplete(outcome -> {
                if (outcome.succeeded()) {
                    ctx.next();
                } else {
                    ctx.fail(outcome.cause());
                }
            });
        }

        /**
         * Snapshots physical uploads in request order. Each upload instance is included at most once,
         * and occurrence pointers count all earlier unique uploads with the same part name. The same
         * frozen snapshot feeds synchronous constraints and deep verification.
         */
        private List<ApplicableUpload> snapshotUploads(RoutingContext ctx) {
            if (namedFileDescriptors.isEmpty() && aggregateFileDescriptor == null) {
                return List.of();
            }

            List<ApplicableUpload> uploads = new ArrayList<>();
            Set<FileUpload> seenUploads = Collections.newSetFromMap(new IdentityHashMap<>());
            Map<String, Integer> occurrencesByName = new HashMap<>();
            for (FileUpload upload : ctx.fileUploads()) {
                if (!seenUploads.add(upload)) {
                    continue;
                }

                String partName = upload.name();
                int occurrence = occurrencesByName.merge(partName, 1, Integer::sum) - 1;
                String pointer = occurrence == 0 ? partName : partName + "[" + occurrence + "]";
                uploads.add(new ApplicableUpload(upload, pointer));
            }
            return List.copyOf(uploads);
        }

        /** Runs every verifier for every applicable upload, sequentially and fail-closed. */
        private Future<Void> verifyUploads(List<ApplicableUpload> uploads) {
            return Combinators.foldSequential(uploads, (Void) null, (upload, ignored) -> {
                if (!verifierApplies(upload)) {
                    return Future.succeededFuture();
                }
                return Combinators.foldSequential(
                        fileContentVerifiers, (Void) null, (verifier, verifierIgnored) -> verify(verifier, upload));
            });
        }

        private boolean verifierApplies(ApplicableUpload upload) {
            return namedFileDescriptors.containsKey(upload.part().name()) || aggregateFileDescriptor != null;
        }

        /**
         * Invokes one verifier on the event loop and normalizes every extension outcome into the
         * sequential chain. The leading succeeded future captures synchronous extension throws
         * without changing their cause identity.
         */
        private static Future<Void> verify(FileContentVerifier verifier, ApplicableUpload upload) {
            return Future.<Void>succeededFuture().compose(ignored -> {
                Future<FileVerificationResult> verification = verifier.verify(upload.part());
                if (verification == null) {
                    return Future.failedFuture(new IllegalStateException("FileContentVerifier returned a null Future"));
                }
                return verification.compose(result -> {
                    if (result == null) {
                        return Future.failedFuture(
                                new IllegalStateException("FileContentVerifier completed with a null result"));
                    }
                    if (result instanceof FileVerificationResult.Rejected rejected) {
                        ValidationErrorDetail detail = new ValidationErrorDetail(
                                upload.pointer(), rejected.detail(), "file", rejected.type(), rejected.args());
                        return Future.failedFuture(
                                new RestValidationException("Request validation failed", List.of(detail)));
                    }
                    return Future.succeededFuture();
                });
            });
        }

        /**
         * Validates each physical upload at most once, in request order. Exact case-sensitive named
         * constraints take precedence; the aggregate constraint applies only when no named constraint
         * matches. Occurrence pointers count every earlier physical upload with the same part name,
         * including conforming ones. Size is checked before declared content type.
         *
         * @param uploads  the frozen physical-upload snapshot
         * @param failures the accumulating failure list
         */
        private void validateFileParts(List<ApplicableUpload> uploads, List<ValidationErrorDetail> failures) {
            for (ApplicableUpload applicable : uploads) {
                FileUpload upload = applicable.part();
                PreparedFilePart filePart = namedFileDescriptors.get(upload.name());
                if (filePart == null || !filePart.constrained()) {
                    filePart = aggregateFileDescriptor;
                }
                if (filePart == null || !filePart.constrained()) {
                    continue;
                }

                FilePartDescriptor descriptor = filePart.descriptor();
                if (descriptor.maxSizeBytes() >= 0 && upload.size() > descriptor.maxSizeBytes()) {
                    failures.add(new ValidationErrorDetail(
                            applicable.pointer(),
                            "file part exceeds the maximum allowed size",
                            "file",
                            "fileMaxSize",
                            Map.of("maxSizeBytes", descriptor.maxSizeBytes())));
                    if (failFast) {
                        return;
                    }
                }

                if (!descriptor.allowedTypes().isEmpty()) {
                    validateDeclaredContentType(upload.contentType(), applicable.pointer(), filePart, failures);
                    if (failFast && !failures.isEmpty()) {
                        return;
                    }
                }
            }
        }

        /**
         * Classifies one constrained upload's declared content type without exposing the submitted
         * header value. Missing, malformed/wildcard, and concrete-but-incompatible declarations map to
         * the frozen file validation errors.
         */
        private static void validateDeclaredContentType(
                String rawContentType,
                String pointer,
                PreparedFilePart filePart,
                List<ValidationErrorDetail> failures) {
            List<String> allowedTypes = filePart.descriptor().allowedTypes();
            if (rawContentType == null || rawContentType.isBlank()) {
                failures.add(new ValidationErrorDetail(
                        pointer,
                        "file part declares no content type",
                        "file",
                        "fileContentTypeMissing",
                        Map.of("allowedTypes", allowedTypes)));
                return;
            }

            MediaType declared = parseStrictDeclaredMediaType(rawContentType);
            if (declared == null || declared.isWildcardType() || declared.isWildcardSubtype()) {
                failures.add(new ValidationErrorDetail(
                        pointer,
                        "file part declares a malformed or wildcard content type",
                        "file",
                        "fileContentTypeMalformed",
                        Map.of("allowedTypes", allowedTypes)));
                return;
            }

            if (!isAllowedDeclaredType(declared, filePart.allowedTypes())) {
                failures.add(new ValidationErrorDetail(
                        pointer,
                        "file part content type is not allowed",
                        "file",
                        "fileContentTypeNotAllowed",
                        Map.of("allowedTypes", allowedTypes)));
            }
        }

        /**
         * Strictly parses one client-declared multipart media type. The shared {@link MediaType}
         * parser is intentionally lenient for content negotiation, so this trust boundary validates
         * the complete declaration before constructing the value used for compatibility matching.
         */
        private static MediaType parseStrictDeclaredMediaType(String raw) {
            return new StrictMediaTypeParser(raw).parse();
        }

        /**
         * Returns whether a concrete client-declared media type satisfies one of the configured
         * allowed types. The client side must be concrete so symmetric wildcard compatibility cannot
         * turn a declared {@code type/*} or {@code *}{@code /*} into an accepted upload.
         *
         * @param declared    the parsed client-declared media type
         * @param allowedTypes the descriptor's parsed allowed media types
         * @return {@code true} when an allowed type directionally matches the declared type
         */
        private static boolean isAllowedDeclaredType(MediaType declared, List<MediaType> allowedTypes) {
            for (MediaType allowed : allowedTypes) {
                if (allowed.isCompatible(declared)) {
                    return true;
                }
            }
            return false;
        }

        /** Minimal RFC 9110 media-type parser with complete parameter-syntax validation. */
        private static final class StrictMediaTypeParser {

            private final String raw;
            private int offset;

            private StrictMediaTypeParser(String raw) {
                this.raw = raw;
            }

            private MediaType parse() {
                skipOptionalWhitespace();
                String type = readToken();
                if (type == null || !consume('/')) {
                    return null;
                }
                String subtype = readToken();
                if (subtype == null) {
                    return null;
                }

                skipOptionalWhitespace();
                while (offset < raw.length()) {
                    if (!consume(';')) {
                        return null;
                    }
                    skipOptionalWhitespace();
                    if (offset == raw.length() || raw.charAt(offset) == ';') {
                        continue;
                    }
                    if (readToken() == null) {
                        return null;
                    }
                    if (!consume('=')) {
                        return null;
                    }
                    if (!readParameterValue()) {
                        return null;
                    }
                    skipOptionalWhitespace();
                }
                return new MediaType(type, subtype, Map.of(), 1.0);
            }

            private String readToken() {
                int start = offset;
                while (offset < raw.length() && isTokenCharacter(raw.charAt(offset))) {
                    offset++;
                }
                return offset == start ? null : raw.substring(start, offset);
            }

            private boolean readParameterValue() {
                if (offset >= raw.length()) {
                    return false;
                }
                if (raw.charAt(offset) != '"') {
                    return readToken() != null;
                }

                offset++;
                while (offset < raw.length()) {
                    char current = raw.charAt(offset++);
                    if (current == '"') {
                        return true;
                    }
                    if (current == '\\') {
                        if (offset >= raw.length() || !isQuotedCharacter(raw.charAt(offset++))) {
                            return false;
                        }
                    } else if (!isQuotedCharacter(current)) {
                        return false;
                    }
                }
                return false;
            }

            private boolean consume(char expected) {
                if (offset >= raw.length() || raw.charAt(offset) != expected) {
                    return false;
                }
                offset++;
                return true;
            }

            private void skipOptionalWhitespace() {
                while (offset < raw.length()) {
                    char current = raw.charAt(offset);
                    if (current != ' ' && current != '\t') {
                        return;
                    }
                    offset++;
                }
            }

            private static boolean isTokenCharacter(char value) {
                return (value >= 'a' && value <= 'z')
                        || (value >= 'A' && value <= 'Z')
                        || (value >= '0' && value <= '9')
                        || "!#$%&'*+-.^_`|~".indexOf(value) >= 0;
            }

            private static boolean isQuotedCharacter(char value) {
                return value == '\t' || (value >= 0x20 && value <= 0xFF && value != 0x7F);
            }
        }

        /** One physical upload and its frozen request-occurrence pointer. */
        private record ApplicableUpload(FileUpload part, String pointer) {}

        /** One file-part declaration with its allowed types parsed once when the gate is built. */
        private record PreparedFilePart(FilePartDescriptor descriptor, List<MediaType> allowedTypes) {

            private static PreparedFilePart from(FilePartDescriptor descriptor) {
                return new PreparedFilePart(
                        descriptor,
                        descriptor.allowedTypes().stream().map(MediaType::parse).toList());
            }

            private boolean constrained() {
                return descriptor.constrained();
            }
        }

        /** Keeps the last constrained declaration while preserving unconstrained eligibility. */
        private static PreparedFilePart preferConstrained(PreparedFilePart existing, PreparedFilePart candidate) {
            return existing == null || candidate.constrained() ? candidate : existing;
        }

        /**
         * Validates the request body, when a body validator is present, against the shared
         * {@link BoundRequest}'s underlying JSON value. The bound request is obtained from the routing
         * context, binding and stashing one if absent, so the body buffer is read once for both the gate
         * and downstream dispatch.
         *
         * @param ctx      the routing context
         * @param failures the accumulating failure list
         */
        private void validateBody(RoutingContext ctx, List<ValidationErrorDetail> failures) {
            if (bodyValidator == null) {
                return;
            }
            BoundRequest bound = ctx.get(BoundRequest.KEY_META_DATA_BOUND_REQUEST);
            if (bound == null) {
                bound = new DefaultBoundRequest(ctx, op, paramConversionResolver);
                ctx.put(BoundRequest.KEY_META_DATA_BOUND_REQUEST, bound);
            }
            Object instance = bound.body().get();
            collectFailures(bodyValidator.validate(instance), "body", null, bodySchema, failures, failFast);
        }

        /**
         * Validates each declared parameter that has a validator against its raw request value, coerced
         * leniently to the declared scalar type. Reads raw values directly from the routing context so a
         * non-coercible value yields a schema {@code type} violation rather than a binding exception.
         *
         * <p>In fail-fast mode, stops validating further parameters as soon as the first violation is
         * found. In aggregate mode, continues through all parameters.
         *
         * @param ctx      the routing context
         * @param failures the accumulating failure list
         */
        private void validateParams(RoutingContext ctx, List<ValidationErrorDetail> failures) {
            for (ParamValidator pv : paramValidators) {
                if (pv.componentType() != null) {
                    // Collection parameter (List<T>/Set<T>/array): validate ALL request values as a JSON
                    // array, coercing each element to the component type, so the array schema matches the
                    // multi-valued request rather than only its first value.
                    List<String> all = allValues(ctx, pv.location(), pv.name());
                    if (all.isEmpty()) {
                        continue;
                    }
                    io.vertx.core.json.JsonArray array = new io.vertx.core.json.JsonArray();
                    for (String value : all) {
                        array.add(lenientCoerce(value, pv.componentType()));
                    }
                    collectFailures(
                            pv.validator().validate(array),
                            locationToken(pv.location()),
                            pv.name(),
                            pv.schema(),
                            failures,
                            failFast);
                    if (failFast && !failures.isEmpty()) {
                        return;
                    }
                    continue;
                }
                String raw = rawValue(ctx, pv.location(), pv.name());
                if (raw == null) {
                    continue;
                }
                Object instance = lenientCoerce(raw, pv.type());
                collectFailures(
                        pv.validator().validate(instance),
                        locationToken(pv.location()),
                        pv.name(),
                        pv.schema(),
                        failures,
                        failFast);
                if (failFast && !failures.isEmpty()) {
                    return;
                }
            }
        }

        /**
         * Reads <em>all</em> raw values for a declared multi-valued parameter from the routing context,
         * honoring case-insensitive header/cookie lookup. Path parameters are single-valued.
         *
         * @param ctx      the routing context
         * @param location the parameter location
         * @param name     the declared parameter name
         * @return all raw string values (possibly empty), never {@code null}
         */
        private static List<String> allValues(RoutingContext ctx, ParamLocation location, String name) {
            return switch (location) {
                case PATH -> {
                    String v = ctx.pathParams().get(name);
                    yield v == null ? List.of() : List.of(v);
                }
                case QUERY -> ctx.queryParams().getAll(name);
                case HEADER -> ctx.request().headers().getAll(name);
                case COOKIE -> {
                    String v = cookieValue(ctx, name);
                    yield v == null ? List.of() : List.of(v);
                }
                case FORM -> ctx.request().formAttributes().getAll(name);
            };
        }

        /**
         * Reads the raw (un-coerced) first value for a declared parameter directly from the routing
         * context, honoring case-insensitive header/cookie lookup.
         *
         * @param ctx      the routing context
         * @param location the parameter location
         * @param name     the declared parameter name
         * @return the raw string value, or {@code null} when the parameter is absent from the request
         */
        private static String rawValue(RoutingContext ctx, ParamLocation location, String name) {
            return switch (location) {
                case PATH -> ctx.pathParams().get(name);
                case QUERY -> firstOf(ctx.queryParams(), name);
                case HEADER -> firstOf(ctx.request().headers(), name);
                case COOKIE -> cookieValue(ctx, name);
                case FORM -> firstOf(ctx.request().formAttributes(), name);
            };
        }

        /**
         * Returns the first value for {@code name} from a {@link MultiMap}, or {@code null}. Header and
         * query {@link MultiMap}s created by Vert.x are case-insensitive, so the declared name matches
         * regardless of request casing.
         *
         * @param source the multi-map (query, headers, or form attributes)
         * @param name   the parameter name
         * @return the first value, or {@code null} when absent
         */
        private static String firstOf(MultiMap source, String name) {
            return source == null ? null : source.get(name);
        }

        /**
         * Returns the value of the named cookie (case-insensitive), or {@code null} when absent.
         *
         * @param ctx  the routing context
         * @param name the cookie name
         * @return the cookie value, or {@code null}
         */
        private static String cookieValue(RoutingContext ctx, String name) {
            Set<Cookie> cookies = ctx.request().cookies();
            if (cookies == null) {
                return null;
            }
            for (Cookie cookie : cookies) {
                if (cookie.getName().equalsIgnoreCase(name)) {
                    return cookie.getValue();
                }
            }
            return null;
        }

        /**
         * Translates a vertx-json-schema {@link OutputUnit} result into {@link ValidationErrorDetail}
         * entries, appending one per reported error. Each entry is enriched with the failed keyword as
         * {@code type} and the expected constraint value as {@code args} (FR-010).
         *
         * <p>In vertx-json-schema Basic output format, intermediate structural errors (e.g.
         * "Property does not match schema" at keyword {@code properties}) are emitted alongside the
         * concrete constraint violations. This method skips those structural wrapper entries so each
         * {@link ValidationErrorDetail} refers to a concrete violated constraint keyword.
         *
         * <p>When a keyword and constraint args are successfully resolved, a safe canonical
         * {@code detail} message is generated from the keyword and constraint value — the raw
         * vertx-json-schema error message is intentionally not used because it may echo the submitted
         * request value (e.g. "500 is greater than 100"). For keywords without resolved args, the raw
         * message is used as a fallback.
         *
         * <p>When {@code failFast} is {@code true}, only the first non-structural error is added to
         * {@code failures} and then this method returns immediately.
         *
         * @param result       the validation result
         * @param location     the location token for the error ({@code body}, {@code query}, etc.)
         * @param fallbackPath a fallback {@code path} (the parameter name) used when the error reports a
         *                     root instance location; {@code null} for body errors
         * @param schema       the schema {@link JsonObject} used for constraint-value resolution; may be
         *                     {@code null}
         * @param failures     the accumulating failure list
         * @param failFast     when {@code true}, stops after the first non-structural error is added
         */
        private static void collectFailures(
                OutputUnit result,
                String location,
                String fallbackPath,
                JsonObject schema,
                List<ValidationErrorDetail> failures,
                boolean failFast) {
            if (result.getValid() != null && result.getValid()) {
                return;
            }
            List<OutputUnit> errors = result.getErrors();
            if (errors == null || errors.isEmpty()) {
                // Single-error result (e.g. scalar param validated directly)
                String keyword = extractKeywordFor(result);
                // Skip structural wrapper errors (keyword is null and the location names a structural keyword)
                if (keyword != null || !isStructuralError(result.getKeywordLocation())) {
                    Map<String, Object> args = resolveConstraintArgs(keyword, result.getKeywordLocation(), schema);
                    String detail = safeDetail(keyword, args, result.getError());
                    failures.add(new ValidationErrorDetail(
                            pathFor(null, fallbackPath), detail, location, keyword, args.isEmpty() ? null : args));
                }
                return;
            }
            for (OutputUnit error : errors) {
                String keyword = extractKeywordFor(error);
                // Skip structural traversal wrapper errors (e.g. "#/properties" intermediate error)
                if (keyword == null && isStructuralError(error.getKeywordLocation())) {
                    continue;
                }
                Map<String, Object> args = resolveConstraintArgs(keyword, error.getKeywordLocation(), schema);
                String detail = safeDetail(keyword, args, error.getError());
                failures.add(new ValidationErrorDetail(
                        pathFor(error.getInstanceLocation(), fallbackPath),
                        detail,
                        location,
                        keyword,
                        args.isEmpty() ? null : args));
                if (failFast) {
                    return;
                }
            }
        }

        /**
         * Produces a safe {@code detail} message that does not echo the submitted request value.
         *
         * <p>When the keyword and constraint args are successfully resolved, a canonical constraint
         * description is generated using only the keyword and its expected constraint value (e.g.
         * {@code "must have a minimum length of 3"} for {@code minLength: 3}). This ensures the raw
         * vertx-json-schema error message — which may echo the submitted value (e.g.
         * {@code "500 is greater than 100"}) — is never used for enriched keywords.
         *
         * <p>For keywords without resolved args (where args contain only {@code {keyword: true}}),
         * the raw error message is used as a fallback since no submitted-value risk is evident.
         *
         * @param keyword    the failed keyword, or {@code null} when not resolvable
         * @param args       the resolved constraint args map (empty when keyword is unknown)
         * @param rawMessage the raw error message from vertx-json-schema; used as fallback
         * @return a safe detail message
         */
        private static String safeDetail(String keyword, Map<String, Object> args, String rawMessage) {
            if (keyword != null && args.containsKey(keyword)) {
                Object constraintValue = args.get(keyword);
                // Generate a canonical safe message from the keyword and constraint value
                return switch (keyword) {
                    case "minLength" -> "must have a minimum length of " + constraintValue;
                    case "maxLength" -> "must have a maximum length of " + constraintValue;
                    case "minimum" -> "must be at least " + constraintValue;
                    case "maximum" -> "must be at most " + constraintValue;
                    case "exclusiveMinimum" -> "must be greater than " + constraintValue;
                    case "exclusiveMaximum" -> "must be less than " + constraintValue;
                    case "minItems" -> "must have at least " + constraintValue + " items";
                    case "maxItems" -> "must have at most " + constraintValue + " items";
                    case "minProperties" -> "must have at least " + constraintValue + " properties";
                    case "maxProperties" -> "must have at most " + constraintValue + " properties";
                    case "multipleOf" -> "must be a multiple of " + constraintValue;
                    case "pattern" -> "must match pattern: " + constraintValue;
                    case "required" -> rawMessage != null ? rawMessage : "is missing a required field";
                    case "type" -> "must be of type: " + constraintValue;
                    default -> rawMessage != null ? rawMessage : keyword + " constraint violated";
                };
            }
            // For keywords with boolean fallback args or no args, use the raw message
            return rawMessage != null
                    ? rawMessage
                    : (keyword != null ? keyword + " constraint violated" : "is invalid");
        }

        /**
         * Extracts the failed keyword from an {@link OutputUnit}'s keyword location, returning
         * {@code null} when the location is absent or when the last segment is any structural
         * traversal keyword (including {@code additionalProperties}, which this strategy treats
         * as a structural wrapper).
         *
         * <p>Uses {@link SchemaErrorKeywords#extractKeyword(String)} to obtain the last segment,
         * then checks membership in {@link SchemaErrorKeywords#STRUCTURAL_KEYWORDS} (which
         * includes {@code additionalProperties}).
         *
         * @param error the {@link OutputUnit} error entry
         * @return the failed keyword (e.g. {@code "minLength"}), or {@code null} when the
         *     keyword location is absent or names a structural traversal keyword
         */
        private static String extractKeywordFor(OutputUnit error) {
            String segment = SchemaErrorKeywords.extractKeyword(error.getKeywordLocation());
            if (segment == null) {
                return null;
            }
            // Treat ALL structural keywords — including additionalProperties — as wrappers in
            // this strategy, so no concrete constraint detail is fabricated for them.
            return SchemaErrorKeywords.STRUCTURAL_KEYWORDS.contains(segment) ? null : segment;
        }

        /**
         * Returns {@code true} when the keyword location's last segment is any structural traversal
         * keyword (including {@code additionalProperties}). Structural errors are intermediate
         * wrappers emitted by vertx-json-schema Basic output and carry no actionable constraint
         * information for the client.
         *
         * @param keywordLocation the {@link OutputUnit} keyword location to classify
         * @return {@code true} when the last segment is a member of
         *     {@link SchemaErrorKeywords#STRUCTURAL_KEYWORDS}
         */
        private static boolean isStructuralError(String keywordLocation) {
            String segment = SchemaErrorKeywords.extractKeyword(keywordLocation);
            return segment != null && SchemaErrorKeywords.STRUCTURAL_KEYWORDS.contains(segment);
        }

        /**
         * Chooses the {@code path} for an error: the schema instance location when it points inside the
         * instance, otherwise the parameter-name fallback (for a root-level scalar parameter violation
         * the instance location is the document root, e.g. {@code "#"} or {@code ""}).
         *
         * @param instanceLocation the schema error's instance location, possibly {@code null}
         * @param fallbackPath     the parameter-name fallback, or {@code null} for a body error
         * @return the chosen path string
         */
        private static String pathFor(String instanceLocation, String fallbackPath) {
            boolean rootLocation = instanceLocation == null
                    || instanceLocation.isEmpty()
                    || "#".equals(instanceLocation)
                    || "/".equals(instanceLocation);
            if (rootLocation) {
                return fallbackPath != null ? fallbackPath : (instanceLocation != null ? instanceLocation : "");
            }
            return instanceLocation;
        }
    }
}
