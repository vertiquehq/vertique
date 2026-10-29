// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import io.vertx.core.json.jackson.VertxModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that state is read through the configured process codec: the profile's read limits and parser leniency
 * apply, a profile swap is picked up, and numbers stay exact under the profile mapper.
 */
class ExactJsonProfileMapperTest {

    private static final JsonProfileId PROFILE = JsonProfileId.of("exact-json-test");

    @AfterEach
    void restoreRawCodec() {
        VertiqueJson.resetForTests();
    }

    private static ObjectMapper profileMapper(JsonFactory factory) {
        return new ObjectMapper(factory).registerModule(new VertxModule());
    }

    private static String nested(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }

    @Test
    @DisplayName("a state deeper than the default nesting limit is read when the profile raises the limit")
    void readsWithRaisedReadLimit() {
        String deep = nested(1500);
        assertThatThrownBy(() -> ExactJson.read(deep))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A state could not be read as JSON");

        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(
                        StreamReadConstraints.builder().maxNestingDepth(2000).build())
                .build();
        VertiqueJson.install(PROFILE, profileMapper(factory));

        assertThat(ExactJson.read(deep).isArray()).isTrue();
    }

    @Test
    @DisplayName("a state with a non-numeric number is read when the profile allows it")
    void readsNonNumericNumbersWhenProfileAllows() {
        String state = "{\"x\":NaN}";
        VertiqueJson.install(PROFILE, profileMapper(new JsonFactory()));
        assertThatThrownBy(() -> ExactJson.read(state)).isInstanceOf(IllegalArgumentException.class);

        JsonFactory lenient = JsonFactory.builder()
                .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .build();
        VertiqueJson.install(PROFILE, profileMapper(lenient));

        JsonNode x = ExactJson.read(state).get("x");
        assertThat(x.isNumber()).isTrue();
        assertThat(Double.isNaN(x.doubleValue())).isTrue();
    }

    @Test
    @DisplayName("numbers stay exact under the profile mapper")
    void keepsExactNumbersUnderProfileMapper() {
        VertiqueJson.install(PROFILE, profileMapper(new JsonFactory()));

        assertThat(ExactJson.read("1")).isNotEqualTo(ExactJson.read("1.0"));
        assertThat(ExactJson.read("1.0")).isEqualTo(ExactJson.read("1.00"));
        assertThat(ExactJson.read("0.1234567890123456789")).isNotEqualTo(ExactJson.read("0.12345678901234568"));
        assertThat(ExactJson.read("{\"a\":0.1234567890123456789}").toString())
                .isEqualTo("{\"a\":0.1234567890123456789}");
    }
}
