// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 2.1 RED tests for {@link JsonConfig} — boundary parsing of the {@code json} section into the
 * typed record, via a {@link ConfigParser}.
 *
 * <p>Verifies that:
 *
 * <ul>
 *   <li>a {@code json} section {@code {"jsonProfile":"vertique"}} yields {@code jsonProfile()=="vertique"};
 *   <li>an empty/absent {@code json} section yields {@code jsonProfile()==null} and equals
 *       {@link JsonConfig#defaults()};
 *   <li>{@code {"jsonProfile":""}} yields {@code jsonProfile()==""} (blank is preserved; the floor
 *       lives in the accessors, which treat blank as no default ⇒ vertique).
 * </ul>
 *
 * <p>{@code vertique-json} cannot depend on {@code vertique-config-core} (which depends back on
 * {@code vertique-json}, a reactor cycle), so this test supplies a minimal {@link ConfigParser} over
 * a plain Jackson {@link ObjectMapper}; the {@link JsonConfig} record is two nullable strings with
 * a {@code @JsonCreator}, which a plain mapper binds correctly.
 */
@DisplayName("JsonConfig")
class JsonConfigTest {

    @Test
    @DisplayName("parses jsonProfile")
    void parsesJsonProfile() {
        JsonObject section = new JsonObject().put("jsonProfile", "vertique");

        JsonConfig config = configParser().parse(section, JsonConfig.class);

        assertEquals("vertique", config.jsonProfile());
    }

    @Test
    @DisplayName("unset is the framework default")
    void unsetIsFrameworkDefault() {
        JsonConfig config = configParser().parse(new JsonObject(), JsonConfig.class);

        assertNull(config.jsonProfile(), "absent jsonProfile must be null (⇒ the vertique floor)");
        assertEquals(JsonConfig.defaults(), config);
    }

    @Test
    @DisplayName("blank stays blank")
    void blankStaysBlank() {
        JsonObject section = new JsonObject().put("jsonProfile", "");

        JsonConfig config = configParser().parse(section, JsonConfig.class);

        assertEquals("", config.jsonProfile());
    }

    /**
     * TP-001 — {@code JsonConfig} carries two profile keys ({@code jsonProfile}, {@code
     * systemProfile}); {@link JsonConfig#effectiveProfile()} floors at the reserved {@code vertique}
     * id and {@link JsonConfig#effectiveSystemProfile()} floors at the reserved {@link
     * JsonProfileId#SYSTEM} id; the retired {@code vertx} id is rejected by {@link
     * JsonDefaultProfileValidator} naming the rename (contracts/json-default-profile.md, "the
     * registry rejects it ... naming the rename").
     *
     * <p>Rows (contract table + T010 TP-001 Given/Then):
     *
     * <ol>
     *   <li>{@code JsonConfig.defaults()} → both accessors floor to ({@code vertique}, {@code
     *       system});
     *   <li>{@code new JsonConfig(" ", " ")} (blank, not null) → same floors as (1) — this is the
     *       decisive row: floor-ing {@code effectiveSystemProfile()} at {@code vertique} instead of
     *       {@code system} would flip rows (1) and (2);
     *   <li>{@code new JsonConfig("system", "vertique")} → explicit values are returned verbatim, not
     *       floored;
     *   <li>{@code new JsonConfig("custom")} (the 1-arg convenience constructor) → {@code
     *       effectiveProfile()} returns the raw {@code custom} id (accessors never validate), {@code
     *       effectiveSystemProfile()} floors to {@code system} (the 1-arg ctor delegates to {@code
     *       (jsonProfile, null)}), and running {@link JsonDefaultProfileValidator} over a registry
     *       seeded with the reserved trio fails because {@code custom} is unknown;
     *   <li>{@code new JsonConfig("vertx", null)} → the validator fails, and the message names the
     *       rename (contains both the retired {@code vertx} id and the {@code system} id it was
     *       renamed to).
     * </ol>
     */
    @Test
    @DisplayName("effectiveProfile/effectiveSystemProfile floor correctly and vertx is rejected naming the rename")
    void effectiveProfilesFloorAndRejectVertx() {
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());

        // Row 1: JsonConfig.defaults() -> both null -> floors (vertique, system).
        JsonConfig defaults = JsonConfig.defaults();
        assertEquals(
                VertiqueJsonMapperProfile.ID,
                defaults.effectiveProfile(),
                "defaults() must floor effectiveProfile() at the reserved vertique id");
        assertEquals(
                JsonProfileId.SYSTEM,
                defaults.effectiveSystemProfile(),
                "defaults() must floor effectiveSystemProfile() at the reserved system id, not vertique");
        assertDoesNotThrow(
                () -> new JsonDefaultProfileValidator(defaults, registry),
                "an unset jsonProfile must validate cleanly against the seeded registry");

        // Row 2: new JsonConfig(" ", " ") -> blank (not null) -> the same floors as row 1. This is
        // the sensitivity row named by TP-001: flooring effectiveSystemProfile() at vertique instead
        // of system would make this assertion (and row 1's) fail.
        JsonConfig blank = new JsonConfig(" ", " ");
        assertEquals(
                VertiqueJsonMapperProfile.ID,
                blank.effectiveProfile(),
                "a blank jsonProfile must floor effectiveProfile() at the reserved vertique id");
        assertEquals(
                JsonProfileId.SYSTEM,
                blank.effectiveSystemProfile(),
                "a blank systemProfile must floor effectiveSystemProfile() at the reserved system id");
        assertDoesNotThrow(
                () -> new JsonDefaultProfileValidator(blank, registry),
                "a blank jsonProfile must validate cleanly against the seeded registry");

        // Row 3: new JsonConfig("system", "vertique") -> explicit values are preserved verbatim.
        JsonConfig explicit = new JsonConfig("system", "vertique");
        assertEquals(
                JsonProfileId.SYSTEM,
                explicit.effectiveProfile(),
                "an explicit jsonProfile must be returned verbatim, not floored");
        assertEquals(
                VertiqueJsonMapperProfile.ID,
                explicit.effectiveSystemProfile(),
                "an explicit systemProfile must be returned verbatim, not floored");
        assertDoesNotThrow(
                () -> new JsonDefaultProfileValidator(explicit, registry),
                "explicit system/vertique values must both validate cleanly");

        // Row 4: new JsonConfig("custom") (1-arg convenience ctor) -> effectiveProfile() returns the
        // raw unknown id (accessors never validate against the registry); effectiveSystemProfile()
        // floors to system because the 1-arg ctor delegates to (jsonProfile, null); the validator
        // fails because "custom" names no registered profile.
        JsonConfig custom = new JsonConfig("custom");
        assertEquals(
                JsonProfileId.of("custom"),
                custom.effectiveProfile(),
                "effectiveProfile() must return the configured id verbatim, unvalidated");
        assertEquals(
                JsonProfileId.SYSTEM,
                custom.effectiveSystemProfile(),
                "the 1-arg constructor must delegate to (jsonProfile, null), so effectiveSystemProfile() floors to system");
        JsonProfileConfigurationException customEx = assertThrows(
                JsonProfileConfigurationException.class,
                () -> new JsonDefaultProfileValidator(custom, registry),
                "an unknown configured jsonProfile ('custom') must fail JsonDefaultProfileValidator");
        assertTrue(
                customEx.getMessage().contains("custom"),
                "the unknown-profile failure must name the offending id: " + customEx.getMessage());

        // Row 5: new JsonConfig("vertx", null) -> the validator fails, naming the rename (vertx -> system).
        JsonConfig vertx = new JsonConfig("vertx", null);
        JsonProfileConfigurationException vertxEx = assertThrows(
                JsonProfileConfigurationException.class,
                () -> new JsonDefaultProfileValidator(vertx, registry),
                "the retired 'vertx' id must be rejected by JsonDefaultProfileValidator");
        assertTrue(
                vertxEx.getMessage().contains("vertx") && vertxEx.getMessage().contains("system"),
                "the vertx rejection must name the rename (mention both 'vertx' and 'system'): "
                        + vertxEx.getMessage());

        // Row 6: new JsonConfig(null, "vertx") -> the system-role key is validated at VALIDATE as well
        // (security review): a retired or unknown json.systemProfile must not boot silently.
        JsonConfig systemVertx = new JsonConfig(null, "vertx");
        JsonProfileConfigurationException systemVertxEx = assertThrows(
                JsonProfileConfigurationException.class,
                () -> new JsonDefaultProfileValidator(systemVertx, registry),
                "json.systemProfile=vertx must be rejected by JsonDefaultProfileValidator");
        assertTrue(
                systemVertxEx.getMessage().contains("vertx")
                        && systemVertxEx.getMessage().contains("system"),
                "the systemProfile vertx rejection must name the rename: " + systemVertxEx.getMessage());
        assertThrows(
                JsonProfileConfigurationException.class,
                () -> new JsonDefaultProfileValidator(new JsonConfig(null, "no-such-profile"), registry),
                "an unknown json.systemProfile must be rejected by JsonDefaultProfileValidator");
        assertDoesNotThrow(
                () -> new JsonDefaultProfileValidator(new JsonConfig(null, "system"), registry),
                "json.systemProfile=system is the reserved baseline and must validate");
    }

    // --- Helpers ---

    /**
     * Returns a minimal {@link ConfigParser} over a plain Jackson {@link ObjectMapper}, sufficient to
     * bind the simple {@link JsonConfig} record through its {@code @JsonCreator}.
     *
     * @return a {@link ConfigParser} whose {@code parse} delegates to a plain {@link ObjectMapper}
     */
    private static ConfigParser configParser() {
        ObjectMapper mapper = new ObjectMapper();
        return new ConfigParser() {
            @Override
            public <T> T parse(JsonObject section, Class<T> type) {
                try {
                    String json = section == null ? "{}" : section.encode();
                    return mapper.readValue(json, type);
                } catch (Exception e) {
                    throw new ConfigurationException("failed to parse config section into " + type.getSimpleName(), e);
                }
            }

            @Override
            public <T> List<T> parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType) {
                throw new UnsupportedOperationException("not needed for JsonConfigTest");
            }

            @Override
            public <T> List<T> parseKeyedObject(
                    JsonObject section, String identityProp, Class<T> elementType, Map<String, Object> fixedProps) {
                throw new UnsupportedOperationException("not needed for JsonConfigTest");
            }
        };
    }
}
