// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed interface representing the source that was used to verify an authentication credential.
 *
 * <p>Each permit corresponds to a distinct verification mechanism:
 * <ul>
 *   <li>{@link JwksVerificationSource} — JSON Web Key Set (JWT signature verification)</li>
 *   <li>{@link IntrospectionVerificationSource} — OAuth 2.0 token introspection endpoint</li>
 *   <li>{@link ApiKeyRegistryVerificationSource} — application-managed API key registry</li>
 *   <li>{@link MtlsTrustStoreVerificationSource} — mutual TLS trust store</li>
 *   <li>{@link HmacSecretResolverVerificationSource} — HMAC shared-secret resolver</li>
 *   <li>{@link BasicCredentialVerifierVerificationSource} — Basic authentication credential verifier</li>
 *   <li>{@link CustomVerificationSource} — application-defined verification mechanism</li>
 * </ul>
 *
 * <p>Jackson polymorphic typing is configured via the {@code "type"} property discriminator.
 * The discriminator values are stable identifiers (e.g., {@code "jwks"}, {@code "introspection"})
 * and are safe to persist in audit records.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = JwksVerificationSource.class, name = "jwks"),
    @JsonSubTypes.Type(value = IntrospectionVerificationSource.class, name = "introspection"),
    @JsonSubTypes.Type(value = ApiKeyRegistryVerificationSource.class, name = "api-key-registry"),
    @JsonSubTypes.Type(value = MtlsTrustStoreVerificationSource.class, name = "mtls-trust-store"),
    @JsonSubTypes.Type(value = HmacSecretResolverVerificationSource.class, name = "hmac-secret-resolver"),
    @JsonSubTypes.Type(value = BasicCredentialVerifierVerificationSource.class, name = "basic-credential-verifier"),
    @JsonSubTypes.Type(value = CustomVerificationSource.class, name = "custom")
})
public sealed interface VerificationSource
        permits JwksVerificationSource,
                IntrospectionVerificationSource,
                ApiKeyRegistryVerificationSource,
                MtlsTrustStoreVerificationSource,
                HmacSecretResolverVerificationSource,
                BasicCredentialVerifierVerificationSource,
                CustomVerificationSource {}
