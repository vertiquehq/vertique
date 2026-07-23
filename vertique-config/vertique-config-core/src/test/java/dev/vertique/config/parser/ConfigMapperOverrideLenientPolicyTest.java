// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.core.config.ConfigMapper;
import dev.vertique.core.config.ConfigParser;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Proves the <strong>mandatory-leniency-wins</strong> half of FR-CFGI-007: a custom
 * {@code @ConfigMapper} override that is deliberately <em>hostile</em> to config parsing — built with
 * scalar coercion DISABLED ({@code MapperFeature.ALLOW_COERCION_OF_SCALARS} off) and
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} ENABLED — still parses config leniently once the seam finalizes
 * it, because {@link DefaultConfigMapper#finalizeForConfig(ObjectMapper)} re-asserts the framework's
 * lenient policy <em>in place</em> over the override.
 *
 * <p>Where {@link ConfigMapperOverrideMandatoryModulesTest} proves the override's mandatory
 * <em>modules</em> are re-layered, this test proves the override's lenient <em>policy</em> wins:
 *
 * <ul>
 *   <li><strong>coercion re-asserted</strong> — a record {@code int} field parses from the JSON
 *       string {@code "8080"} even though the override disabled scalar coercion; and
 *   <li><strong>FAIL_ON_UNKNOWN re-disabled</strong> — an unknown JSON property is ignored even
 *       though the override enabled {@code FAIL_ON_UNKNOWN_PROPERTIES} (which would otherwise throw).
 * </ul>
 *
 * <p>Both paths are exercised: {@link DefaultConfigMapper#finalizeForConfig(ObjectMapper)} directly,
 * and the Dagger {@link ConfigParsingModule} seam wired with a {@code @Provides @ConfigMapper} that
 * returns the hostile strict mapper.
 */
class ConfigMapperOverrideLenientPolicyTest {

    /**
     * Config record with an {@code int} field (proves string→int coercion was re-asserted). The
     * fixtures additionally carry an unknown JSON property, proving FAIL_ON_UNKNOWN was re-disabled.
     *
     * @param name an arbitrary string field
     * @param port an {@code int} field that only binds from {@code "8080"} if coercion is lenient
     */
    record StrictProbe(String name, int port) {}

    /**
     * Builds a mapper hostile to config parsing: scalar coercion disabled and
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} enabled. Absent the framework's re-layering, this mapper
     * would reject both a string-encoded {@code int} and an unknown property.
     *
     * @return the hostile strict override mapper
     */
    private static ObjectMapper hostileStrictOverride() {
        return JsonMapper.builder()
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    @Nested
    @DisplayName("via DefaultConfigMapper.finalizeForConfig directly")
    class ViaFinalizeForConfig {

        @Test
        @DisplayName("re-asserts lenient coercion + unknown-tolerance over a hostile strict override")
        void finalizeReAssertsLenientPolicy() {
            ObjectMapper finalized = DefaultConfigMapper.finalizeForConfig(hostileStrictOverride());
            ConfigParser parser = new DefaultConfigParser(finalized);

            // port is a JSON STRING (coercion would fail on the raw override); "extra" is unknown
            // (FAIL_ON_UNKNOWN would throw on the raw override).
            JsonObject section =
                    new JsonObject().put("name", "svc").put("port", "8080").put("extra", "ignored");

            StrictProbe parsed = parser.parse(section, StrictProbe.class);

            assertEquals("svc", parsed.name());
            assertEquals(
                    8080,
                    parsed.port(),
                    "lenient coercion must be re-asserted: string \"8080\" binds to int though the override disabled it");
        }
    }

    @Nested
    @DisplayName("wired through the @ConfigMapper seam (DaggerComponent)")
    class ViaSeam {

        /**
         * Override module supplying the hostile strict {@code @ConfigMapper} mapper. The seam
         * finalizes it in place, so the framework's lenient policy is re-asserted over it.
         */
        @Module
        static final class HostileOverrideModule {
            @Provides
            @ConfigMapper
            static ObjectMapper configMapper() {
                return hostileStrictOverride();
            }
        }

        @Singleton
        @Component(modules = {ConfigParsingModule.class, HostileOverrideModule.class})
        interface HostileOverrideComponent {
            ConfigParser configParser();
        }

        @Test
        @DisplayName("seam-resolved parser parses leniently though the override was strict")
        void seamReAssertsLenientPolicy() {
            HostileOverrideComponent component =
                    DaggerConfigMapperOverrideLenientPolicyTest_ViaSeam_HostileOverrideComponent.create();

            ConfigParser parser = component.configParser();
            assertNotNull(parser, "ConfigParser must resolve with a hostile @ConfigMapper override present");

            JsonObject section =
                    new JsonObject().put("name", "svc").put("port", "9090").put("extra", "ignored");

            StrictProbe parsed = parser.parse(section, StrictProbe.class);

            assertEquals("svc", parsed.name());
            assertEquals(
                    9090, parsed.port(), "lenient coercion re-asserted through the seam: string \"9090\" binds to int");
        }
    }
}
