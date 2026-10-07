// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.avro;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.kafka.avro.it.composed.Composed;
import dev.vertique.kafka.avro.it.nested.NestedPayload;
import dev.vertique.kafka.avro.it.pkg.array.ArrayPackaged;
import dev.vertique.kafka.avro.it.pkg.csv.CsvPackaged;
import dev.vertique.kafka.avro.it.serializeronly.SerializerOnly;
import dev.vertique.kafka.avro.it.untrusted.Unlisted;
import dev.vertique.kafka.config.KafkaConfig;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.util.ClassSecurityValidator;
import org.apache.avro.util.ClassSecurityValidator.ClassSecurityPredicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the provider extends Avro's class allow-list at the point Avro enforces it:
 * {@link SpecificData#getClass(Schema)} on a fresh {@code SpecificData} (no class cache), the same
 * call Apicurio's datum providers make. Every test uses its own generated fixture because the trust
 * is JVM-wide and additive, so a class registered by one test would mask the red state of another.
 */
class AvroClassTrustTest {

    private static final String REGISTRY = "http://localhost:8080/apis/registry/v3";

    private final ApicurioAvroSerdeProvider provider = new ApicurioAvroSerdeProvider(
            KafkaConfig.fromConfig(new JsonObject(), new DefaultConfigParser(DefaultConfigMapper.lenient())));

    private ClassSecurityPredicate originalGlobal;

    @BeforeEach
    void rememberGlobal() {
        originalGlobal = ClassSecurityValidator.getGlobal();
    }

    @AfterEach
    void restoreGlobal() {
        ClassSecurityValidator.setGlobal(originalGlobal);
    }

    private static JsonObject config(Object trustedPackages) {
        JsonObject serdeProperties = new JsonObject();
        if (trustedPackages != null) {
            serdeProperties.put(AvroClassTrust.TRUSTED_PACKAGES_KEY, trustedPackages);
        }
        return new JsonObject()
                .put("schemaRegistry", new JsonObject().put("url", REGISTRY))
                .put("serdeProperties", serdeProperties);
    }

    private static void resolve(Schema schema) {
        new SpecificData().getClass(schema);
    }

    @Test
    @DisplayName("deserializer(type) trusts the declared record and every nested record, enum and fixed")
    void declaredTypeTrustCoversNestedNamedSchemas() {
        Schema payload = NestedPayload.getClassSchema();
        Schema status = payload.getField("status").schema();
        Schema checksum = payload.getField("checksum").schema();
        Schema line = payload.getField("line").schema();

        assertThrows(SecurityException.class, () -> resolve(payload), "untrusted before any serde is built");

        provider.deserializer(NestedPayload.class, config(null));

        assertDoesNotThrow(() -> resolve(payload));
        assertDoesNotThrow(() -> resolve(status));
        assertDoesNotThrow(() -> resolve(checksum));
        assertDoesNotThrow(() -> resolve(line));
        assertDoesNotThrow(
                () -> resolve(payload.getField("note").schema().getTypes().get(1)));
    }

    @Test
    @DisplayName("serializer(type) trusts the declared record too (the write path is gated as well)")
    void serializerBuildTrustsDeclaredType() {
        assertThrows(SecurityException.class, () -> resolve(SerializerOnly.getClassSchema()));

        provider.serializer(SerializerOnly.class, config(null));

        assertDoesNotThrow(() -> resolve(SerializerOnly.getClassSchema()));
    }

    @Test
    @DisplayName("a class nobody declared stays forbidden after other serdes are built")
    void undeclaredClassStaysForbidden() {
        provider.serializer(Composed.class, config(null));

        assertThrows(SecurityException.class, () -> resolve(Unlisted.getClassSchema()));
    }

    @Test
    @DisplayName("routingDeserializer trusts only the configured package prefix (array form)")
    void routingTrustsConfiguredPackageArray() {
        Schema trusted = ArrayPackaged.getClassSchema();
        assertThrows(SecurityException.class, () -> resolve(trusted));

        provider.routingDeserializer(config(new JsonArray().add("dev.vertique.kafka.avro.it.pkg.array")));

        assertDoesNotThrow(() -> resolve(trusted));
        assertThrows(SecurityException.class, () -> resolve(Unlisted.getClassSchema()));
    }

    @Test
    @DisplayName("routingDeserializer accepts the comma-separated string form and trims entries")
    void routingTrustsConfiguredPackageCsv() {
        Schema trusted = CsvPackaged.getClassSchema();
        assertThrows(SecurityException.class, () -> resolve(trusted));

        provider.routingDeserializer(config(" com.example.unrelated , dev.vertique.kafka.avro.it.pkg.csv ,"));

        assertDoesNotThrow(() -> resolve(trusted));
    }

    @Test
    @DisplayName("a package prefix does not match a sibling that merely shares the leading characters")
    void packagePrefixRespectsSegmentBoundary() {
        assertFalse(AvroClassTrust.isTrusted("dev.vertique.kafka.avro.it.nope.Thing"));
        AvroClassTrust.register(config(new JsonArray().add("dev.vertique.kafka.avro.it.boundary")));

        assertTrue(AvroClassTrust.isTrusted("dev.vertique.kafka.avro.it.boundary.Thing"));
        assertTrue(AvroClassTrust.isTrusted("dev.vertique.kafka.avro.it.boundary.sub.Thing"));
        assertFalse(AvroClassTrust.isTrusted("dev.vertique.kafka.avro.it.boundaryX.Thing"));
    }

    @Test
    @DisplayName("wildcards, single-segment roots, class-like junk and non-list values are rejected at build time")
    void malformedPackageListsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> provider.routingDeserializer(config("*")));
        assertThrows(IllegalArgumentException.class, () -> provider.routingDeserializer(config("com.acme.*")));
        assertThrows(IllegalArgumentException.class, () -> provider.routingDeserializer(config("com")));
        assertThrows(
                IllegalArgumentException.class,
                () -> provider.routingDeserializer(
                        config(new JsonArray().add("com.acme").add("*"))));
        assertThrows(IllegalArgumentException.class, () -> provider.routingDeserializer(config(42)));
        assertThrows(IllegalArgumentException.class, () -> provider.deserializer(Composed.class, config("*")));
    }

    @Test
    @DisplayName("existing global trust is kept, and trust is re-applied after another component replaces the global")
    void composesAdditivelyAndSurvivesReplacement() {
        ClassSecurityValidator.setGlobal(
                ClassSecurityValidator.composite(ClassSecurityValidator.getGlobal(), type -> type == UUID.class));

        provider.serializer(Composed.class, config(null));

        assertTrue(ClassSecurityValidator.getGlobal().isTrusted(UUID.class), "application trust is preserved");
        assertTrue(ClassSecurityValidator.getGlobal().isTrusted(Composed.class));

        // Another component replaces the global wholesale; the next serde build restores our trust.
        ClassSecurityValidator.setGlobal(ClassSecurityValidator.builder().build());
        assertFalse(ClassSecurityValidator.getGlobal().isTrusted(Composed.class));

        provider.serializer(Composed.class, config(null));

        assertTrue(ClassSecurityValidator.getGlobal().isTrusted(Composed.class));
    }
}
