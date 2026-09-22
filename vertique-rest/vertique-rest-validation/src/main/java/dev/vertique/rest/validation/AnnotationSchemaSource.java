// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import dev.vertique.json.schema.JsonSchemaGenerationException;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.routing.BodyDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.swagger.v3.oas.annotations.media.Schema;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.Validator;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime {@link OperationSchemaSource} that synthesizes JSON schemas from JAX-RS operation
 * annotations (DRAFT 2020-12).
 *
 * <p>Two synthesis paths feed the produced {@link OperationSchemas}:
 *
 * <ul>
 *   <li><strong>Body</strong> — the body type is handed to the transport-neutral
 *       {@link AnnotationJsonSchemaGenerator} built for the operation's <em>effective JSON
 *       profile</em> with {@link AnnotationJsonSchemaGenerator#forInputProfile(JsonMapperProfile)
 *       forInputProfile}, which produces a complete object schema (nested objects, required fields,
 *       constraints) with canonically ordered object keys and the swagger sentinel already removed.
 *       The canonical document is bridged to vertx-json-schema JSON via {@link JsonObject}. There is
 *       no profile-agnostic path and no profile-selection rule in this module: the profile arrives
 *       as an argument, already resolved by the registrar.
 *   <li><strong>Parameters</strong> — the generator introspects types and fields, not loose method
 *       parameters, so each {@link ParamDescriptor} is mapped to a small {@link JsonObject} from its
 *       declared type, optional collection component type, and its constraint annotations
 *       ({@code @Size}, {@code @Pattern}, {@code @Min}/{@code @Max}, Swagger {@code @Schema}). This
 *       path is owned by this module and does not use the shared generator.
 * </ul>
 *
 * <p>The swagger-2 module emits a {@code "default": "##default"} sentinel for unset annotation
 * defaults; that sentinel is stripped recursively from every produced schema.
 *
 * <p>Schemas are synthesized at route registration, so a profile the generator cannot be built for,
 * or a body type it cannot represent, fails router construction with a
 * {@link RestConfigurationException} carrying the operation id and the generator's
 * {@link JsonSchemaGenerationException} as its cause; the mount is never installed. The text this
 * class authors names the operation only — never a schema fragment and never a pattern — while the
 * generator's own message and its preserved third-party cause keep their own bounds. That quoting
 * and that cause are the generator's exception alone: any other runtime exception the synthesis
 * catch admits contributes only its class's simple name, is attached neither as a cause nor as a
 * suppressed exception, and leaves the whole message within FR-JSON-075's bound.
 * Request-validation outcomes and error categories are otherwise unaffected.
 *
 * <p><strong>No per-operation schema cache.</strong> This is a {@link Singleton} shared across every
 * route mount, but duplicate-operationId is enforced only <em>within</em> a single registration (by
 * {@code JaxRsRouteRegistrar.checkDuplicateOperationId}). The registrar calls {@link #schemasFor} once
 * per operation at registration, so an operationId-keyed cache would never dedup within a mount; across
 * mounts it would hand a second operation the first operation's schema — validating against the wrong
 * contract. Re-introducing a cache requires a content-aware or mount-aware key, not the operationId
 * alone. No schema is cached by operation id, Java type, or mapper identity.
 *
 * <p><strong>One generator per profile instance.</strong> Generators, unlike schemas, are cached:
 * each distinct {@link JsonMapperProfile} <em>instance</em> — compared by reference, never by id,
 * mapper, or equality — resolves to at most one {@link AnnotationJsonSchemaGenerator}, built on
 * first use and retained for this source's lifetime. Retention is therefore bounded by the number of
 * distinct instances the profile registry hands out; a registry that returns a fresh instance per
 * call is a misconfiguration, named as such in this module's packaged document. Calls on one
 * generator instance are serialized by the generator itself.
 */
@Singleton
public class AnnotationSchemaSource implements OperationSchemaSource {

    /** Swagger-2 sentinel emitted for an unset {@code @Schema} default value. */
    private static final String DEFAULT_SENTINEL = "##default";

    /** Reads the generator's canonical document back into a {@link JsonNode} for the protected seam. */
    private static final ObjectMapper CANONICAL_READER = new ObjectMapper();

    /** The longest operation identity this class embeds in a diagnostic, in UTF-16 code units. */
    private static final int MAX_OPERATION_IDENTITY_LENGTH = 256;

    /**
     * FR-JSON-075's message bound, in UTF-16 code units. Applied to the whole diagnostic only when
     * this class authors all of it; see {@link #synthesisFailure(String, RuntimeException)}.
     */
    private static final int MAX_MESSAGE_LENGTH = 512;

    /** Marker appended in place of the elided tail of a truncated identity. */
    private static final String ELLIPSIS = "...";

    /**
     * One input-direction generator per distinct profile <em>instance</em>, built on first use.
     *
     * <p>A {@link ConcurrentHashMap} over an identity-wrapping key rather than an
     * {@code IdentityHashMap}: the wrapper supplies the reference-identity keying the contract
     * requires, while the concurrent map supplies atomic compute-if-absent semantics that serialize
     * mapping functions <em>per key</em> instead of across the whole map, so concurrent router builds
     * for different profiles neither block one another nor construct a generator twice.
     */
    private final Map<ProfileKey, AnnotationJsonSchemaGenerator> generatorsByProfile = new ConcurrentHashMap<>();

    /**
     * Counts generator constructions, incremented inside the cache's mapping function at the
     * construction site. Atomic because that mapping function is serialized per key and this source
     * may be driven by several concurrent router builds at once: a lost update would read low and
     * mask the very duplicate construction the count exists to detect.
     */
    private final AtomicInteger generatorConstructions = new AtomicInteger();

    /**
     * The optional application-bound {@link Validator}: present when an application depends on
     * {@code vertique-validation} (or binds its own {@code Validator}), absent otherwise. When
     * present, every generator this source builds additionally sources its value-schema constraints
     * from Bean Validation metadata — the annotation walk still runs first, as the floor every
     * generator carries, and the metadata source only supplements or, for a bounded set of shapes,
     * corrects it; see {@code ConstraintSource} in {@code vertique-json-schema}.
     */
    private final Optional<Validator> validator;

    /**
     * Creates a schema source with an empty generator cache and no Bean Validation validator; every
     * generator this source builds uses the annotation walk. Equivalent to
     * {@code AnnotationSchemaSource(Optional.empty())}, kept for source compatibility with callers
     * that construct this class directly rather than through Dagger.
     */
    public AnnotationSchemaSource() {
        this(Optional.empty());
    }

    /**
     * Creates a schema source with an empty generator cache; generators are built on first use.
     *
     * @param validator the application's optional Bean Validation validator, declared
     *                  {@code @BindsOptionalOf} in {@link RestValidationModule}
     */
    @Inject
    public AnnotationSchemaSource(Optional<Validator> validator) {
        this.validator = validator;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The body schema is generated through {@code profile}'s own input-direction generator, so it
     * describes the wire shape that profile's mapper actually accepts. Parameter schemas are
     * synthesized from the declared Java types and constraint annotations alone and never receive the
     * profile.
     */
    @Override
    public OperationSchemas schemasFor(JaxRsOperationDescriptor op, JsonMapperProfile profile) {
        return synthesize(op, profile);
    }

    /**
     * Synthesizes the body and parameter schemas for the operation. The registrar invokes this once
     * per operation at registration; there is deliberately no cache (see the class javadoc), so each
     * distinct operation — even one sharing an operationId with an operation on another mount — gets
     * its own correct schema.
     *
     * @param op      the operation descriptor whose body and parameters are introspected
     * @param profile the operation's effective JSON profile, used for the body path only
     * @return the freshly synthesized schemas
     */
    private OperationSchemas synthesize(JaxRsOperationDescriptor op, JsonMapperProfile profile) {
        OperationSchemas.Builder schemas = OperationSchemas.builder();

        op.body().ifPresent(body -> schemas.bodySchema(synthesizeBody(op.operationId(), body, profile)));

        for (ParamDescriptor param : op.parameters()) {
            schemas.parameterSchema(param.location(), param.name(), synthesizeParam(param));
        }

        return schemas.build();
    }

    // --- Body path (profiled generator) ---

    /**
     * Generates the body schema through the profile's generator and strips the swagger-2 sentinel. The
     * full generic type is passed when present (e.g. {@code List<MyDto>}) so the element type is
     * resolved and an array-of-{@code MyDto} schema is produced rather than a raw-{@code List}
     * schema; otherwise the raw class is used.
     *
     * <p>Every failure of this path — building the profile's generator, generating, or reading the
     * produced document — surfaces as a {@link RestConfigurationException} naming the operation, so
     * the router build fails and the mount is never installed.
     *
     * @param operationId the operation whose body is being synthesized, named in a failure
     * @param body        the body descriptor whose type (generic when available) drives generation
     * @param profile     the operation's effective JSON profile
     * @return the body schema as vertx-json-schema JSON
     * @throws RestConfigurationException if the profile's generator cannot be built, or the body type
     *     cannot be represented, or generation fails
     */
    private JsonObject synthesizeBody(String operationId, BodyDescriptor body, JsonMapperProfile profile) {
        Type type = body.genericType() != null ? body.genericType() : body.type();
        try {
            JsonNode node = generateBodySchema(type, profile);
            JsonObject schema = new JsonObject(node.toString());
            // Idempotent for the shared generator (which already removes the sentinel); retained because a
            // subclass may override the seam and supply a node the generator never canonicalized.
            stripDefaultSentinel(schema);
            return schema;
        } catch (RestConfigurationException alreadyNamed) {
            throw alreadyNamed;
        } catch (RuntimeException failed) {
            throw synthesisFailure(operationId, failed);
        }
    }

    /**
     * Runs profiled schema generation for the given body type through the profile's own
     * input-direction generator, which is built on first use and cached per profile instance.
     * Protected and overridable so tests and subclasses can count or substitute generation
     * invocations; it is the single generation path and is invoked exactly once per body synthesis.
     *
     * <p><strong>INTERNAL.</strong> This seam replaces the former {@code generateBodySchema(Type)} and
     * sits outside this module's compatibility promise: it may change or disappear without notice.
     * Applications contribute an {@link OperationSchemaSource} instead of subclassing this class.
     *
     * @param type    the body type to generate a schema for
     * @param profile the operation's effective JSON profile, whose input-direction generator is used
     * @return the generated schema node, parsed from the generator's canonical document
     * @throws JsonSchemaGenerationException if the profile yields no generator, the body type cannot
     *     be represented, or generation fails
     */
    protected JsonNode generateBodySchema(Type type, JsonMapperProfile profile) {
        String canonical = generatorFor(profile).generateCanonical(type);
        try {
            return CANONICAL_READER.readTree(canonical);
        } catch (JsonProcessingException unreadable) {
            // The generator guarantees a valid canonical JSON document, so this is unreachable short
            // of a programming error; the message stays value-free.
            throw new IllegalStateException("the generated canonical JSON Schema document is unreadable", unreadable);
        }
    }

    // --- Per-profile generator cache ---

    /**
     * Returns the input-direction generator for {@code profile}, building it on first use. At most one
     * generator is built per distinct profile instance, including when several router builds run
     * concurrently: the mapping function runs once per key under the concurrent map's per-key
     * exclusion, and the construction counter is incremented inside it.
     *
     * @param profile the operation's effective JSON profile
     * @return the generator retained for this profile instance
     * @throws JsonSchemaGenerationException if the profile's id, mapper, or override declarations are
     *     invalid, or it declares a duplicate effective input override
     */
    private AnnotationJsonSchemaGenerator generatorFor(JsonMapperProfile profile) {
        return generatorsByProfile.computeIfAbsent(new ProfileKey(profile), key -> {
            AnnotationJsonSchemaGenerator built =
                    AnnotationJsonSchemaGenerator.forInputProfile(key.profile(), validator.orElse(null));
            generatorConstructions.incrementAndGet();
            return built;
        });
    }

    /**
     * Returns how many generators this source has constructed. Package-private and internal: it exists
     * so a proof can tell an atomic compute-if-absent from a construct-then-publish sequence that
     * builds duplicates and retains one of them, which the retained-entry count cannot distinguish.
     *
     * @return the number of generators constructed since this source was created
     */
    int generatorConstructionCount() {
        return generatorConstructions.get();
    }

    /**
     * Returns how many generators this source retains. Package-private and internal.
     *
     * @return the number of cache entries, one per distinct profile instance seen
     */
    int cachedGeneratorCount() {
        return generatorsByProfile.size();
    }

    /**
     * Cache key giving a {@link JsonMapperProfile} reference-identity semantics.
     *
     * <p>The contract keys the generator cache by reference identity, while a map keyed on the profile
     * itself would key by {@code equals}: two distinct instances that happen to be equal records would
     * share one generator. This wrapper settles that by construction rather than by relying on a
     * profile implementation's equality contract.
     *
     * @param profile the profile this key stands for, compared by reference
     */
    private record ProfileKey(JsonMapperProfile profile) {

        @Override
        public boolean equals(Object other) {
            return other instanceof ProfileKey key && key.profile == profile;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(profile);
        }
    }

    // --- Failure wrapping ---

    /**
     * Wraps a synthesis failure as the module's configuration exception, with the operation id
     * prepended.
     *
     * <p>Two failures reach this point and they are not treated alike. The generator's own
     * {@link JsonSchemaGenerationException} is the single bounded exception type FR-JSON-076 defines:
     * its message is quoted and it is preserved as the cause, which is exactly the raw-cause allowance
     * FR-008 grants. <strong>Any other</strong> runtime exception is third-party text of unknown
     * shape and unknown length — a decode failure's message embeds the document it choked on — so it
     * contributes only its class's simple name and is not attached as a cause or as a suppressed
     * exception. Nothing it carries can then reach the message or the cause chain whatever its
     * length.
     *
     * <p>This class authors only the operation identity, bounded to
     * {@value #MAX_OPERATION_IDENTITY_LENGTH} UTF-16 code units. In the non-generator case, where
     * every part of the message is authored here, the whole message is additionally bounded to
     * {@value #MAX_MESSAGE_LENGTH} UTF-16 code units. In the generator case the quoted detail is the
     * generator's own message, which the generator already bounds and whose preserved third-party
     * cause is deliberately not sanitized here.
     *
     * @param operationId the operation whose body schema could not be synthesized
     * @param failed      the synthesis failure
     * @return the configuration exception to throw
     */
    private static RestConfigurationException synthesisFailure(String operationId, RuntimeException failed) {
        StringBuilder message = new StringBuilder("Request body schema synthesis failed for operation '")
                .append(boundedIdentity(operationId))
                .append('\'');
        if (failed instanceof JsonSchemaGenerationException generatorFailure) {
            String detail = generatorFailure.getMessage();
            if (detail != null && !detail.isBlank()) {
                message.append(": ").append(detail);
            }
            return new RestConfigurationException(message.toString(), generatorFailure);
        }
        // The simple name only, and no cause: the class of the failure is all the diagnostic that can
        // be given without disclosing text this module neither authored nor bounded.
        message.append(": ").append(failed.getClass().getSimpleName());
        return new RestConfigurationException(bounded(message.toString(), MAX_MESSAGE_LENGTH));
    }

    /**
     * Bounds an identity to {@link #MAX_OPERATION_IDENTITY_LENGTH} code units without ever splitting a
     * surrogate pair.
     *
     * @param identity the identity to bound, possibly {@code null}
     * @return the bounded identity; never {@code null}
     */
    private static String boundedIdentity(String identity) {
        return bounded(String.valueOf(identity), MAX_OPERATION_IDENTITY_LENGTH);
    }

    /**
     * Bounds a fragment to {@code max} code units, marking the elision and never splitting a
     * surrogate pair.
     *
     * @param value the fragment to bound; never {@code null}
     * @param max   the maximum retained length in UTF-16 code units; always larger than
     *     {@link #ELLIPSIS} here
     * @return the bounded fragment, never longer than {@code max}
     */
    private static String bounded(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        int cut = max - ELLIPSIS.length();
        if (Character.isHighSurrogate(value.charAt(cut - 1))) {
            cut--;
        }
        return value.substring(0, cut) + ELLIPSIS;
    }

    // --- Parameter path (constraint mapping) ---

    /**
     * Builds a JSON schema for a single parameter from its declared type, optional collection
     * component type, and constraint annotations.
     *
     * @param param the parameter descriptor
     * @return the parameter schema as vertx-json-schema JSON
     */
    private JsonObject synthesizeParam(ParamDescriptor param) {
        JsonObject schema;
        // The array-shape decision is keyed off componentType != null to agree with the binder:
        // DefaultBoundRequest binds ANY param with a non-null componentType as a JsonArray (covering
        // List/Set/SortedSet/NavigableSet/Collection AND Java arrays like Integer[]). Gating on
        // isCollection(type) instead would miss arrays — Collection.isAssignableFrom is false for an
        // array type — producing a scalar schema for a JsonArray-bound value and rejecting a valid
        // repeated request with a 400.
        boolean isArray = param.componentType() != null;
        if (isArray) {
            schema = new JsonObject().put("type", "array").put("items", scalarSchema(param.componentType()));
        } else {
            schema = scalarSchema(param.type());
        }
        applyConstraints(schema, isArray, param.annotations());
        stripDefaultSentinel(schema);
        return schema;
    }

    /**
     * Maps a scalar Java type to its JSON-schema {@code type} keyword.
     *
     * @param type the scalar (non-collection) type
     * @return a schema object with a single {@code type} entry
     */
    private static JsonObject scalarSchema(Class<?> type) {
        return new JsonObject().put("type", jsonType(type));
    }

    /**
     * Returns the JSON-schema {@code type} keyword for a scalar Java type.
     *
     * @param type the Java type
     * @return {@code "integer"}, {@code "number"}, {@code "boolean"}, or {@code "string"}
     */
    private static String jsonType(Class<?> type) {
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class) {
            return "integer";
        }
        if (type == Float.class || type == float.class || type == Double.class || type == double.class) {
            return "number";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        return "string";
    }

    /**
     * Applies the supported constraint annotations to a parameter schema as JSON-schema keywords.
     *
     * @param schema      the schema being populated
     * @param isArray     whether the schema is an array (selects {@code minItems}/{@code maxItems} vs
     *                    {@code minLength}/{@code maxLength})
     * @param annotations the parameter's declared annotations
     */
    private static void applyConstraints(JsonObject schema, boolean isArray, Collection<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof Size size) {
                if (isArray) {
                    schema.put("minItems", size.min());
                    if (size.max() != Integer.MAX_VALUE) {
                        schema.put("maxItems", size.max());
                    }
                } else {
                    schema.put("minLength", size.min());
                    if (size.max() != Integer.MAX_VALUE) {
                        schema.put("maxLength", size.max());
                    }
                }
            } else if (annotation instanceof Pattern pattern) {
                schema.put("pattern", pattern.regexp());
            } else if (annotation instanceof Min min) {
                schema.put("minimum", min.value());
            } else if (annotation instanceof Max max) {
                schema.put("maximum", max.value());
            } else if (annotation instanceof DecimalMin decimalMin) {
                schema.put("minimum", parseNumber(decimalMin.value()));
            } else if (annotation instanceof DecimalMax decimalMax) {
                schema.put("maximum", parseNumber(decimalMax.value()));
            } else if (annotation instanceof Schema swaggerSchema) {
                applySwaggerSchema(schema, swaggerSchema);
            }
        }
    }

    /**
     * Applies the keyword-bearing members of a Swagger {@code @Schema} annotation, ignoring the
     * swagger {@code ##default} sentinel and unset members.
     *
     * @param schema        the schema being populated
     * @param swaggerSchema the Swagger annotation
     */
    private static void applySwaggerSchema(JsonObject schema, Schema swaggerSchema) {
        if (!swaggerSchema.pattern().isEmpty()) {
            schema.put("pattern", swaggerSchema.pattern());
        }
        if (!swaggerSchema.minimum().isEmpty() && !DEFAULT_SENTINEL.equals(swaggerSchema.minimum())) {
            schema.put("minimum", parseNumber(swaggerSchema.minimum()));
        }
        if (!swaggerSchema.maximum().isEmpty() && !DEFAULT_SENTINEL.equals(swaggerSchema.maximum())) {
            schema.put("maximum", parseNumber(swaggerSchema.maximum()));
        }
        if (swaggerSchema.minLength() > 0) {
            schema.put("minLength", swaggerSchema.minLength());
        }
        if (swaggerSchema.maxLength() != Integer.MAX_VALUE) {
            schema.put("maxLength", swaggerSchema.maxLength());
        }
    }

    /**
     * Parses a numeric constraint string into a {@link Number}, preferring a long when integral.
     *
     * @param value the numeric string from the annotation
     * @return a {@link Long} for integral values, otherwise a {@link Double}
     */
    private static Number parseNumber(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException notLong) {
            return Double.parseDouble(value);
        }
    }

    // --- ##default sentinel strip ---

    /**
     * Recursively removes every {@code "default"} entry whose value is the swagger {@code ##default}
     * sentinel from the schema tree.
     *
     * @param value the current node (object, array, or scalar)
     */
    private static void stripDefaultSentinel(Object value) {
        if (value instanceof JsonObject obj) {
            if (DEFAULT_SENTINEL.equals(obj.getValue("default"))) {
                obj.remove("default");
            }
            // Snapshot field names: fieldNames() is a live view and we mutate the map during the walk.
            for (String field : List.copyOf(obj.fieldNames())) {
                stripDefaultSentinel(obj.getValue(field));
            }
        } else if (value instanceof JsonArray arr) {
            arr.forEach(AnnotationSchemaSource::stripDefaultSentinel);
        }
    }
}
