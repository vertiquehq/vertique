// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LifecyclePhase#CONFIGURE CONFIGURE} startup step that installs the configured system
 * profile's mapper as the mapper of the process JSON codec.
 *
 * <p>It resolves {@code json.systemProfile} (floor: the reserved {@code system} id) through the
 * {@link JsonMapperProfileRegistry} and hands the resulting mapper to
 * {@link VertiqueJson#install}. From that moment every Vert.x JSON operation in the process —
 * {@code Json.encode}, {@code Json.decodeValue}, {@code JsonObject} parsing and encoding,
 * {@code mapTo}, {@code mapFrom} — runs on that mapper. Running in {@code CONFIGURE} puts the
 * install before every other startup step and before any verticle deploys.
 *
 * <p>Three conditions are verified before installing, each failing the boot with a
 * {@link JsonProfileConfigurationException} that names the offending profile and the remedy:
 *
 * <ol>
 *   <li>The framework owns the process codec. If Vert.x did not select it, installing a mapper would
 *       change nothing and the application would silently keep Vert.x's raw JSON behaviour.</li>
 *   <li>The mapper registers Vert.x's Jackson module, without which {@code JsonObject},
 *       {@code JsonArray} and {@code Buffer} values would be bean-serialized instead of
 *       round-tripping.</li>
 *   <li>The mapper does not activate Jackson default typing, which would let any payload reaching
 *       the process codec choose the type it deserializes into.</li>
 * </ol>
 *
 * <p>The last two are also enforced by {@link VertiqueJson#install} itself, for every caller; this
 * step re-checks them so that a misconfigured application fails with a message naming its profile id
 * rather than with the generic seam rejection.
 *
 * <p>Settings of the selected profile that widen what the process accepts, relative to the baseline
 * {@code system} recipe, are logged at {@code WARN} — they apply to every JSON payload the process
 * decodes, including payloads no request-validation gate has seen.
 */
@Singleton
final class JsonSystemProfileInstallStep implements ApplicationStartupStep {

    private static final Logger log = LoggerFactory.getLogger(JsonSystemProfileInstallStep.class);

    /** Property that opens the process codec's test-only reset seam. */
    private static final String ALLOW_RESET_PROPERTY = "vertique.json.codec.allowReset";

    /** Jackson's registration id for Vert.x's Jackson module, as {@code getRegisteredModuleIds()} reports it. */
    private static final Object VERTX_MODULE_TYPE_ID = VertxJsonSupport.module().getTypeId();

    /** Parser features the baseline {@code system} recipe leaves off; each one widens what parses. */
    private static final List<JsonParser.Feature> LENIENT_PARSER_FEATURES = List.of(
            JsonParser.Feature.ALLOW_SINGLE_QUOTES,
            JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES,
            JsonParser.Feature.ALLOW_TRAILING_COMMA,
            JsonParser.Feature.ALLOW_MISSING_VALUES,
            JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER,
            JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS,
            JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS,
            JsonParser.Feature.ALLOW_YAML_COMMENTS);

    private final JsonConfig jsonConfig;
    private final JsonMapperProfileRegistry registry;
    private final BooleanSupplier ownsProcessCodec;

    /**
     * Constructs the step with the production codec-ownership check.
     *
     * @param jsonConfig the parsed {@code json} configuration section
     * @param registry the registry resolving the configured profile id to its mapper
     */
    @Inject
    JsonSystemProfileInstallStep(JsonConfig jsonConfig, JsonMapperProfileRegistry registry) {
        this(jsonConfig, registry, VertiqueJson::ownsProcessCodec);
    }

    /**
     * Constructs the step with an explicit codec-ownership check.
     *
     * @param jsonConfig the parsed {@code json} configuration section
     * @param registry the registry resolving the configured profile id to its mapper
     * @param ownsProcessCodec reports whether the framework owns the process JSON codec
     */
    JsonSystemProfileInstallStep(
            JsonConfig jsonConfig, JsonMapperProfileRegistry registry, BooleanSupplier ownsProcessCodec) {
        this.jsonConfig = jsonConfig;
        this.registry = registry;
        this.ownsProcessCodec = ownsProcessCodec;
    }

    /**
     * Returns the phase this step runs in.
     *
     * @return {@link LifecyclePhase#CONFIGURE}
     */
    @Override
    public LifecyclePhase phase() {
        return LifecyclePhase.CONFIGURE;
    }

    /**
     * Installs the configured system profile's mapper as the process JSON codec's mapper.
     *
     * @return a succeeded future once the mapper is installed, or a failed future carrying the
     *     configuration failure that must stop the boot
     */
    @Override
    public Future<Void> start() {
        try {
            install();
            return Future.succeededFuture();
        } catch (RuntimeException failure) {
            // The cause is not passed as a logging argument: a profile failure message can quote
            // configured values, and the future carries the full cause to the caller anyway.
            log.error(
                    "The process JSON codec could not be configured in the {} phase; the application will not start"
                            + " (failure: {})",
                    LifecyclePhase.CONFIGURE,
                    failure.getClass().getName());
            return Future.failedFuture(failure);
        }
    }

    // --- Internals ---

    /**
     * Resolves, verifies and installs the configured system profile's mapper.
     *
     * @throws JsonProfileConfigurationException if the profile is unknown or unfit for the process
     *     codec, or if another profile is already installed in this JVM
     */
    private void install() {
        JsonProfileId id = jsonConfig.effectiveSystemProfile();
        requireProcessCodecOwnership(id);
        // Resolving through the registry is also what rejects an unknown or retired profile id.
        ObjectMapper mapper = registry.mapper(id);
        requireVertxModule(id, mapper);
        requireNoDefaultTyping(id, mapper);
        warnAboutLeniency(id, mapper);
        warnAboutResetSeam();
        try {
            VertiqueJson.install(id, mapper);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            throw new JsonProfileConfigurationException(
                    "the JSON profile '" + id.value() + "' configured as 'json.systemProfile' could not be installed"
                            + " as the process JSON codec's mapper: " + rejected.getMessage(),
                    rejected);
        }
    }

    /**
     * Fails when Vert.x did not select the framework's JSON codec for this process.
     *
     * @param id the configured system profile id
     * @throws JsonProfileConfigurationException if the process codec is a foreign one
     */
    private void requireProcessCodecOwnership(JsonProfileId id) {
        if (!ownsProcessCodec.getAsBoolean()) {
            throw new JsonProfileConfigurationException("the JSON profile '" + id.value()
                    + "' configured as 'json.systemProfile' cannot be installed: the process JSON codec is "
                    + Json.CODEC.getClass().getName()
                    + ", so Vertique's JsonFactory was not selected. Check that META-INF/services entries survive"
                    + " packaging (a shaded jar must merge them) and that no other io.vertx.core.spi.JsonFactory is"
                    + " registered ahead of it");
        }
    }

    /**
     * Fails when the profile's mapper lacks Vert.x's Jackson module.
     *
     * @param id the configured system profile id
     * @param mapper the profile's mapper
     * @throws JsonProfileConfigurationException if the module is not registered
     */
    private static void requireVertxModule(JsonProfileId id, ObjectMapper mapper) {
        if (!mapper.getRegisteredModuleIds().contains(VERTX_MODULE_TYPE_ID)) {
            throw new JsonProfileConfigurationException("the JSON profile '" + id.value()
                    + "' configured as 'json.systemProfile' cannot be the process JSON codec's profile: its mapper"
                    + " does not register VertxJsonSupport.module(), so JsonObject, JsonArray and Buffer values"
                    + " would be bean-serialized instead of round-tripping. Build the profile's mapper from the"
                    + " sanctioned seed, JacksonDefaults.applySystem(new ObjectMapper())");
        }
    }

    /**
     * Fails when the profile's mapper activates Jackson default typing.
     *
     * @param id the configured system profile id
     * @param mapper the profile's mapper
     * @throws JsonProfileConfigurationException if default typing is active
     */
    private static void requireNoDefaultTyping(JsonProfileId id, ObjectMapper mapper) {
        if (mapper.getDeserializationConfig().getDefaultTyper(null) != null) {
            throw new JsonProfileConfigurationException("the JSON profile '" + id.value()
                    + "' configured as 'json.systemProfile' cannot be the process JSON codec's profile: its mapper"
                    + " activates Jackson default typing, which would let any payload decoded by the process codec"
                    + " choose the type it instantiates. Remove activateDefaultTyping(); annotation-driven"
                    + " polymorphism with @JsonTypeInfo stays available");
        }
    }

    /**
     * Logs, at {@code WARN}, every setting of the profile's mapper that accepts more than the
     * baseline {@code system} recipe would.
     *
     * @param id the configured system profile id
     * @param mapper the profile's mapper
     */
    private static void warnAboutLeniency(JsonProfileId id, ObjectMapper mapper) {
        List<String> deltas = new ArrayList<>();
        if (!mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)) {
            deltas.add("FAIL_ON_UNKNOWN_PROPERTIES is off (unknown JSON properties are silently dropped)");
        }
        if (mapper.isEnabled(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)) {
            deltas.add("READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE is on (an unrecognized enum string binds to the"
                    + " declared default constant)");
        }
        if (mapper.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)) {
            deltas.add("ACCEPT_CASE_INSENSITIVE_ENUMS is on");
        }
        LENIENT_PARSER_FEATURES.stream()
                .filter(mapper::isEnabled)
                .map(feature -> feature.name() + " is on")
                .forEach(deltas::add);
        deltas.addAll(weakerReadLimits(mapper.getFactory().streamReadConstraints()));
        if (!deltas.isEmpty()) {
            log.warn(
                    "The JSON profile '{}' installed as the process JSON codec accepts more than the baseline"
                            + " 'system' recipe; this applies to every payload the process decodes, including"
                            + " payloads no request-validation gate has seen: {}",
                    id.value(),
                    String.join("; ", deltas));
        }
    }

    /**
     * Returns a description of every stream-read limit that is weaker than the raw Vert.x factory's.
     *
     * @param constraints the installed mapper's factory limits
     * @return one entry per weakened limit; empty when none is weakened
     */
    private static List<String> weakerReadLimits(StreamReadConstraints constraints) {
        StreamReadConstraints raw = SystemJsonMapperProfile.rawStreamReadConstraints();
        List<String> weakened = new ArrayList<>();
        addWhenWeaker(weakened, "maxNestingDepth", constraints.getMaxNestingDepth(), raw.getMaxNestingDepth());
        addWhenWeaker(weakened, "maxNumberLength", constraints.getMaxNumberLength(), raw.getMaxNumberLength());
        addWhenWeaker(weakened, "maxStringLength", constraints.getMaxStringLength(), raw.getMaxStringLength());
        addWhenWeaker(weakened, "maxNameLength", constraints.getMaxNameLength(), raw.getMaxNameLength());
        addWhenWeaker(weakened, "maxDocumentLength", constraints.getMaxDocumentLength(), raw.getMaxDocumentLength());
        addWhenWeaker(weakened, "maxTokenCount", constraints.getMaxTokenCount(), raw.getMaxTokenCount());
        return weakened;
    }

    /**
     * Records a stream-read limit that is higher than, or unbounded relative to, the raw one.
     *
     * @param weakened the accumulating descriptions
     * @param limit the limit's name
     * @param actual the installed mapper's value
     * @param raw the raw Vert.x factory's value
     */
    private static void addWhenWeaker(List<String> weakened, String limit, long actual, long raw) {
        boolean rawIsBounded = raw > 0;
        boolean actualIsUnbounded = actual <= 0;
        if (rawIsBounded && (actualIsUnbounded || actual > raw)) {
            weakened.add("the stream-read limit " + limit + " (" + actual + ") is weaker than Vert.x's (" + raw + ")");
        }
    }

    /** Logs, at {@code WARN}, that the process codec's test-only reset seam is open in this JVM. */
    private static void warnAboutResetSeam() {
        if (Boolean.getBoolean(ALLOW_RESET_PROPERTY)) {
            log.warn(
                    "The system property {} is set: the process JSON codec's test-only reset seam is open in this JVM."
                            + " It must never be set for a deployed application",
                    ALLOW_RESET_PROPERTY);
        }
    }
}
