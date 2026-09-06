// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.Json;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.core.json.jackson.VertxModule;
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
