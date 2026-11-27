// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import io.vertx.core.json.Json;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accessor and install surface for the process JSON codec.
 *
 * <p>Vert.x runs every {@code Json.*} and {@code JsonObject}/{@code JsonArray} operation through a
 * single process-wide codec. {@code dev.vertique:vertique-json} registers the framework's codec for that
 * role through the Vert.x {@code JsonFactory} service loader and, at application startup, installs the
 * configured system profile into it. This class is how framework and application code reach the installed
 * profile and how the startup path installs it. Without {@code vertique-json}, Vert.x uses its own codec:
 * {@link #ownsCodec()} is {@code false}, {@link #codec()}, {@link #install(JsonProfileCodec)} and
 * {@link #resetForTests()} throw {@link IllegalStateException} naming {@code vertique-json}, and
 * {@link #profile()} is empty.
 *
 * <p><strong>Read at use time.</strong> {@link #codec()} is a live read: before installation it returns
 * the raw profile over Vert.x's own mapper, afterwards the installed profile. Capture the result only in an
 * object built during or after the {@code CONFIGURE} startup phase — never in a static initializer or
 * while a dependency-injection graph is being constructed, both of which run earlier.
 *
 * <p><strong>Installation is keyed by profile id.</strong> Installing the same id again swaps to the new,
 * equivalent instance and logs the swap; installing a different id fails. That makes a second application
 * booted in the same JVM with the same system profile work, while a conflicting profile is a loud failure
 * rather than a silent process-wide semantic change.
 *
 * <p><strong>The codec guards every install</strong>, whoever the caller is: the profile must be a
 * Jackson 2 profile whose mapper registers Vert.x's Jackson module (otherwise {@code JsonObject} and
 * {@code Buffer} would be bean-serialized instead of round-tripping) and does not activate Jackson default
 * typing (which turns every payload reaching the process codec into a polymorphic instantiation vector).
 * Annotation-driven polymorphism ({@code @JsonTypeInfo}) is unaffected. The guards bind the act of
 * installing; the JSON runtime re-checks the default-typing invariant at the {@code VALIDATE} phase.
 */
public final class VertiqueJson {

    private static final Logger log = LoggerFactory.getLogger(VertiqueJson.class);

    private static final String ALLOW_RESET_PROPERTY = "vertique.json.codec.allowReset";

    /** Read once at class initialization: setting the property later does not open the reset seam. */
    private static final boolean ALLOW_RESET = Boolean.getBoolean(ALLOW_RESET_PROPERTY);

    private static final String NO_FRAMEWORK_CODEC = "Vert.x did not select the framework JSON codec. Add"
            + " dev.vertique:vertique-json to the application, and keep its"
            + " META-INF/services/io.vertx.core.spi.JsonFactory entry when building a shaded or uber jar";

    private static volatile JsonProfileCodec installedProfile;
    private static volatile String installedBy;

    private VertiqueJson() {}

    /**
     * Returns the profile the process JSON codec currently runs: the installed profile, or before any
     * install the raw profile (id {@code vertx}) over Vert.x's own mapper. Read it at use time.
     *
     * @return the current profile
     * @throws IllegalStateException naming {@code vertique-json} when Vert.x did not select the framework
     *     codec
     */
    public static JsonProfileCodec codec() {
        return requireCodec().current();
    }

    /**
     * Reports whether Vert.x selected the framework's process JSON codec.
     *
     * @return {@code true} when {@code Json.CODEC} is the framework codec
     */
    public static boolean ownsCodec() {
        return Json.CODEC instanceof ProcessJsonCodec;
    }

    /**
     * Returns the id of the installed profile.
     *
     * @return the installed profile id, or empty before any install and without the framework codec
     */
    public static Optional<JsonProfileId> profile() {
        JsonProfileCodec installed = installedProfile;
        return installed == null ? Optional.empty() : Optional.of(installed.id());
    }

    /**
     * Installs {@code profile} as the process JSON codec's profile.
     *
     * <p>Under this class's lock: the codec's guards run first, then the id-keyed conflict check, then the
     * swap; only then is the profile recorded with its installer and the swap logged. A failed install
     * changes nothing.
     *
     * @param profile the profile to install
     * @throws IllegalStateException naming {@code vertique-json} without the framework codec, or naming
     *     both ids and installers when a different profile id is already installed
     * @throws IllegalArgumentException naming the profile id when the codec refuses the profile
     */
    public static synchronized void install(JsonProfileCodec profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        ProcessJsonCodec codec = requireCodec();
        JsonProfileCodec current = installedProfile;
        ProcessJsonCodec.Prepared prepared = codec.validate(profile, current);
        String caller = caller();
        JsonProfileId id = profile.id();
        if (current != null && !current.id().equals(id)) {
            throw new IllegalStateException("the process JSON codec already runs the JSON profile '"
                    + current.id().value() + "' installed by " + installedBy + "; profile '" + id.value()
                    + "' cannot be installed by " + caller
                    + ". One process has one JSON codec: select the same profile everywhere, or run the"
                    + " applications in separate JVMs");
        }
        codec.swap(prepared);
        installedProfile = profile;
        installedBy = caller;
        if (current == null) {
            log.info("Installed JSON profile '{}' as the process JSON codec (installed by {})", id.value(), caller);
        } else {
            log.info(
                    "Swapped the process JSON codec to another instance of JSON profile '{}' (installed by {})",
                    id.value(),
                    caller);
            if (!prepared.widenedSettings().isEmpty()) {
                log.warn(
                        "The same-id swap of JSON profile '{}' by {} installs a mapper with weaker read leniency"
                                + " than the mapper it replaces; every payload the process codec decodes is"
                                + " affected: {}",
                        id.value(),
                        caller,
                        String.join("; ", prepared.widenedSettings()));
            }
        }
    }

    /**
     * Test-only seam: restores the raw profile and clears the installed id. Reachable only when the JVM
     * was started with {@code -Dvertique.json.codec.allowReset=true}.
     *
     * @throws IllegalStateException naming {@code vertique-json} without the framework codec, or naming the
     *     property when the JVM was started without it
     */
    public static synchronized void resetForTests() {
        ProcessJsonCodec codec = requireCodec();
        if (!ALLOW_RESET) {
            throw new IllegalStateException("resetForTests() is a test-only seam: it is reachable only when the JVM is"
                    + " started with -D" + ALLOW_RESET_PROPERTY + "=true, and the flag is read once at class"
                    + " initialization");
        }
        codec.reset();
        installedProfile = null;
        installedBy = null;
    }

    private static ProcessJsonCodec requireCodec() {
        if (Json.CODEC instanceof ProcessJsonCodec codec) {
            return codec;
        }
        throw new IllegalStateException(NO_FRAMEWORK_CODEC);
    }

    private static String caller() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(declaring -> declaring != VertiqueJson.class)
                        .findFirst()
                        .map(Class::getName)
                        .orElse("an unknown caller"));
    }
}
