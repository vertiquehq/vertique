// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.security.IdentitySnapshotFactory;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.time.Clock;

/**
 * Dagger module that wires the config-backed identity-snapshot keyset and registers the
 * snapshot's durable-carriage encoder/decoder pair into the substrate's multibinding sets
 * (PRD-ID-002 §14.3 "Durable carriage").
 *
 * <p>Reads the {@code identity.snapshot} section of the application config and provides:
 * <ul>
 *   <li>{@link IdentitySnapshotConfig} — the parsed, validated section.</li>
 *   <li>{@link SnapshotHmac} — built from {@link IdentitySnapshotConfig#hmacKeys()}, replacing the
 *       process-lifetime placeholder keyset {@code PrivilegedIdentityModule} previously
 *       self-provisioned.</li>
 *   <li>{@link IdentitySnapshotCodec} — the codec {@link IdentityReconstruction} and the durable
 *       encoder/decoder below all share.</li>
 *   <li>{@link IdentitySnapshotFactory} ({@link DefaultIdentitySnapshotFactory}) — the credential-free
 *       capture interface (fixed framework implementation in V1; this unconditional binding is why an
 *       application {@code @Provides} of the type is a duplicate-binding error) used to build a
 *       to-be-signed {@link dev.vertique.security.IdentitySnapshot} from a live
 *       {@link dev.vertique.security.SecurityContext}.</li>
 *   <li>{@link IdentitySnapshotCapture} — the producer-side capture seam, constructed with the
 *       {@code captureEnabled} kill-switch read from {@link IdentitySnapshotConfig}.</li>
 *   <li>{@code @Provides @IntoSet DurableContextMetadataEncoder}/{@code Decoder} for
 *       {@link IdentitySnapshotContext}, contributed into the empty multibinding sets declared by
 *       {@code vertique-context}'s {@code ContextRuntimeModule} (mirrors
 *       {@code CorrelationContextModule}'s {@code correlationDurableEncoder}/
 *       {@code correlationDurableDecoder} in {@code vertique-correlation}).</li>
 * </ul>
 *
 * <p>Applications wiring identity-snapshot durable carriage must include this module — without it,
 * the encoder/decoder pair is never registered and the carriage path is inert (the substrate's
 * empty multibinding sets simply skip {@code IdentitySnapshotContext}).
 *
 * <p>Config path: {@code identity.snapshot} — for example:
 * <pre>{@code
 * identity:
 *   snapshot:
 *     captureEnabled: true
 *     onDegradation: FAIL
 *     hmacKeys:
 *       active:
 *         keyId: key-2026-07
 *         secretRef: ${IDENTITY_SNAPSHOT_HMAC_KEY}
 *       previous:
 *         - keyId: key-2026-06
 *           secretRef: ${IDENTITY_SNAPSHOT_HMAC_KEY_PREVIOUS}
 * }</pre>
 */
@Module
public abstract class IdentitySnapshotCarriageModule {

    private IdentitySnapshotCarriageModule() {
        /* Dagger abstract module — no instances */
    }

    /**
     * Provides the parsed {@link IdentitySnapshotConfig} from the {@code identity.snapshot}
     * section of the application config.
     *
     * @param config the full application config injected via {@code @VertxConfig}
     * @param parser the injected config parser
     * @return the parsed identity-snapshot configuration; never {@code null}
     */
    @Provides
    @Singleton
    static IdentitySnapshotConfig identitySnapshotConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(
                JsonConfigPaths.navigateObject(config, "identity", "snapshot"), IdentitySnapshotConfig.class);
    }

    /**
     * Provides the config-backed {@link SnapshotHmac} keyset, replacing the process-lifetime
     * placeholder keyset {@code PrivilegedIdentityModule} previously self-provisioned.
     *
     * @param config the parsed identity-snapshot configuration
     * @return the config-backed HMAC keyset; never {@code null}
     */
    @Provides
    @Singleton
    static SnapshotHmac snapshotHmac(IdentitySnapshotConfig config) {
        return config.hmacKeys().toSnapshotHmac();
    }

    /**
     * Provides the config-backed {@link SnapshotFreshnessPolicy}: the operator-configured freshness
     * budgets and clock skew from {@link IdentitySnapshotConfig}, over the system UTC clock (PRD-ID-002
     * §14.6 amendment A9, the F5 freshness defense).
     *
     * @param config the parsed identity-snapshot configuration supplying the freshness budgets and
     *               clock skew
     * @return the config-backed freshness policy; never {@code null}
     */
    @Provides
    @Singleton
    static SnapshotFreshnessPolicy snapshotFreshnessPolicy(IdentitySnapshotConfig config) {
        return new SnapshotFreshnessPolicy(
                config.maxCarrierLifetime(), config.maxSnapshotLifetime(), config.clockSkew(), Clock.systemUTC());
    }

    /**
     * Provides the {@link IdentitySnapshotCodec} singleton, backed by the config-backed
     * {@link SnapshotHmac} keyset and {@link SnapshotFreshnessPolicy}.
     *
     * @param hmac            the HMAC signer/verifier the codec delegates signing and verification to
     * @param freshnessPolicy the fail-closed freshness policy the codec applies at decode
     * @return a new {@link IdentitySnapshotCodec}; never {@code null}
     */
    @Provides
    @Singleton
    static IdentitySnapshotCodec identitySnapshotCodec(SnapshotHmac hmac, SnapshotFreshnessPolicy freshnessPolicy) {
        return new IdentitySnapshotCodec(hmac, freshnessPolicy);
    }

    /**
     * Provides the {@link IdentitySnapshotFactory} singleton used to capture a to-be-signed
     * {@link dev.vertique.security.IdentitySnapshot} from a live
     * {@link dev.vertique.security.SecurityContext}.
     *
     * @param holder the context holder the factory reads the ambient
     *               {@link dev.vertique.security.authz.InvocationOrigin} from at capture time
     *               (identity-002 P2.S5b-i); must not be {@code null}
     * @return a new {@link DefaultIdentitySnapshotFactory}; never {@code null}
     */
    @Provides
    @Singleton
    static IdentitySnapshotFactory identitySnapshotFactory(ContextHolder holder) {
        return new DefaultIdentitySnapshotFactory(holder);
    }

    /**
     * Provides the {@link IdentitySnapshotCapture} ingress seam consumed by {@code rest-security}'s
     * {@code IdentityResolutionMiddleware} via {@code @BindsOptionalOf}.
     *
     * <p>Provided here (not via an {@code @Inject} constructor) so the middleware's
     * {@code Optional<IdentitySnapshotCapture>} resolves to empty unless this module is installed —
     * Dagger rejects {@code @BindsOptionalOf} of an unqualified {@code @Inject} type. Mirrors
     * {@link #identitySnapshotDegradationPolicy(IdentitySnapshotConfig)}: both are carriage-provided
     * optionals the REST/services runtime reads only when durable carriage is wired.
     *
     * <p>The {@code captureEnabled} kill-switch is read from the injected typed
     * {@link IdentitySnapshotConfig} (config.md R5) rather than an unqualified {@code @Provides boolean},
     * which would be a positional collision hazard on the Dagger graph.
     *
     * @param holder  the context holder the capture binds an {@link IdentitySnapshotContext} into;
     *                must not be {@code null}
     * @param factory the credential-free snapshot factory the capture builds snapshots with; must
     *                not be {@code null}
     * @param config  the parsed identity-snapshot configuration supplying the
     *                {@code captureEnabled} kill-switch
     * @return the singleton capture seam; never {@code null}
     */
    @Provides
    @Singleton
    static IdentitySnapshotCapture identitySnapshotCapture(
            ContextHolder holder, IdentitySnapshotFactory factory, IdentitySnapshotConfig config) {
        return new IdentitySnapshotCapture(holder, factory, config.captureEnabled());
    }

    /**
     * Provides the resolved {@code identity.snapshot.onDegradation} policy, consumed by
     * {@code dev.vertique.services.interceptor.SnapshotDegradationGate} (in {@code
     * vertique-services}) to decide whether an unverifiable carried snapshot fails or continues
     * dispatch.
     *
     * @param config the parsed identity-snapshot configuration
     * @return the resolved degradation policy; never {@code null}
     */
    @Provides
    static IdentitySnapshotDegradationPolicy identitySnapshotDegradationPolicy(IdentitySnapshotConfig config) {
        return config.onDegradation();
    }

    /**
     * Contributes {@link IdentitySnapshotDurableEncoder} into the durable encoder multibinding set
     * declared by {@code vertique-context}'s {@code ContextRuntimeModule}.
     *
     * @param impl the singleton encoder
     * @return the multibinding contribution
     */
    @Provides
    @IntoSet
    static DurableContextMetadataEncoder<?> identitySnapshotDurableEncoder(IdentitySnapshotDurableEncoder impl) {
        return impl;
    }

    /**
     * Contributes {@link IdentitySnapshotDurableDecoder} into the durable decoder multibinding set
     * declared by {@code vertique-context}'s {@code ContextRuntimeModule}.
     *
     * @param impl the singleton decoder
     * @return the multibinding contribution
     */
    @Provides
    @IntoSet
    static DurableContextMetadataDecoder<?> identitySnapshotDurableDecoder(IdentitySnapshotDurableDecoder impl) {
        return impl;
    }
}
