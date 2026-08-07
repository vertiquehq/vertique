// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dagger.Module;
import dagger.Provides;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.security.CapturedAuthorityReconstruction;
import dev.vertique.security.CarriageRequirement;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;

/**
 * Dagger {@link Module} that provides {@link CapturedAuthorityReconstruction} to the framework
 * infrastructure that explicitly opts into Mode-3 captured-authority reconstruction (PRD
 * identity-002 §14.3 Phase-2 Appendix, §14.6 P2.S4).
 *
 * <p><strong>Structural never-default.</strong> This module is deliberately <strong>not</strong>
 * included by {@link PrivilegedIdentityModule}, {@code IdentitySnapshotReconstructionModule}, or
 * any other general wiring — mirroring {@link PrivilegedIdentityModule}'s own isolation rationale,
 * but one boundary further out: {@link PrivilegedIdentityModule} is itself already an opt-in
 * module applications install for the (attribution-only) Mode-1 privileged boundary, and this
 * module is a <em>second</em>, independent opt-in on top of it. There is no {@code
 * @BindsOptionalOf} fallback for {@link CapturedAuthorityReconstruction} anywhere in the
 * framework — absent this module, the type is simply unbound, so any accidental application-level
 * dependency on it fails to compile the Dagger graph rather than silently resolving to a
 * degraded or empty implementation. An application enables Mode 3 by explicitly including this
 * module in its component's module list, which is visible at code review.
 *
 * <p><strong>No Dagger binding bypasses the audited seam.</strong> This module deliberately
 * exposes <em>only</em> {@link CapturedAuthorityActivation} — it declares no {@code @Provides}
 * (and {@link CapturedAuthorityReconstruction} declares no {@code @Inject} constructor) for the
 * raw {@link CapturedAuthorityReconstruction} seam anywhere in the framework, so there is no
 * injectable binding for it to obtain and bypass the activation-audit event with. The
 * {@link DefaultCapturedAuthorityReconstruction} collaborator {@link #capturedAuthorityActivation}
 * constructs is a purely internal implementation detail of that one provider, never itself a
 * Dagger binding — so the only way framework or application code can <em>inject</em> a way to put
 * captured authority into effect is {@link CapturedAuthorityActivation}, which always emits and
 * awaits the activation-audit event before resolving.
 *
 * <p>The guarantee is scoped to injection, not to the language. {@link
 * CapturedAuthorityReconstruction} is a public interface and {@link CapturedAuthorityActivation}'s
 * constructor is public, so application code that deliberately hand-wires its own implementation —
 * or its own activation instance over one — reconstructs without emitting the event. <strong>No
 * Dagger binding can bypass the audited seam; a deliberate hand-wired instantiation of the public
 * constructor can</strong>, and is visible at code review exactly like installing this module is.
 *
 * <p>This module must always be installed alongside {@link IdentitySnapshotCarriageModule} (for
 * the shared, config-backed {@link IdentitySnapshotCodec} and {@link IdentitySnapshotConfig}) and
 * {@code SecurityEventsModule} (for the {@link SecurityEventEmitter} binding {@link
 * CapturedAuthorityActivation} needs) in the application's Dagger component — it declares no
 * {@code includes} of its own so that installing it is never mistaken for a silent default; the
 * requirement is documented here instead. An application enabling Mode 3 MUST install both
 * companion modules.
 *
 * <p><strong>The audited Mode-3 entry point.</strong> {@link CapturedAuthorityActivation} is the
 * sanctioned seam that consuming infrastructure uses to actually put captured authority into
 * effect — it invokes the internally-constructed {@link CapturedAuthorityReconstruction}, then
 * emits and awaits the {@code CapturedAuthorityActivatedEvent} audit record (reconstruction itself
 * stays event-silent per FR-ID-CA-007). This ordering guarantee is emission-ordering, not a
 * durable/acknowledged-delivery guarantee — see {@link CapturedAuthorityActivation}'s javadoc.
 *
 * <p><strong>Startup REQUIRED validation.</strong> {@link #capturedAuthorityReconstruction} checks,
 * for every kind named in {@link CapturedAuthorityConfig#allowedTargetKinds()}, that {@link
 * IdentitySnapshotConfig#carriageRequirementFor(String)} resolves to {@link
 * CarriageRequirement#REQUIRED}. A captured-authority target whose carriage is merely {@link
 * CarriageRequirement#OPTIONAL} or {@link CarriageRequirement#FORBIDDEN} would be a standing
 * bearer-credential hole — a dispatch on that target could arrive with no snapshot at all, or an
 * attacker could suppress the snapshot, and the target would never be flagged as missing expected
 * carriage. This check fails application startup with a {@link ConfigurationException} naming the
 * offending kind and its actual requirement, rather than allowing Mode 3 to install silently
 * unsafe.
 *
 * <p>Config path: {@code identity.snapshot.capturedAuthority} — for example:
 *
 * <pre>{@code
 * identity:
 *   snapshot:
 *     carriageRequirements:
 *       outbox-relay: REQUIRED
 *     capturedAuthority:
 *       allowedTargetKinds:
 *         - outbox-relay
 * }</pre>
 */
@Module
public abstract class CapturedAuthorityReconstructionModule {

    private CapturedAuthorityReconstructionModule() {
        /* Dagger abstract module — no instances */
    }

    /**
     * Provides the {@link CapturedAuthorityActivation} singleton — the <strong>only</strong>
     * binding this module exposes for putting captured authority into effect.
     *
     * <p>Validates that every allowlisted target kind is {@link CarriageRequirement#REQUIRED},
     * then constructs the {@link DefaultCapturedAuthorityReconstruction} collaborator internally
     * and hands it, along with the injected {@link SecurityEventEmitter}, to a new {@link
     * CapturedAuthorityActivation}. The raw {@link CapturedAuthorityReconstruction} instance is
     * never itself exposed as a Dagger binding — see the class javadoc's "No Dagger binding
     * bypasses the audited seam" note — so no <em>injectable</em> route reaches it other than the
     * emit-and-await {@link CapturedAuthorityActivation} seam; a deliberate hand-wired
     * instantiation of the public {@link CapturedAuthorityReconstruction} interface still can.
     *
     * @param codec    the snapshot codec used to re-verify a snapshot's integrity before
     *                 reconstruction; must not be {@code null}
     * @param config   the shared identity-snapshot configuration supplying {@link
     *                 IdentitySnapshotConfig#carriageRequirementFor(String)}; must not be {@code
     *                 null}
     * @param captured the Mode-3 allowlist configuration; must not be {@code null}
     * @param emitter  the security-event emitter {@link CapturedAuthorityActivation} uses to fan
     *                 out the activation-audit event; must not be {@code null}
     * @return a new {@link CapturedAuthorityActivation}; never {@code null}
     * @throws ConfigurationException if any kind in {@link CapturedAuthorityConfig#allowedTargetKinds()}
     *                                 does not resolve to {@link CarriageRequirement#REQUIRED} on
     *                                 {@code config}
     */
    @Provides
    @Singleton
    static CapturedAuthorityActivation capturedAuthorityActivation(
            IdentitySnapshotCodec codec,
            IdentitySnapshotConfig config,
            CapturedAuthorityConfig captured,
            SecurityEventEmitter emitter) {
        for (String kind : captured.allowedTargetKinds()) {
            CarriageRequirement requirement = config.carriageRequirementFor(kind);
            if (requirement != CarriageRequirement.REQUIRED) {
                throw new ConfigurationException(
                        "identity.snapshot.capturedAuthority.allowedTargetKinds names durable target kind '" + kind
                                + "' whose identity.snapshot.carriageRequirements entry is " + requirement
                                + " — a captured-authority target must be REQUIRED, otherwise it would be a standing "
                                + "bearer-credential hole");
            }
        }
        CapturedAuthorityReconstruction reconstruction =
                new DefaultCapturedAuthorityReconstruction(codec, captured.allowedTargetKinds());
        return new CapturedAuthorityActivation(reconstruction, emitter);
    }

    /**
     * Provides the parsed {@link CapturedAuthorityConfig} from the {@code
     * identity.snapshot.capturedAuthority} section of the application config.
     *
     * @param config the full application configuration injected via {@code @VertxConfig}
     * @param parser the injected config parser
     * @return the parsed captured-authority configuration; never {@code null}
     */
    @Provides
    @Singleton
    static CapturedAuthorityConfig capturedAuthorityConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(
                JsonConfigPaths.navigateObject(config, "identity", "snapshot", "capturedAuthority"),
                CapturedAuthorityConfig.class);
    }
}
