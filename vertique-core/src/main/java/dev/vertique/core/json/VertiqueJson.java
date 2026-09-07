// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.Json;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.core.json.jackson.VertxModule;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accessor and install surface for the process JSON codec's mapper.
 *
 * <p>Vert.x runs every {@code Json.*} and {@code JsonObject}/{@code JsonArray} operation through a
 * single process-wide codec. The framework registers its own codec for that role through the Vert.x
 * {@code JsonFactory} service loader ({@link VertiqueJsonFactory}) and points it, at application
 * startup, at the mapper of the configured system profile. This class is how framework and
 * application code reach that mapper and how the startup path installs it.
 *
 * <p><strong>Read at use time.</strong> {@link #mapper()} is a live read: before installation it
 * returns Vert.x's raw mapper, afterwards the installed profile's mapper. Capture the result only in
 * an object built during or after the {@code CONFIGURE} startup phase — never in a static
 * initializer or while a dependency-injection graph is being constructed, both of which run earlier.
 *
 * <p><strong>Installation is keyed by profile id.</strong> Installing the same id again swaps the
 * delegate to the new, equivalent instance and logs the swap; installing a different id fails. That
 * makes a second application booted in the same JVM with the same system profile work, while a
 * conflicting profile is a loud failure rather than a silent process-wide semantic change.
 *
 * <p><strong>Two invariants are enforced on every install</strong>, whoever the caller is: the
 * mapper must have Vert.x's Jackson module registered (otherwise {@code JsonObject} and
 * {@code Buffer} would be bean-serialized instead of round-tripping), and it must not activate
 * Jackson default typing (which turns every JSON payload reaching the process codec into a
 * polymorphic instantiation vector). Annotation-driven polymorphism ({@code @JsonTypeInfo}) is
 * unaffected. Both invariants bind the act of installing: the installed mapper stays a live
 * {@code ObjectMapper}, so code that reconfigures it afterwards is outside every guard; the JSON
 * runtime re-checks the default-typing invariant once more at the {@code VALIDATE} phase.
 *
 * <p>Mappers are swapped, never reconfigured: Jackson forbids reconfiguring a mapper after first
 * use. {@link DatabindCodec#mapper()} in particular is never mutated by the framework.
 */
public final class VertiqueJson {

    private static final Logger log = LoggerFactory.getLogger(VertiqueJson.class);

    /** System property that opens {@link #resetForTests()}. */
    private static final String ALLOW_RESET_PROPERTY = "vertique.json.codec.allowReset";

    /**
     * Whether {@link #resetForTests()} is reachable in this JVM, captured once when this class is
     * initialized. A later {@code System.setProperty} cannot open the seam.
     */
    private static final boolean ALLOW_RESET = Boolean.getBoolean(ALLOW_RESET_PROPERTY);

    /** The id of the installed profile, or {@code null} while nothing is installed. */
    private static volatile JsonProfileId installedId;

    /** The class that installed {@link #installedId}, or {@code null} while nothing is installed. */
    private static volatile String installedBy;

    private VertiqueJson() {}

    /**
     * Returns the mapper the process JSON codec currently runs on: Vert.x's raw mapper before
     * installation, the installed profile's mapper afterwards.
     *
     * @return the process mapper; never {@code null}
     */
    public static ObjectMapper mapper() {
        return VertiqueJsonCodec.INSTANCE.mapper();
    }

    /**
     * Returns whether Vert.x selected the framework's codec as the process codec.
     *
     * <p>{@code false} means the service registration did not reach the classloader that initialized
     * Vert.x's {@code Json} class — typically a shaded jar that dropped
     * {@code META-INF/services} entries, or a container that isolated the framework — and that
     * installing a mapper would have no effect on {@code Json.*}.
     *
     * @return {@code true} when {@code Json.CODEC} is the framework's codec
     */
    public static boolean ownsProcessCodec() {
        return Json.CODEC == VertiqueJsonCodec.INSTANCE;
    }

    /**
     * Returns the id of the installed profile.
     *
     * @return the installed profile id, or an empty optional before installation
     */
    public static Optional<JsonProfileId> installedProfile() {
        return Optional.ofNullable(installedId);
    }

    /**
     * Installs {@code mapper} as the process JSON codec's mapper under the profile id {@code id}.
     *
     * <p>The mapper invariants are checked first, on every call. Then, if a profile is already
     * installed, its id must equal {@code id}: the same id swaps the delegate to {@code mapper}, a
     * different id is refused and nothing changes.
     *
     * @param id the profile id the mapper belongs to
     * @param mapper the mapper to install; never mutated
     * @throws IllegalArgumentException if {@code mapper} does not register Vert.x's Jackson module or
     *     activates Jackson default typing
     * @throws IllegalStateException if a different profile id is already installed
     */
    public static synchronized void install(JsonProfileId id, ObjectMapper mapper) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        requireVertxModule(id, mapper);
        requireNoDefaultTyping(id, mapper);

        String caller = caller();
        JsonProfileId current = installedId;
        if (current != null && !current.equals(id)) {
            throw new IllegalStateException("the process JSON codec already runs the JSON profile '"
                    + current.value() + "' installed by " + installedBy + "; profile '" + id.value()
                    + "' cannot be installed by " + caller
                    + ". One process has one JSON codec: select the same profile everywhere, or run the"
                    + " applications in separate JVMs");
        }

        ObjectMapper previous = current != null ? VertiqueJsonCodec.INSTANCE.mapper() : null;
        VertiqueJsonCodec.INSTANCE.delegateTo(mapper);
        installedId = id;
        installedBy = caller;
        if (current == null) {
            log.info("Installed JSON profile '{}' as the process JSON codec (installed by {})", id.value(), caller);
        } else {
            log.info(
                    "Swapped the process JSON codec to another mapper instance of JSON profile '{}' (installed by {})",
                    id.value(),
                    caller);
            warnAboutLeniencyWidening(id, caller, previous, mapper);
        }
    }

    /**
     * Restores Vert.x's raw mapper as the process codec's delegate and clears the installation.
     *
     * <p>Test seam. It is reachable only when the JVM was <em>started</em> with
     * {@code -Dvertique.json.codec.allowReset=true}: the flag is read once when this class is
     * initialized, so setting the property at runtime does not open it. A build enables the flag in
     * the surefire or failsafe configuration of the modules whose tests install a mapper.
     *
     * @throws IllegalStateException if the JVM was not started with the flag
     */
    public static synchronized void resetForTests() {
        if (!ALLOW_RESET) {
            throw new IllegalStateException("resetForTests() is a test-only seam: it is reachable only when the JVM is"
                    + " started with -D" + ALLOW_RESET_PROPERTY + "=true, and the flag is read once at class"
                    + " initialization");
        }
        VertiqueJsonCodec.INSTANCE.restoreRawDelegate();
        installedId = null;
        installedBy = null;
    }

    // --- Guards ---

    /**
     * Rejects a mapper that does not carry Vert.x's Jackson module.
     *
     * @param id the profile id being installed, for the failure message
     * @param mapper the candidate mapper
     * @throws IllegalArgumentException if the module is not registered
     */
    private static void requireVertxModule(JsonProfileId id, ObjectMapper mapper) {
        if (!mapper.getRegisteredModuleIds().contains(VertxModule.class.getName())) {
            throw new IllegalArgumentException("the mapper of JSON profile '" + id.value()
                    + "' cannot be the process JSON codec's mapper: it does not register Vert.x's Jackson module, so"
                    + " JsonObject, JsonArray and Buffer values would be bean-serialized instead of round-tripping");
        }
    }

    /**
     * Rejects a mapper with Jackson default typing active.
     *
     * @param id the profile id being installed, for the failure message
     * @param mapper the candidate mapper
     * @throws IllegalArgumentException if default typing is active
     */
    private static void requireNoDefaultTyping(JsonProfileId id, ObjectMapper mapper) {
        if (mapper.getDeserializationConfig().getDefaultTyper(null) != null) {
            throw new IllegalArgumentException("the mapper of JSON profile '" + id.value()
                    + "' cannot be the process JSON codec's mapper: it activates Jackson default typing, which lets"
                    + " any payload reaching the codec choose the type it deserializes into. Annotation-driven"
                    + " polymorphism is unaffected");
        }
    }

    /**
     * Parser features that widen what the process codec accepts as JSON text; a same-id swap that
     * turns any of them on is reported, mirroring the CONFIGURE-time install step's own list.
     */
    private static final List<JsonParser.Feature> LENIENT_PARSER_FEATURES = List.of(
            JsonParser.Feature.ALLOW_COMMENTS,
            JsonParser.Feature.ALLOW_SINGLE_QUOTES,
            JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES,
            JsonParser.Feature.ALLOW_TRAILING_COMMA,
            JsonParser.Feature.ALLOW_MISSING_VALUES,
            JsonParser.Feature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER,
            JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS,
            JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS,
            JsonParser.Feature.ALLOW_YAML_COMMENTS);

    /**
     * Logs, at {@code WARN}, every read-leniency setting a same-id swap's incoming mapper widens
     * relative to the mapper it replaces.
     *
     * <p>A same-id swap is permitted because code running inside the process is trusted, but it is
     * never silent: the two structural invariants ({@link #requireVertxModule},
     * {@link #requireNoDefaultTyping}) are re-checked above regardless, and this comparison covers
     * what those invariants do not — read leniency that widens what every payload the process codec
     * decodes afterwards is accepted as.
     *
     * @param id the profile id being swapped
     * @param caller the class performing the swap
     * @param previous the mapper being replaced
     * @param incoming the mapper taking its place
     */
    private static void warnAboutLeniencyWidening(
            JsonProfileId id, String caller, ObjectMapper previous, ObjectMapper incoming) {
        List<String> deltas = new ArrayList<>();
        addWhenNoLongerFails(
                deltas,
                "FAIL_ON_UNKNOWN_PROPERTIES",
                previous.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
                incoming.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        addWhenNoLongerFails(
                deltas,
                "FAIL_ON_TRAILING_TOKENS",
                previous.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS),
                incoming.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
        addWhenNowAccepted(
                deltas,
                "ACCEPT_CASE_INSENSITIVE_PROPERTIES",
                previous.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES),
                incoming.isEnabled(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES));
        for (JsonParser.Feature lenient : LENIENT_PARSER_FEATURES) {
            addWhenNowAccepted(deltas, lenient.name(), previous.isEnabled(lenient), incoming.isEnabled(lenient));
        }
        deltas.addAll(weakerReadLimits(
                previous.getFactory().streamReadConstraints(),
                incoming.getFactory().streamReadConstraints()));
        if (!deltas.isEmpty()) {
            log.warn(
                    "The same-id swap of JSON profile '{}' by {} installs a mapper with weaker read leniency than"
                            + " the mapper it replaces; every payload the process codec decodes is affected: {}",
                    id.value(),
                    caller,
                    String.join("; ", deltas));
        }
    }

    /**
     * Records a delta when a feature that used to make decoding fail no longer does.
     *
     * @param deltas the accumulating descriptions
     * @param feature the feature's name, for the message
     * @param previousFails whether the replaced mapper had the feature enabled (failing)
     * @param incomingFails whether the incoming mapper has the feature enabled (failing)
     */
    private static void addWhenNoLongerFails(
            List<String> deltas, String feature, boolean previousFails, boolean incomingFails) {
        if (previousFails && !incomingFails) {
            deltas.add(feature + " is off (was on)");
        }
    }

    /**
     * Records a delta when a feature that widens what decoding accepts turns on.
     *
     * @param deltas the accumulating descriptions
     * @param feature the feature's name, for the message
     * @param previousAccepts whether the replaced mapper had the feature enabled (accepting)
     * @param incomingAccepts whether the incoming mapper has the feature enabled (accepting)
     */
    private static void addWhenNowAccepted(
            List<String> deltas, String feature, boolean previousAccepts, boolean incomingAccepts) {
        if (!previousAccepts && incomingAccepts) {
            deltas.add(feature + " is on (was off)");
        }
    }

    /**
     * Returns a description of every stream-read limit the incoming mapper's factory weakens
     * relative to the mapper it replaces.
     *
     * @param previous the replaced mapper's factory limits
     * @param incoming the incoming mapper's factory limits
     * @return one entry per weakened limit; empty when none is weakened
     */
    private static List<String> weakerReadLimits(StreamReadConstraints previous, StreamReadConstraints incoming) {
        List<String> weakened = new ArrayList<>();
        // maxNestingDepth/maxNumberLength/maxStringLength/maxNameLength are always positive in
        // Jackson (the builder rejects non-positive values), so only "higher" is weaker for them;
        // maxDocumentLength and maxTokenCount treat a non-positive value as unbounded.
        addWhenHigher(weakened, "maxNestingDepth", incoming.getMaxNestingDepth(), previous.getMaxNestingDepth());
        addWhenHigher(weakened, "maxNumberLength", incoming.getMaxNumberLength(), previous.getMaxNumberLength());
        addWhenHigher(weakened, "maxStringLength", incoming.getMaxStringLength(), previous.getMaxStringLength());
        addWhenHigher(weakened, "maxNameLength", incoming.getMaxNameLength(), previous.getMaxNameLength());
        addWhenWeaker(weakened, "maxDocumentLength", incoming.getMaxDocumentLength(), previous.getMaxDocumentLength());
        addWhenWeaker(weakened, "maxTokenCount", incoming.getMaxTokenCount(), previous.getMaxTokenCount());
        return weakened;
    }

    /**
     * Records a stream-read limit that is higher than, or unbounded relative to, the mapper being
     * replaced.
     *
     * @param weakened the accumulating descriptions
     * @param limit the limit's name
     * @param incoming the incoming mapper's value
     * @param previous the replaced mapper's value
     */
    private static void addWhenHigher(List<String> weakened, String limit, long incoming, long previous) {
        if (incoming > previous) {
            weakened.add("the stream-read limit " + limit + " (" + incoming + ") is weaker than the previous mapper's ("
                    + previous + ")");
        }
    }

    /**
     * Records {@code limit} when the incoming read constraint is looser than the previous one: a larger
     * bound, or the unbounded sentinel replacing a bound.
     */
    private static void addWhenWeaker(List<String> weakened, String limit, long incoming, long previous) {
        boolean previousIsBounded = previous > 0;
        boolean incomingIsUnbounded = incoming <= 0;
        if (previousIsBounded && (incomingIsUnbounded || incoming > previous)) {
            weakened.add("the stream-read limit " + limit + " (" + incoming + ") is weaker than the previous mapper's ("
                    + previous + ")");
        }
    }

    /**
     * Returns the name of the class that called into this class.
     *
     * @return the calling class name, or a placeholder when the stack yields none
     */
    private static String caller() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(declaring -> declaring != VertiqueJson.class)
                        .findFirst()
                        .map(Class::getName)
                        .orElse("an unknown caller"));
    }
}
