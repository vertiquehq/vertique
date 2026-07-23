// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.core.config.ConfigMapper;
import dev.vertique.core.config.ConfigParser;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger seam test for {@link ConfigParsingModule} (AC-4 / AC-5). Proves that:
 *
 * <ul>
 *   <li><strong>AC-4</strong> — a {@code @Component} listing only {@link ConfigParsingModule} (no
 *       {@code @ConfigMapper} provider) resolves a non-null {@link ConfigParser} — a
 *       {@link DefaultConfigParser} over the lenient default — and parses a fixture exactly as the
 *       direct lenient parser does;
 *   <li><strong>AC-5</strong> — a second {@code @Component} that also installs an
 *       {@link OverrideModule} binding {@code @Provides @ConfigMapper ObjectMapper} resolves exactly
 *       one {@link ConfigParser} binding (no duplicate-binding error) and the app-specific override
 *       module is in effect.
 * </ul>
 *
 * <p>The {@code @ConfigMapper} provider in the override case is finalized in place by the seam
 * (mandatory modules + lenient policy re-layered), so its app-specific custom deserializer survives
 * while the framework guarantees still hold.
 */
class ConfigParsingModuleSeamTest {

    /** A value type the override's custom module deserializes (upper-casing the string). */
    record Marker(String value) {}

    /** Custom deserializer proving the override mapper is the one actually parsing config. */
    static final class MarkerDeserializer extends JsonDeserializer<Marker> {
        @Override
        public Marker deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return new Marker(p.getValueAsString().toUpperCase());
        }
    }

    /** A simple record probing default behavior in the no-override case. */
    record Plain(String name, int port) {}

    /** A record carrying the override-only {@link Marker} type. */
    record WithMarker(Marker marker) {}

    // --- AC-4: no @ConfigMapper provider ---

    @Singleton
    @Component(modules = ConfigParsingModule.class)
    interface DefaultComponent {
        ConfigParser configParser();
    }

    @Test
    @DisplayName("AC-4: component with only ConfigParsingModule resolves the lenient default parser")
    void noOverrideResolvesLenientDefaultParser() {
        DefaultComponent component = DaggerConfigParsingModuleSeamTest_DefaultComponent.create();

        ConfigParser parser = component.configParser();

        assertNotNull(parser, "ConfigParser must resolve without any @ConfigMapper provider");
        assertInstanceOf(DefaultConfigParser.class, parser, "default binding must be a DefaultConfigParser");

        // Parses a fixture exactly as the direct lenient parser does (lenient coercion + unknown-key tolerance).
        JsonObject section =
                new JsonObject().put("name", "svc").put("port", "8080").put("extra", "ignored");
        Plain viaSeam = parser.parse(section, Plain.class);
        Plain viaDirect = new DefaultConfigParser(DefaultConfigMapper.lenient()).parse(section, Plain.class);

        assertEquals(viaDirect, viaSeam, "seam default parser must match the direct lenient parser");
        assertEquals("svc", viaSeam.name());
        assertEquals(8080, viaSeam.port(), "lenient coercion applies through the seam default");
    }

    // --- AC-5: @ConfigMapper override present ---

    /**
     * Override module supplying an app-specific {@code @ConfigMapper} mapper. The mapper registers a
     * custom {@link Marker} deserializer the lenient default does not have; the seam finalizes it in
     * place, so app-specific behavior and framework guarantees coexist.
     */
    @Module
    static final class OverrideModule {
        @Provides
        @ConfigMapper
        static ObjectMapper configMapper() {
            SimpleModule appModule = new SimpleModule("seam-marker-module");
            appModule.addDeserializer(Marker.class, new MarkerDeserializer());
            return JsonMapper.builder().addModule(appModule).build();
        }
    }

    @Singleton
    @Component(modules = {ConfigParsingModule.class, OverrideModule.class})
    interface OverrideComponent {
        ConfigParser configParser();
    }

    @Test
    @DisplayName("AC-5: component with a @ConfigMapper override resolves one binding with the override in effect")
    void overrideResolvesSingleBindingWithAppModule() {
        OverrideComponent component = DaggerConfigParsingModuleSeamTest_OverrideComponent.create();

        // Exactly one ConfigParser binding resolves — a duplicate binding would fail Dagger codegen,
        // so reaching this point already proves no duplicate-binding error.
        ConfigParser parser = component.configParser();

        assertNotNull(parser, "ConfigParser must resolve with the @ConfigMapper override present");
        assertInstanceOf(DefaultConfigParser.class, parser);

        // The app-specific module is in effect: the lenient default has no deserializer mapping a
        // bare JSON string to Marker, whereas the override's custom deserializer upper-cases it.
        WithMarker parsed = parser.parse(new JsonObject().put("marker", "release"), WithMarker.class);
        assertEquals(
                new Marker("RELEASE"),
                parsed.marker(),
                "the @ConfigMapper override's custom Marker deserializer must be in effect");
    }
}
