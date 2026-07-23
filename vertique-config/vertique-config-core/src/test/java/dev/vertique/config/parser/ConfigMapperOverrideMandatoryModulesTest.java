// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import dev.vertique.core.config.ConfigParser;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Proves the mandatory-modules contract of {@link DefaultConfigMapper#finalizeForConfig(ObjectMapper)}
 * (FR-CFGI-007 / AC-6): a custom {@code @ConfigMapper} override built <strong>without</strong> the
 * JavaTime module still parses {@code java.time} config correctly, because {@code finalizeForConfig}
 * re-layers JavaTime (plus Jdk8, Vertx, KeyedCollection) over the override in place. The mandatory
 * layering must <em>not</em> drop the override's own application-specific module.
 *
 * <p>The override registers an app-specific {@link SimpleModule} with a custom deserializer for
 * {@link Tag} (upper-cases the incoming string) — a behavior the lenient default mapper does not
 * have — so a passing assertion proves the override's module survived finalization. The same record
 * carries a {@link LocalDate} field that only parses if JavaTime was re-layered, since the override
 * never registered it.
 */
class ConfigMapperOverrideMandatoryModulesTest {

    /** A value type with no Jackson annotations, deserialized only via the override's custom module. */
    record Tag(String value) {}

    /** Custom deserializer proving the override's app-specific module is in effect (upper-cases input). */
    static final class TagDeserializer extends JsonDeserializer<Tag> {
        @Override
        public Tag deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return new Tag(p.getValueAsString().toUpperCase());
        }
    }

    /**
     * Config record exercising both a JavaTime field (re-layered by the framework) and a type handled
     * only by the override's app-specific module.
     *
     * @param releaseDate a JSR310 {@link LocalDate} — parses only if JavaTime is re-layered
     * @param tag a {@link Tag} — parses only if the override's custom module survived finalization
     */
    record AppConfig(LocalDate releaseDate, Tag tag) {}

    /** Builds an override mapper deliberately missing JavaTime but carrying the app-specific Tag module. */
    private static ObjectMapper overrideWithoutJavaTime() {
        SimpleModule appModule = new SimpleModule("app-tag-module");
        appModule.addDeserializer(Tag.class, new TagDeserializer());
        // No JavaTimeModule, no Jdk8Module, no VertxModule, no KeyedCollectionModule.
        return JsonMapper.builder().addModule(appModule).build();
    }

    @Nested
    @DisplayName("via DefaultConfigMapper.finalizeForConfig directly")
    class ViaFinalizeForConfig {

        @Test
        @DisplayName("re-layers JavaTime over an override missing it, and keeps the override's own module")
        void finalizeReLayersJavaTimeAndKeepsAppModule() {
            ObjectMapper finalized = DefaultConfigMapper.finalizeForConfig(overrideWithoutJavaTime());
            ConfigParser parser = new DefaultConfigParser(finalized);

            JsonObject section =
                    new JsonObject().put("releaseDate", "2026-06-25").put("tag", "release");

            AppConfig parsed = parser.parse(section, AppConfig.class);

            assertEquals(
                    LocalDate.parse("2026-06-25"),
                    parsed.releaseDate(),
                    "JavaTime must be re-layered: LocalDate parses though the override never registered it");
            assertEquals(
                    new Tag("RELEASE"),
                    parsed.tag(),
                    "override's app-specific module must survive: custom Tag deserializer still applies");
        }

        @Test
        @DisplayName("finalizeForConfig returns the same instance (no copy)")
        void finalizeReturnsSameInstance() {
            ObjectMapper override = overrideWithoutJavaTime();
            ObjectMapper finalized = DefaultConfigMapper.finalizeForConfig(override);
            // The contract is in-place finalization (copy() throws on JsonMapper subclasses).
            org.junit.jupiter.api.Assertions.assertSame(
                    override, finalized, "finalizeForConfig must finalize in place and return the same instance");
        }
    }

    @Nested
    @DisplayName("wired through the @ConfigMapper seam (DaggerComponent)")
    class ViaSeam {

        @Test
        @DisplayName("the seam-resolved parser re-layers JavaTime and honors the override's module")
        void seamParserReLayersJavaTimeAndKeepsAppModule() {
            // Mirror what ConfigParsingModule does: finalize the @ConfigMapper override in place.
            // (The full Dagger seam is exercised in ConfigParsingModuleSeamTest; here we pin the
            //  same finalize-then-parse behavior the provider performs.)
            ConfigParser parser =
                    new DefaultConfigParser(DefaultConfigMapper.finalizeForConfig(overrideWithoutJavaTime()));

            JsonObject section =
                    new JsonObject().put("releaseDate", "2026-12-31").put("tag", "ga");

            AppConfig parsed = parser.parse(section, AppConfig.class);

            assertEquals(LocalDate.parse("2026-12-31"), parsed.releaseDate(), "JavaTime re-layered through the seam");
            assertEquals(new Tag("GA"), parsed.tag(), "override module preserved through the seam");
        }
    }
}
