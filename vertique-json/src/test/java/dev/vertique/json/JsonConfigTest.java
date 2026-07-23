// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
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
 *   <li>{@code {"jsonProfile":""}} yields {@code jsonProfile()==""} (blank is preserved; the validator
 *       and inline tail treat blank as no default ⇒ vertx).
 * </ul>
 *
 * <p>{@code vertique-json} cannot depend on {@code vertique-config-core} (which depends back on
 * {@code vertique-json}, a reactor cycle), so this test supplies a minimal {@link ConfigParser} over
 * a plain Jackson {@link ObjectMapper}; the {@link JsonConfig} record is a single nullable string with
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
    @DisplayName("unset is vertx default")
    void unsetIsVertxDefault() {
        JsonConfig config = configParser().parse(new JsonObject(), JsonConfig.class);

        assertNull(config.jsonProfile(), "absent jsonProfile must be null (⇒ vertx)");
        assertEquals(JsonConfig.defaults(), config);
    }

    @Test
    @DisplayName("blank stays blank")
    void blankStaysBlank() {
        JsonObject section = new JsonObject().put("jsonProfile", "");

        JsonConfig config = configParser().parse(section, JsonConfig.class);

        assertEquals("", config.jsonProfile());
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
