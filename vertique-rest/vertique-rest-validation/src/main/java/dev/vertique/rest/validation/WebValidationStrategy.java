// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import dev.vertique.core.async.Combinators;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.json.schema.validation.JsonSchemaValidationOptions;
import dev.vertique.json.schema.validation.PathForm;
import dev.vertique.json.schema.validation.PatternCompileFailure;
import dev.vertique.json.schema.validation.PatternInputGuard;
import dev.vertique.json.schema.validation.SchemaPatterns;
import dev.vertique.json.schema.validation.ViolationDetails;
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
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.Cookie;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.Validator;
import jakarta.annotation.Nullable;
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
import java.util.concurrent.TimeUnit;

/**
 * The {@code web-validation} {@link RequestValidationStrategy}: validates each request body and
 * declared parameter against vertx-json-schema validators synthesized from the operation's
 * {@link OperationSchemas}, and validates declared file-part constraints. It is the default
 * request-validation strategy.
 *
 * <p><strong>Validators are built once, at {@code gateFor} time (startup).</strong> For
 * efficiency the body validator and each per-parameter validator are compiled when the gate is
 * produced and closed over by the returned handler, so no schema is compiled on the request hot path.
 * Each is compiled from a private copy of its schema; the objects {@link OperationSchemas} holds are
 * never compiled or modified.
 *
 * <p><strong>Regular expressions are compiled once, at the same point.</strong> Beside the body
 * validator's compilation, {@link #gateFor(JaxRsOperationDescriptor, OperationSchemas)} runs the shared
 * pattern walk, {@link SchemaPatterns#precompile(JsonObject)}, over the whole body document: it compiles
 * every string-valued member keyed {@code pattern} and every key of every object keyed
 * {@code patternProperties}, at any depth and at any position. A pattern the regex engine rejects fails
 * router construction with a {@link RestConfigurationException} naming the operation, the JSON pointer,
 * and the engine's own description and index, taken from the first failure the walk reports — never the
 * pattern text itself, and never the engine's exception, which quotes that text. The pointer withholds a
 * {@code patternProperties} key whether or not that key is the failing one, naming its ordinal
 * instead, because a key is a regular expression however well it compiles. A schema this strategy
 * never gates keeps its unparseable pattern: the check lives here and nowhere else.
 *
 * <p><strong>Pattern input is bounded.</strong> A string value or object key that reaches a
 * {@code pattern}, {@code patternProperties}, or pattern-bearing {@code propertyNames} position, or an
 * {@code idn-hostname}, {@code idn-email}, or {@code regex} format, is rejected before that check runs
 * when it is longer than {@link JaxRsConfig#validationPatternMaxChars()} UTF-16 code units, and a request
 * is rejected once the strings reaching such positions exceed {@link
 * JaxRsConfig#validationPatternMaxTotalChars()} in total, a string checked at two positions counting
 * twice. No other format is bounded or counted. Either rejection is one value-free detail of type
 * {@code patternInputLength} or {@code patternInputTotalLength}, whose {@code args} name only the
 * configured limit, and validation of the request stops there in both modes. The bound is enforced by
 * {@code PatternInputGuard}, whose entries each private copy carries; an input within both limits gets
 * exactly the verdict and violations it would get without them.
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
 * <p><strong>Enrichment.</strong> Each {@link ValidationErrorDetail} carries:
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
 * <p><strong>Validation mode.</strong> The {@link JaxRsConfig#validationMode()} field
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

    /** The message bound, applied at this module's boundary, in UTF-16 code units. */
    private static final int MAX_MESSAGE_LENGTH = 512;

    /** Marker appended in place of the elided tail of a truncated description. */
    private static final String ELLIPSIS = "...";

    private static final JsonSchemaOptions SCHEMA_OPTIONS = JsonSchemaValidationOptions.draft202012();

    /** The {@code aggregate} validation-mode literal: collect all violations before failing. */
    private static final String MODE_AGGREGATE = "aggregate";

    /** The {@code failFast} validation-mode literal: stop at the first violation. */
    private static final String MODE_FAIL_FAST = "failFast";

    private final boolean failFast;

    /**
     * The framework conversion resolver, passed into every {@link DefaultBoundRequest} this strategy's
     * gate constructs. The binder stores declared scalars as raw strings; parameter conversion
     * happens in {@code ParameterExtractor}.
     */
    private final ParamConversionResolver paramConversionResolver;

    /** Bound file-content verifiers, sorted once in the framework extension order. */
    private final List<FileContentVerifier> fileContentVerifiers;

    /**
     * Per-verifier wait deadline in milliseconds, from {@link
     * JaxRsConfig#fileContentVerifierDeadlineMs()}. Applied to every verifier future; validated
     * positive at the {@code JaxRsConfig} provider.
     */
    private final long fileContentVerifierDeadlineMs;

    /**
     * The pattern-input guard every body and parameter validator is compiled with, carrying the configured
     * {@link JaxRsConfig#validationPatternMaxChars()} and {@link JaxRsConfig#validationPatternMaxTotalChars()}
     * limits. The rest-core {@code JaxRsConfig} provider has already validated both at startup.
     */
    private final PatternInputGuard patternInputGuard;

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
     * <p>The pattern-input limits {@link JaxRsConfig#validationPatternMaxChars()} and {@link
     * JaxRsConfig#validationPatternMaxTotalChars()}, and the per-verifier wait deadline {@link
     * JaxRsConfig#fileContentVerifierDeadlineMs()}, are read here too, once; they are validated where
     * {@link JaxRsConfig} is provided, not again here.
     *
     * @param config the JAX-RS runtime configuration; {@link JaxRsConfig#validationMode()} selects
     *     either {@code "aggregate"} (collect all violations) or {@code "failFast"} (stop at first
     *     violation), the two pattern-input limits bound pattern and bounded-format input, and {@link
     *     JaxRsConfig#fileContentVerifierDeadlineMs()} bounds each {@link FileContentVerifier} wait
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
        this.fileContentVerifierDeadlineMs = config.fileContentVerifierDeadlineMs();
        this.patternInputGuard =
                new PatternInputGuard(config.validationPatternMaxChars(), config.validationPatternMaxTotalChars());
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

    /**
     * {@inheritDoc}
     *
     * <p>Compiles the body and parameter validators, and — beside the body validator — every regular
     * expression the body document declares, so an unparseable pattern fails the router build rather
     * than the first request that reaches the route. Each validator is compiled from a private copy the
     * pattern-input guard rewrites; the schemas {@code schemas} holds are never compiled or modified, and
     * the shared pattern walk and constraint-argument resolution read those originals.
     *
     * @param op      the operation to gate
     * @param schemas the operation's synthesized schemas
     * @return the gate handler, or {@link Optional#empty()} when the operation has nothing to validate
     * @throws RestConfigurationException if the body document declares a pattern the regex engine
     *     rejects
     */
    @Override
    public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
        Validator bodyValidator = schemas.bodySchema().map(this::compile).orElse(null);
        JsonObject bodySchema = schemas.bodySchema().orElse(null);
        if (bodySchema != null) {
            List<PatternCompileFailure> failures = SchemaPatterns.precompile(bodySchema);
            if (!failures.isEmpty()) {
                PatternCompileFailure first = failures.get(0);
                throw new RestConfigurationException(
                        patternFailure(op.operationId(), first.pointer(), first.description(), first.index()));
            }
        }
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
                fileContentVerifierDeadlineMs,
                failFast,
                paramConversionResolver,
                patternInputGuard));
    }

    /**
     * Compiles a vertx-json-schema {@link Validator} for a schema {@link JsonObject} using the
     * spike-proven DRAFT 2020-12 options: the pattern-input guard rewrites a private copy of
     * {@code schema} and compiles that copy with itself as the format validator, so {@code schema}
     * itself is never compiled or modified.
     *
     * @param schema the shared schema to compile a guarded copy of
     * @return a reusable validator
     */
    private Validator compile(JsonObject schema) {
        return patternInputGuard.compile(schema, SCHEMA_OPTIONS);
    }

    /**
     * Converts a pattern-input guard exception, thrown directly or as the cause of the thrown exception, into
     * the one value-free detail of the rejected validator call: {@code pointer} {@code ""} for the body and
     * {@code parameter} with the parameter name otherwise; the location token; {@code type} {@code
     * patternInputLength} or {@code patternInputTotalLength}; the fixed detail naming the configured limit; and
     * {@code args} {@code {"maxChars": N}} or {@code {"maxTotalChars": N}}. The value, the key, and the pattern
     * never appear.
     *
     * <p>The type, the message and the limit come from the shared {@link
     * ViolationDetails#patternInputBound(Throwable)}; the location member and the argument name are the
     * response shape this strategy has always reported.
     *
     * @param thrown        the exception a validator call threw
     * @param location      the call's location token ({@code body}, {@code path}, {@code query}, {@code
     *     header}, {@code cookie}, or {@code form})
     * @param parameterName the parameter name, or {@code null} for the body
     * @return the detail, or {@link Optional#empty()} when neither {@code thrown} nor its cause is a guard
     *     exception
     */
    static Optional<ValidationErrorDetail> patternInputBoundDetail(
            Throwable thrown, String location, @Nullable String parameterName) {
        return ViolationDetails.patternInputBound(thrown).map(detail -> {
            Object limit = detail.args().get("limit");
            String argumentName =
                    ViolationDetails.PATTERN_INPUT_LENGTH_TYPE.equals(detail.type()) ? "maxChars" : "maxTotalChars";
            return new ValidationErrorDetail(
                    parameterName == null ? "" : null,
                    parameterName,
                    detail.message(),
                    location,
                    detail.type(),
                    Map.of(argumentName, limit));
        });
    }

    /**
     * Names the parameter a violation inside a parameter's value belongs to: the declared name followed by
     * {@code [n]} for each leading segment of the violation's pointer that is an array index, and nothing
     * after the first segment that is not one. A member name inside an object-valued parameter is never
     * rendered, because the client chooses it.
     *
     * @param name    the declared parameter name
     * @param pointer the violation's location inside the value as an RFC 6901 pointer; {@code ""} for the
     *     value itself
     * @return the parameter name, for example {@code ids} or {@code ids[1]}
     */
    static String parameterName(String name, String pointer) {
        StringBuilder parameter = new StringBuilder(name);
        int start = 0;
        while (start < pointer.length() && pointer.charAt(start) == '/') {
            int end = pointer.indexOf('/', start + 1);
            String segment = pointer.substring(start + 1, end < 0 ? pointer.length() : end);
            if (!isArrayIndex(segment)) {
                break;
            }
            parameter.append('[').append(segment).append(']');
            if (end < 0) {
                break;
            }
            start = end;
        }
        return parameter.toString();
    }

    /** Whether a segment is the canonical spelling of an array index: {@code 0}, or digits with no leading zero. */
    private static boolean isArrayIndex(String segment) {
        if (segment.isEmpty() || (segment.length() > 1 && segment.charAt(0) == '0')) {
            return false;
        }
        for (int index = 0; index < segment.length(); index++) {
            char c = segment.charAt(index);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    // --- Regex precompilation diagnostic ---

    /**
     * Assembles the bounded diagnostic for an unparseable pattern.
     *
     * <p>The operation, the pointer, and the index precede the engine's description and always
     * survive; only the description is elided, because the engine bounds the token it quotes by the
     * pattern's own length alone. The assembled message is at most {@value #MAX_MESSAGE_LENGTH} UTF-16
     * code units, and an elision never splits a surrogate pair. JSON-005's own bounding helper is
     * package-private to {@code vertique-json-schema}, so the numeric bound is applied here instead.
     *
     * <p>The wording carries no parenthesis on purpose: a one-character pattern such as an unclosed
     * group is disclosed by any parenthesis in this text, so the proof that the pattern never leaks is
     * mechanically the absence of that character.
     *
     * @param operationId the operation whose body document declares the pattern
     * @param pointer     the JSON pointer of the position that declares it
     * @param description the engine's description of the syntax error
     * @param index       the engine's index of the syntax error within the pattern
     * @return the bounded, pattern-free diagnostic
     */
    private static String patternFailure(String operationId, String pointer, String description, int index) {
        String identified = "Unparseable regular expression in the request body schema of operation '" + operationId
                + "' at JSON pointer " + pointer + ", rejected at index " + index + ": ";
        if (identified.length() >= MAX_MESSAGE_LENGTH) {
            // Only the description is elidable, so an identity this long is reported unbounded rather
            // than cut where the pointer or the index would be lost.
            return identified;
        }
        return identified + elide(String.valueOf(description), MAX_MESSAGE_LENGTH - identified.length());
    }

    /**
     * Bounds one fragment to {@code max} UTF-16 code units, marking an elision when there is room for
     * the marker and never splitting a surrogate pair.
     *
     * @param value the fragment to bound
     * @param max   the maximum retained length in UTF-16 code units; always positive here
     * @return the bounded fragment, never longer than {@code max}
     */
    private static String elide(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        if (max < ELLIPSIS.length()) {
            // No room to signal an elision at all, so the fragment is simply cut: the marker stays
            // inside the bound rather than extending past it.
            return value.substring(0, cutPoint(value, max));
        }
        return value.substring(0, cutPoint(value, max - ELLIPSIS.length())) + ELLIPSIS;
    }

    /**
     * Moves a cut point back one code unit when it would land inside a surrogate pair.
     *
     * @param value  the fragment being cut
     * @param length the intended cut length in UTF-16 code units
     * @return the cut length that keeps every surrogate pair whole
     */
    private static int cutPoint(String value, int length) {
        return length > 0 && Character.isHighSurrogate(value.charAt(length - 1)) ? length - 1 : length;
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
     *
     * <p>All of a request's parameter and body validation runs synchronously inside one pattern-input
     * counting window, which closes before any failure is reported, any file part is checked, any
     * file-content verifier runs, or the next handler is called. A pattern-input rejection stops validation
     * of the request in both modes: its one detail follows the details earlier validator calls contributed,
     * and nothing after it runs.
     */
    private static final class GateHandler implements Handler<RoutingContext> {

        private final JaxRsOperationDescriptor op;
        private final Validator bodyValidator;
        private final JsonObject bodySchema;
        private final List<ParamValidator> paramValidators;
        private final Map<String, PreparedFilePart> namedFileDescriptors;
        private final PreparedFilePart aggregateFileDescriptor;
        private final List<FileContentVerifier> fileContentVerifiers;
        private final long fileContentVerifierDeadlineMs;
        private final boolean failFast;
        private final ParamConversionResolver paramConversionResolver;
        private final PatternInputGuard patternInputGuard;

        private GateHandler(
                JaxRsOperationDescriptor op,
                Validator bodyValidator,
                JsonObject bodySchema,
                List<ParamValidator> paramValidators,
                List<FilePartDescriptor> fileParts,
                List<FileContentVerifier> fileContentVerifiers,
                long fileContentVerifierDeadlineMs,
                boolean failFast,
                ParamConversionResolver paramConversionResolver,
                PatternInputGuard patternInputGuard) {
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
            this.fileContentVerifierDeadlineMs = fileContentVerifierDeadlineMs;
            this.failFast = failFast;
            this.paramConversionResolver = paramConversionResolver;
            this.patternInputGuard = patternInputGuard;
        }

        @Override
        public void handle(RoutingContext ctx) {
            List<ValidationErrorDetail> failures = new ArrayList<>();
            boolean stopped;
            PatternInputGuard.Window window = patternInputGuard.openWindow();
            try {
                stopped = validateSchemas(ctx, failures);
            } finally {
                // Closed before anything else runs, so no tally outlives this request's validation.
                window.close();
            }
            if (stopped) {
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
         * and occurrence indexes count all earlier unique uploads with the same part name. An upload
         * whose part name is declared is named by that declared name plus its occurrence index; an
         * upload whose part name is not declared is not named at all, because the name is the
         * client's. The same frozen snapshot feeds synchronous constraints and deep verification.
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
                String parameter = null;
                if (namedFileDescriptors.containsKey(partName)) {
                    parameter = occurrence == 0 ? partName : partName + "[" + occurrence + "]";
                }
                uploads.add(new ApplicableUpload(upload, parameter));
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
                        fileContentVerifiers,
                        (Void) null,
                        (verifier, verifierIgnored) -> verify(verifier, upload, fileContentVerifierDeadlineMs));
            });
        }

        private boolean verifierApplies(ApplicableUpload upload) {
            return namedFileDescriptors.containsKey(upload.part().name()) || aggregateFileDescriptor != null;
        }

        /**
         * Invokes one verifier on the event loop and normalizes every extension outcome into the
         * sequential chain. The leading succeeded future captures synchronous extension throws
         * without changing their cause identity.
         *
         * <p>Each verifier future is raced against {@code deadlineMs} (from {@code
         * jaxrs.fileContentVerifierDeadlineMs}). The bound is <strong>per verifier invocation</strong>,
         * not an overall chain budget: a later verifier gets a fresh deadline after an earlier one
         * completes. When the deadline elapses first the chain fails closed as an infrastructure
         * failure (500); the framework stops waiting but does not cancel verifier-owned work — see
         * {@link FileContentVerifier}'s cancellation contract.
         */
        private static Future<Void> verify(FileContentVerifier verifier, ApplicableUpload upload, long deadlineMs) {
            return Future.<Void>succeededFuture().compose(ignored -> {
                Future<FileVerificationResult> verification = verifier.verify(upload.part());
                if (verification == null) {
                    return Future.failedFuture(new IllegalStateException("FileContentVerifier returned a null Future"));
                }
                // Bound the wait: a non-blocking future that simply never resolves is not a
                // synchronous throw, a null future, a failed future, or a null/rejected result, so
                // none of the existing fail-closed branches catch it. .timeout() races the original
                // future against deadlineMs and forwards it unchanged when it settles first.
                return verification.timeout(deadlineMs, TimeUnit.MILLISECONDS).compose(result -> {
                    if (result == null) {
                        return Future.failedFuture(
                                new IllegalStateException("FileContentVerifier completed with a null result"));
                    }
                    if (result instanceof FileVerificationResult.Rejected rejected) {
                        ValidationErrorDetail detail =
                                fileDetail(upload.parameter(), rejected.detail(), rejected.type(), rejected.args());
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
         * matches. Occurrence indexes count every earlier physical upload with the same part name,
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
                    failures.add(fileDetail(
                            applicable.parameter(),
                            "file part exceeds the maximum allowed size",
                            "fileMaxSize",
                            Map.of("maxSizeBytes", descriptor.maxSizeBytes())));
                    if (failFast) {
                        return;
                    }
                }

                if (!descriptor.allowedTypes().isEmpty()) {
                    validateDeclaredContentType(upload.contentType(), applicable.parameter(), filePart, failures);
                    if (failFast && !failures.isEmpty()) {
                        return;
                    }
                }
            }
        }

        /**
         * Builds one file-part entry: {@code parameter} is the declared part name with its occurrence
         * index, or {@code null} for an upload whose part name is not declared, which then carries
         * no name at all.
         */
        private static ValidationErrorDetail fileDetail(
                @Nullable String parameter, String detail, String type, @Nullable Map<String, Object> args) {
            return new ValidationErrorDetail(null, parameter, detail, "file", type, args);
        }

        /**
         * Classifies one constrained upload's declared content type without exposing the submitted
         * header value. Missing, malformed/wildcard, and concrete-but-incompatible declarations map to
         * the frozen file validation errors.
         */
        private static void validateDeclaredContentType(
                String rawContentType,
                @Nullable String parameter,
                PreparedFilePart filePart,
                List<ValidationErrorDetail> failures) {
            List<String> allowedTypes = filePart.descriptor().allowedTypes();
            if (rawContentType == null || rawContentType.isBlank()) {
                failures.add(fileDetail(
                        parameter,
                        "file part declares no content type",
                        "fileContentTypeMissing",
                        Map.of("allowedTypes", allowedTypes)));
                return;
            }

            MediaType declared = parseStrictDeclaredMediaType(rawContentType);
            if (declared == null || declared.isWildcardType() || declared.isWildcardSubtype()) {
                failures.add(fileDetail(
                        parameter,
                        "file part declares a malformed or wildcard content type",
                        "fileContentTypeMalformed",
                        Map.of("allowedTypes", allowedTypes)));
                return;
            }

            if (!isAllowedDeclaredType(declared, filePart.allowedTypes())) {
                failures.add(fileDetail(
                        parameter,
                        "file part content type is not allowed",
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

        /**
         * One physical upload and its frozen report name: the declared part name with its occurrence
         * index, or {@code null} when the upload's part name is not declared.
         */
        private record ApplicableUpload(
                FileUpload part, @Nullable String parameter) {}

        /** One file-part declaration with its allowed types parsed once when the gate is built. */
        private record PreparedFilePart(FilePartDescriptor descriptor, List<MediaType> allowedTypes) {

            /**
             * Parses the descriptor's allowed types once. An allowed type that does not parse is a
             * configuration error, not request data, so it fails here rather than as a
             * {@code null} met on every upload.
             *
             * @throws RestConfigurationException if a declared allowed type is not a media type
             */
            private static PreparedFilePart from(FilePartDescriptor descriptor) {
                List<MediaType> allowed =
                        new ArrayList<>(descriptor.allowedTypes().size());
                for (String declaredType : descriptor.allowedTypes()) {
                    MediaType parsed = MediaType.parse(declaredType);
                    if (parsed == null) {
                        String part = descriptor.partName() == null
                                ? "the aggregate file parameter"
                                : "file part '" + descriptor.partName() + "'";
                        throw new RestConfigurationException(
                                "Invalid allowed media type '" + declaredType + "' declared for " + part);
                    }
                    allowed.add(parsed);
                }
                return new PreparedFilePart(descriptor, List.copyOf(allowed));
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
         * Validates the declared parameters and then the body, and reports whether validation of the
         * request stops here: after a pattern-input rejection in either mode, or after any violation in
         * {@code failFast} mode.
         *
         * @param ctx      the routing context
         * @param failures the accumulating failure list
         * @return {@code true} when the request fails with {@code failures} and nothing after them runs
         */
        private boolean validateSchemas(RoutingContext ctx, List<ValidationErrorDetail> failures) {
            if (validateParams(ctx, failures) || (failFast && !failures.isEmpty())) {
                return true;
            }
            return validateBody(ctx, failures) || (failFast && !failures.isEmpty());
        }

        /**
         * Validates the request body, when a body validator is present, against the shared
         * {@link BoundRequest}'s underlying JSON value. The bound request is obtained from the routing
         * context, binding and stashing one if absent, so the body buffer is read once for both the gate
         * and downstream dispatch.
         *
         * @param ctx      the routing context
         * @param failures the accumulating failure list
         * @return {@code true} when the pattern-input guard rejected the body, so validation stops
         */
        private boolean validateBody(RoutingContext ctx, List<ValidationErrorDetail> failures) {
            if (bodyValidator == null) {
                return false;
            }
            BoundRequest bound = ctx.get(BoundRequest.KEY_META_DATA_BOUND_REQUEST);
            if (bound == null) {
                bound = new DefaultBoundRequest(ctx, op, paramConversionResolver);
                ctx.put(BoundRequest.KEY_META_DATA_BOUND_REQUEST, bound);
            }
            Object instance = bound.body().get();
            return validateAndCollect(bodyValidator, instance, "body", null, bodySchema, failures);
        }

        /**
         * Runs one validator call and appends the details it reports, one per concrete violation, through
         * the shared violation-detail collector. A pattern-input guard exception, thrown directly or as the cause of the thrown exception, becomes the
         * call's one value-free bound detail instead, appended after the details earlier calls contributed;
         * any other exception propagates unchanged.
         *
         * @param validator    the compiled validator
         * @param instance     the value to validate
         * @param location     the location token for the call's details
         * @param fallbackPath the parameter name, or {@code null} for the body
         * @param schema       the original schema, for constraint-value resolution
         * @param failures     the accumulating failure list
         * @return {@code true} when the pattern-input guard rejected the call, so validation stops
         */
        private boolean validateAndCollect(
                Validator validator,
                Object instance,
                String location,
                String fallbackPath,
                JsonObject schema,
                List<ValidationErrorDetail> failures) {
            OutputUnit result;
            try {
                result = validator.validate(instance);
            } catch (RuntimeException thrown) {
                Optional<ValidationErrorDetail> rejection = patternInputBoundDetail(thrown, location, fallbackPath);
                if (rejection.isEmpty()) {
                    throw thrown;
                }
                failures.add(rejection.get());
                return true;
            }
            for (ViolationDetail detail : ViolationDetails.collect(result, schema, failFast, PathForm.RFC6901)) {
                failures.add(toValidationErrorDetail(detail, location, fallbackPath));
            }
            return false;
        }

        /**
         * Turns one shared violation detail into the response detail for a location: a body violation is
         * reported in {@code pointer}, any other in {@code parameter}.
         *
         * @param detail       the shared detail, with its path as an RFC 6901 pointer
         * @param location     the location token for the error ({@code body}, {@code query}, etc.)
         * @param fallbackPath the parameter name, or {@code null} for a body error
         * @return the response detail
         */
        private static ValidationErrorDetail toValidationErrorDetail(
                ViolationDetail detail, String location, String fallbackPath) {
            return new ValidationErrorDetail(
                    fallbackPath == null ? detail.path() : null,
                    fallbackPath == null ? null : parameterName(fallbackPath, detail.path()),
                    detail.message(),
                    location,
                    detail.type(),
                    detail.args());
        }

        /**
         * Validates each declared parameter that has a validator against its raw request value, coerced
         * leniently to the declared scalar type. Reads raw values directly from the routing context so a
         * non-coercible value yields a schema {@code type} violation rather than a binding exception.
         *
         * <p>In fail-fast mode, stops validating further parameters as soon as the first violation is
         * found. In aggregate mode, continues through all parameters. In both modes, stops at a
         * pattern-input rejection.
         *
         * @param ctx      the routing context
         * @param failures the accumulating failure list
         * @return {@code true} when the pattern-input guard rejected a parameter, so validation stops
         */
        private boolean validateParams(RoutingContext ctx, List<ValidationErrorDetail> failures) {
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
                    if (validateAndCollect(
                            pv.validator(), array, locationToken(pv.location()), pv.name(), pv.schema(), failures)) {
                        return true;
                    }
                    if (failFast && !failures.isEmpty()) {
                        return false;
                    }
                    continue;
                }
                String raw = rawValue(ctx, pv.location(), pv.name());
                if (raw == null) {
                    continue;
                }
                Object instance = lenientCoerce(raw, pv.type());
                if (validateAndCollect(
                        pv.validator(), instance, locationToken(pv.location()), pv.name(), pv.schema(), failures)) {
                    return true;
                }
                if (failFast && !failures.isEmpty()) {
                    return false;
                }
            }
            return false;
        }

        /**
         * Reads <em>all</em> raw values for a declared multi-valued parameter from the routing context,
         * honoring case-insensitive header lookup and exact-name cookie lookup. Path parameters are
         * single-valued.
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
         * context, honoring case-insensitive header lookup and exact-name cookie lookup.
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
         * Returns the value of the cookie with exactly the given name, or {@code null} when absent.
         * Cookie names are case-sensitive (RFC 6265), the same rule the binder applies.
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
                if (cookie.getName().equals(name)) {
                    return cookie.getValue();
                }
            }
            return null;
        }
    }
}
