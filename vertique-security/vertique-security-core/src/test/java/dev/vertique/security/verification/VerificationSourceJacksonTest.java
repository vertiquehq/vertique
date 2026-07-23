// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Jackson round-trip serialization tests for every {@link VerificationSource} permit.
 *
 * <p>Each test builds an instance, serializes to JSON, asserts the {@code "type"} discriminator
 * is present, deserializes back to {@link VerificationSource}, and asserts equality with the
 * original.
 */
class VerificationSourceJacksonTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUpMapper() {
        mapper = new ObjectMapper();
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new JavaTimeModule());
    }

    // --- JwksVerificationSource ---

    @Test
    @DisplayName("JwksVerificationSource round-trips with type=jwks")
    void jwksRoundTrip() throws Exception {
        JwksVerificationSource original = new JwksVerificationSource(
                Optional.of("https://idp.example.com"),
                Optional.of("https://idp.example.com/.well-known/jwks.json"),
                Optional.of("key-001"),
                Optional.of("RS256"));

        String json = mapper.writeValueAsString(original);

        assertTrue(json.contains("\"type\":\"jwks\""), "JSON must contain type=jwks: " + json);
        VerificationSource deserialized = mapper.readValue(json, VerificationSource.class);
        assertInstanceOf(JwksVerificationSource.class, deserialized);
        assertEquals(original, deserialized);
    }

    @Test
    @DisplayName("JwksVerificationSource round-trips with empty optionals")
    void jwksRoundTripEmptyOptionals() throws Exception {
        JwksVerificationSource original =
                new JwksVerificationSource(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        String json = mapper.writeValueAsString(original);

        assertTrue(json.contains("\"type\":\"jwks\""), "JSON must contain type=jwks");
        VerificationSource deserialized = mapper.readValue(json, VerificationSource.class);
        assertEquals(original, deserialized);
    }

    // --- IntrospectionVerificationSource ---

    @Test
    @DisplayName("IntrospectionVerificationSource round-trips with type=introspection")
    void introspectionRoundTrip() throws Exception {
        IntrospectionVerificationSource original = new IntrospectionVerificationSource(
                "https://idp.example.com/introspect", IntrospectionResponseShape.RFC7662_JSON);

        String json = mapper.writeValueAsString(original);

        assertTrue(json.contains("\"type\":\"introspection\""), "JSON must contain type=introspection: " + json);
        VerificationSource deserialized = mapper.readValue(json, VerificationSource.class);
        assertInstanceOf(IntrospectionVerificationSource.class, deserialized);
        assertEquals(original, deserialized);
    }

    // --- ApiKeyRegistryVerificationSource ---

    @Test
    @DisplayName("ApiKeyRegistryVerificationSource round-trips with type=api-key-registry")
    void apiKeyRegistryRoundTrip() throws Exception {
        ApiKeyRegistryVerificationSource original = new ApiKeyRegistryVerificationSource("default-registry");

        String json = mapper.writeValueAsString(original);

        assertTrue(json.contains("\"type\":\"api-key-registry\""), "JSON must contain type=api-key-registry: " + json);
        VerificationSource deserialized = mapper.readValue(json, VerificationSource.class);
        assertInstanceOf(ApiKeyRegistryVerificationSource.class, deserialized);
        assertEquals(original, deserialized);
    }

    // --- MtlsTrustStoreVerificationSource ---

    @Test
    @DisplayName("MtlsTrustStoreVerificationSource round-trips with type=mtls-trust-store")
    void mtlsTrustStoreRoundTrip() throws Exception {
        MtlsTrustStoreVerificationSource original =
                new MtlsTrustStoreVerificationSource("primary-trust-store", Optional.of("CN=Partner CA"));

        String json = mapper.writeValueAsString(original);

        assertTrue(json.contains("\"type\":\"mtls-trust-store\""), "JSON must contain type=mtls-trust-store: " + json);
        VerificationSource deserialized = mapper.readValue(json, VerificationSource.class);
        assertInstanceOf(MtlsTrustStoreVerificationSource.class, deserialized);
        assertEquals(original, deserialized);
    }

    // --- HmacSecretResolverVerificationSource ---

    @Test
    @DisplayName("HmacSecretResolverVerificationSource round-trips with type=hmac-secret-resolver")
    void hmacSecretResolverRoundTrip() throws Exception {
        HmacSecretResolverVerificationSource original =
                new HmacSecretResolverVerificationSource("webhook-resolver", "HmacSHA256");

        String json = mapper.writeValueAsString(original);

        assertTrue(
                json.contains("\"type\":\"hmac-secret-resolver\""),
                "JSON must contain type=hmac-secret-resolver: " + json);
        VerificationSource deserialized = mapper.readValue(json, VerificationSource.class);
        assertInstanceOf(HmacSecretResolverVerificationSource.class, deserialized);
        assertEquals(original, deserialized);
    }

    // --- BasicCredentialVerifierVerificationSource ---

    @Test
    @DisplayName("BasicCredentialVerifierVerificationSource round-trips with type=basic-credential-verifier")
    void basicCredentialVerifierRoundTrip() throws Exception {
        BasicCredentialVerifierVerificationSource original =
                new BasicCredentialVerifierVerificationSource("ldap-verifier");

        String json = mapper.writeValueAsString(original);

        assertTrue(
                json.contains("\"type\":\"basic-credential-verifier\""),
                "JSON must contain type=basic-credential-verifier: " + json);
        VerificationSource deserialized = mapper.readValue(json, VerificationSource.class);
        assertInstanceOf(BasicCredentialVerifierVerificationSource.class, deserialized);
        assertEquals(original, deserialized);
    }

    // --- CustomVerificationSource ---

    @Test
    @DisplayName("CustomVerificationSource round-trips with type=custom and customType preserved")
    void customRoundTrip() throws Exception {
        CustomVerificationSource original =
                new CustomVerificationSource("vendor-x", Map.of("region", "eu-west-1", "tier", "gold"));

        String json = mapper.writeValueAsString(original);

        assertTrue(json.contains("\"type\":\"custom\""), "JSON must contain type=custom: " + json);
        assertTrue(json.contains("\"customType\":\"vendor-x\""), "JSON must contain customType=vendor-x: " + json);
        VerificationSource deserialized = mapper.readValue(json, VerificationSource.class);
        assertInstanceOf(CustomVerificationSource.class, deserialized);
        assertEquals(original, deserialized);
    }

    @Test
    @DisplayName("CustomVerificationSource with empty attributes round-trips correctly")
    void customRoundTripEmptyAttributes() throws Exception {
        CustomVerificationSource original = new CustomVerificationSource("my-custom-auth", Map.of());

        String json = mapper.writeValueAsString(original);

        assertTrue(json.contains("\"type\":\"custom\""), "JSON must contain type=custom");
        VerificationSource deserialized = mapper.readValue(json, VerificationSource.class);
        assertEquals(original, deserialized);
    }
}
