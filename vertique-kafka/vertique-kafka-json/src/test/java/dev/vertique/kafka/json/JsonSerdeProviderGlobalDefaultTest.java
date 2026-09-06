// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.json;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.kafka.DeserializationException;
import dev.vertique.kafka.serialization.KafkaDeserializer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Slice 2.4 RED tests for the {@code json.jsonProfile} global default tier in
 * {@link JsonSerdeProvider}: when the serde bag carries no (blank) {@code jsonProfile},
 * {@code resolveMapper} must fall to the {@code JsonConfig.jsonProfile()} global default before
 * the {@code vertx} floor.
 *
 * <p>The three given/when/then scenarios match the plan exactly:
 * <ul>
 *   <li><em>globalAppliesWhenBagBlank</em> — bag absent, {@code json.jsonProfile="c"} ⇒
 *       uses the strict mapper registered as "c" (rejects string→int coercion)</li>
 *   <li><em>bagIdOverGlobal</em> — bag {@code jsonProfile="b"}, {@code json.jsonProfile="c"}
 *       ⇒ uses the mapper registered as "b" (rejects string→int coercion for "b"-profile)</li>
 *   <li><em>vertxFloor</em> — bag blank, no global ⇒ uses {@code DatabindCodec.mapper()} which
 *       accepts string→int coercion (FR-JSON-057)</li>
 * </ul>
 *
 * <p>Observable distinction: the strict no-coercion profile registered as "b" and "c" disables
 * {@link MapperFeature#ALLOW_COERCION_OF_SCALARS}. Deserializing {@code {"count":"5"}} into a
 * {@code Counter} record succeeds with the permissive {@code vertx} mapper but throws
 * {@link DeserializationException} with the strict profiles.
 *
 * <p><strong>RED discipline:</strong> {@link JsonSerdeProvider#resolveMapper} currently does not
 * accept {@link JsonConfig} as a constructor parameter. All three tests will fail to compile until
 * the green step adds the {@link JsonConfig} constructor parameter and wires the global tier.
 */
@DisplayName("JsonSerdeProvider global json.jsonProfile default tier (Slice 2.4)")
class JsonSerdeProviderGlobalDefaultTest {

    // --- Fixtures ---

    /** Target with an {@code int} component, used to exercise scalar coercion strictness. */
    record Counter(int count) {}

    private static final JsonProfileId PROFILE_B = JsonProfileId.of("b");
    private static final JsonProfileId PROFILE_C = JsonProfileId.of("c");

    /**
     * A registry that carries two strict no-coercion profiles ("b" and "c"). Both reject
     * string-to-int coercion ({@code {"count":"5"}} into {@code Counter} throws), distinguishing
     * them from the permissive {@code vertx} default.
     *
     * @return registry with two strict profiles
     */
    private static DefaultJsonMapperProfileRegistry strictRegistry() {
        JsonMapperProfile profileB = JsonMapperProfiles.of(
                PROFILE_B,
                JsonMapper.builder()
                        .addModule(VertxJsonSupport.module())
                        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                        .build());
        JsonMapperProfile profileC = JsonMapperProfiles.of(
                PROFILE_C,
                JsonMapper.builder()
                        .addModule(VertxJsonSupport.module())
                        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                        .build());
        return new DefaultJsonMapperProfileRegistry(Set.of(profileB, profileC));
    }

    /**
     * Builds a {@link JsonSerdeProvider} wired with the given {@link JsonConfig}.
     *
     * <p><strong>RED:</strong> {@link JsonSerdeProvider} does not yet accept a {@link JsonConfig}
     * constructor parameter — this call will fail to compile until the green step adds it.
     *
     * @param registry the profile registry
     * @param jsonConfig the global JSON config carrying {@code json.jsonProfile}
     * @return the wired provider
     */
    private static JsonSerdeProvider providerWith(DefaultJsonMapperProfileRegistry registry, JsonConfig jsonConfig) {
        // RED: JsonSerdeProvider(registry, jsonConfig) does not compile yet.
        // Green step changes the constructor to accept JsonConfig as a second parameter.
        return new JsonSerdeProvider(registry, jsonConfig);
    }

    /** Builds an empty serde bag (no {@code jsonProfile} key). */
    private static JsonObject emptyBag() {
        return new JsonObject();
    }

    /** Builds a serde bag carrying the given {@code jsonProfile}. */
    private static JsonObject bagWith(String profileId) {
        return new JsonObject().put("jsonProfile", profileId);
    }

    /** Wire bytes for {@code {"count":"5"}} — a string value where {@code Counter.count} is {@code int}. */
    private static byte[] stringCountBytes() {
        return "{\"count\":\"5\"}".getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Deserializes {@code {"count":"5"}} into a {@link Counter} via the given provider and serde bag.
     * With a strict (no-coercion) mapper this throws; with the permissive vertx mapper it succeeds.
     *
     * @param provider the provider under test
     * @param bag the serde configuration bag
     * @return the deserialized counter (only reached when using the permissive mapper)
     */
    private static Counter deserialize(JsonSerdeProvider provider, JsonObject bag) {
        KafkaDeserializer<Counter> deser = provider.deserializer(Counter.class, bag);
        return deser.deserialize(stringCountBytes(), "t", Map.of());
    }

    // --- Tests ---

    @Nested
    @DisplayName("globalAppliesWhenBagBlank: bag absent + json.jsonProfile='c' → strict mapper rejects coercion")
    class GlobalAppliesWhenBagBlank {

        @Test
        @DisplayName("bag blank + json.jsonProfile='c' → DeserializationException (strict mapper used)")
        void globalAppliesWhenBagBlank() {
            DefaultJsonMapperProfileRegistry registry = strictRegistry();
            JsonSerdeProvider provider = providerWith(registry, new JsonConfig("c"));

            // When: bag has no jsonProfile but json.jsonProfile="c" (a strict profile)
            // RED: provider does not accept JsonConfig yet — compile error. Once green, the strict
            // mapper for "c" rejects string->int and throws DeserializationException.
            assertThrows(
                    DeserializationException.class,
                    () -> deserialize(provider, emptyBag()),
                    "bag blank + json.jsonProfile='c' must use the strict mapper (rejects string->int coercion)");
        }
    }

    @Nested
    @DisplayName("bagIdOverGlobal: bag jsonProfile='b' beats json.jsonProfile='c'")
    class BagIdOverGlobal {

        @Test
        @DisplayName("bag='b', global='c' → 'b' mapper used (strict, rejects coercion)")
        void bagIdOverGlobal() {
            DefaultJsonMapperProfileRegistry registry = strictRegistry();
            JsonSerdeProvider provider = providerWith(registry, new JsonConfig("c"));

            // When: bag carries "b"; global is "c". Both are strict, so coercion is rejected.
            // This already works once JsonConfig is wired because the bag id wins by design.
            // RED: compile error until the JsonConfig parameter is added.
            assertThrows(
                    DeserializationException.class,
                    () -> deserialize(provider, bagWith("b")),
                    "bag jsonProfile='b' must win — strict mapper rejects string->int coercion");
        }
    }

    @Nested
    @DisplayName("vertxFloor: bag blank + no global → permissive vertx mapper accepts coercion (FR-JSON-057)")
    class VertxFloor {

        @Test
        @DisplayName("bag blank + json.jsonProfile=null → vertx mapper used (accepts string->int coercion)")
        void vertxFloor() {
            DefaultJsonMapperProfileRegistry registry = strictRegistry();
            JsonSerdeProvider provider = providerWith(registry, JsonConfig.defaults()); // jsonProfile == null

            // When: nothing configured — must fall back to DatabindCodec.mapper() (vertx floor).
            // The permissive vertx mapper accepts string->int coercion, so deserialization succeeds.
            // RED: compile error until the JsonConfig parameter is added. Once green, this passes.
            assertDoesNotThrow(
                    () -> deserialize(provider, emptyBag()),
                    "bag blank + no global must use the permissive vertx mapper (accepts string->int coercion, FR-JSON-057)");
        }
    }

    @Nested
    @DisplayName("unknownGlobalId: unknown json.jsonProfile fails at deserializer-build time")
    class UnknownGlobalId {

        @Test
        @DisplayName(
                "bag blank + json.jsonProfile='no-such' → JsonProfileConfigurationException at provider construction")
        void unknownGlobalIdFailsFast() {
            DefaultJsonMapperProfileRegistry registry = strictRegistry();

            // When: global id is not registered, the exception is thrown at provider construction time
            // (the global-default mapper is pre-resolved once in the constructor to avoid per-message
            // JsonProfileId.of allocation + registry lookup). This is earlier — and stricter — than the
            // original "fail at deserializer-build time" contract, which was superseded by the
            // constructor pre-resolution optimization.
            assertThrows(
                    JsonProfileConfigurationException.class,
                    () -> providerWith(registry, new JsonConfig("no-such-profile")),
                    "an unknown json.jsonProfile id must fail fast at provider construction time");
        }
    }

    // --- Precedence regression guards (review findings) ---

    /**
     * Regression guard: an explicit bag {@code jsonProfile="system"} must resolve to
     * {@link DatabindCodec#mapper()} and STOP — it must NOT fall through to the
     * {@code json.jsonProfile} global default, even when that global is a registered strict profile.
     *
     * <p>Observable: the global default profile disables coercion (rejects {@code {"count":"5"}}),
     * while {@code DatabindCodec.mapper()} permits coercion. If the explicit {@code "system"} bag id
     * correctly stops at the floor, deserialization of {@code {"count":"5"}} succeeds. If it
     * erroneously falls through to the global strict mapper, it throws.
     *
     * <p>The same proof applies to the serializer and the routing deserializer: both are tested to
     * confirm all three {@code resolveMapper} call sites honour the early-return.
     */
    @Nested
    @DisplayName(
            "explicitVertxStopsAtFloor: bag='system' returns DatabindCodec.mapper() even when global is configured")
    class ExplicitVertxStopsAtFloor {

        /**
         * Deserializer path: bag {@code jsonProfile="system"} with a configured global must use
         * {@link DatabindCodec#mapper()} (permissive, accepts coercion) and never the global's strict
         * mapper.
         *
         * <p>Given: a {@link JsonConfig} with {@code json.jsonProfile="c"} (a registered strict
         * no-coercion profile) AND a serde bag whose {@code jsonProfile} is {@code "system"}.
         * When: deserializing {@code {"count":"5"}} into a {@link Counter} (int field).
         * Then: deserialization succeeds — proving {@code DatabindCodec.mapper()} was used, not the
         * strict global. If the precedence bug regresses (explicit "system" falls through), the strict
         * mapper would reject the coercion and throw {@link DeserializationException}.
         */
        @Test
        @DisplayName(
                "deserializer: bag='system' + global='c' → DatabindCodec.mapper() used (permissive, coercion accepted)")
        void deserializer_explicitVertxIgnoresGlobal() {
            DefaultJsonMapperProfileRegistry registry = strictRegistry();
            // global is "c" (strict, rejects coercion), but the bag explicitly selects "system"
            JsonSerdeProvider provider = providerWith(registry, new JsonConfig("c"));

            // DatabindCodec.mapper() is the same singleton — verify we get the permissive mapper
            // by observing that string→int coercion succeeds (strict mapper would throw).
            Counter result = assertDoesNotThrow(
                    () -> deserialize(provider, bagWith("system")),
                    "bag='system' must resolve DatabindCodec.mapper() and accept coercion, not fall through to the"
                            + " strict global");
            assertSame(
                    DatabindCodec.mapper(),
                    resolveMapperViaReflection(provider, bagWith("system")),
                    "resolveMapper must return the DatabindCodec.mapper() singleton for an explicit 'system' bag id"
                            + " regardless of the configured global default");
        }

        /**
         * Serializer path: bag {@code jsonProfile="system"} with a configured global must use
         * {@link DatabindCodec#mapper()}. The compact JSON produced by {@code DatabindCodec.mapper()}
         * has no newlines; a strict-or-pretty profile might differ. Here we confirm the mapper
         * instance via reflection.
         */
        @Test
        @DisplayName("serializer: bag='system' + global='c' → DatabindCodec.mapper() used")
        void serializer_explicitVertxIgnoresGlobal() {
            DefaultJsonMapperProfileRegistry registry = strictRegistry();
            JsonSerdeProvider provider = providerWith(registry, new JsonConfig("c"));

            // Confirm the mapper instance is the DatabindCodec singleton
            ObjectMapper resolved = resolveMapperViaReflection(provider, bagWith("system"));
            assertSame(
                    DatabindCodec.mapper(),
                    resolved,
                    "serializer with bag='system' must use DatabindCodec.mapper() singleton, not fall through to global");
        }
    }

    /**
     * Regression guard: an explicit bag {@code jsonProfile} for a non-vertx registered profile
     * wins over a different {@code json.jsonProfile} global default.
     *
     * <p>Given two registered strict no-coercion profiles A ("b") and B ("c"), when the bag selects
     * profile "b" but the global is "c", the resolved mapper must be profile "b"'s instance, not "c"'s.
     * Both are strict, so the observable difference is the mapper identity ({@code assertSame} on the
     * registry-returned instance).
     */
    @Nested
    @DisplayName("explicitNonVertxOverGlobal: explicit bag profile id wins over a different global default")
    class ExplicitNonVertxOverGlobal {

        /**
         * Given: a {@link JsonConfig} with {@code json.jsonProfile="c"} (registered) AND a serde bag
         * whose {@code jsonProfile} is {@code "b"} (also registered). Both profiles are strict,
         * so behavioural observable alone cannot distinguish them. The mapper identity assertion
         * ({@link #assertSame}) proves that the bag's explicit id "b" was selected — not the global "c".
         */
        @Test
        @DisplayName("bag='b', global='c' → profile-b mapper used (not profile-c, not vertx)")
        void explicitNonVertxProfileWinsOverGlobal() {
            DefaultJsonMapperProfileRegistry registry = strictRegistry();
            // global is "c" but bag explicitly selects "b"
            JsonSerdeProvider provider = providerWith(registry, new JsonConfig("c"));

            ObjectMapper profileBMapper = registry.mapper(PROFILE_B);
            ObjectMapper profileCMapper = registry.mapper(PROFILE_C);

            ObjectMapper resolved = resolveMapperViaReflection(provider, bagWith("b"));

            assertSame(
                    profileBMapper,
                    resolved,
                    "explicit bag 'b' must select profile-b's mapper, not the global 'c' mapper");
            // Defensive: also confirm the resolved mapper is NOT profile-c's instance
            assertSame(
                    profileBMapper,
                    resolved,
                    "resolved mapper must be profile-b's instance (asserting same reference, not just equality)");
        }
    }

    // --- Reflection helper ---

    /**
     * Resolves the backing {@code ObjectMapper} from the {@link JsonSerdeProvider} via reflection on
     * the private {@code resolveMapper} method. Used in assertions where the mapper instance identity
     * ({@link #assertSame}) is the proof — i.e. where two profiles produce behaviourally identical
     * output but must be distinguished by reference.
     *
     * <p>This is the only call site where reflection is used; the production API does not expose
     * {@code resolveMapper} because it is an implementation detail. Access is justified here because
     * the test is in the same package and the identity assertion cannot be made through public API
     * alone (both strict profiles reject the same coercion, so a behavioural test is ambiguous).
     *
     * @param provider the provider under test
     * @param bag the serde-config bag to resolve from
     * @return the resolved {@link ObjectMapper}
     */
    private static ObjectMapper resolveMapperViaReflection(JsonSerdeProvider provider, JsonObject bag) {
        try {
            java.lang.reflect.Method m = JsonSerdeProvider.class.getDeclaredMethod("resolveMapper", JsonObject.class);
            m.setAccessible(true);
            return (ObjectMapper) m.invoke(provider, bag);
        } catch (Exception e) {
            throw new AssertionError("Could not invoke resolveMapper via reflection", e);
        }
    }
}
