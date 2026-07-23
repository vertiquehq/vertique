// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.SnapshotDegradationReason;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ContextHolder}-bindable wrapper carried across durable execution boundaries (delayed jobs,
 * inbox/outbox, workflow resume) so a deferred execution can reconstruct — or fail closed on — the
 * identity that originated the work (PRD-ID-002 §14.3 "Durable carriage", §14.6 amendment A9).
 *
 * <p>The wrapper models the two sides of the durable boundary with three mutually-exclusive states,
 * so the schema v2 content/envelope split ({@code IdentitySnapshotFactory} captures content only; the
 * encoder assembles+signs the envelope; the decoder verifies it) round-trips through a single bound
 * type:
 *
 * <ul>
 *   <li><strong>Captured content (producer side)</strong> — {@link #of(IdentitySnapshotContent)}
 *       carries the credential-free {@link IdentitySnapshotContent} the producer-side capture bound.
 *       {@link #content()} is present. {@link IdentitySnapshotDurableEncoder} reads it, assembles the
 *       durable {@link IdentitySnapshot} envelope (carrier + temporal bounds), and signs it.</li>
 *   <li><strong>Verified snapshot (receive side)</strong> — {@link #verified(IdentitySnapshot)}
 *       carries a snapshot that decoded and passed HMAC + freshness + carrier verification on the
 *       receive side. {@link #snapshot()} is present and {@link #verified()} is {@code true}.
 *       {@link IdentitySnapshotReconstructionInitializer} reconstructs a
 *       {@link dev.vertique.security.SecurityContext} from it (re-verifying per FR-ID-CA-008 as
 *       defense-in-depth).</li>
 *   <li><strong>Present-but-unverifiable (receive side)</strong> —
 *       {@link #unverifiable(SnapshotDegradationReason)} carries only the typed reason a
 *       durably-carried snapshot could not be verified (bad HMAC, unknown/unavailable key,
 *       decode/schema failure, or a stale/malformed temporal envelope). {@link #snapshot()} and
 *       {@link #content()} are empty and {@link #unverifiableReason()} is present. The initializer
 *       binds a {@link dev.vertique.security.SnapshotDegradationMarker} (never a reconstructed user
 *       identity) so the async degradation gate can emit the non-droppable degradation event and apply
 *       the operator {@code FAIL}/{@code CONTINUE_WITHOUT_IDENTITY} policy.</li>
 * </ul>
 *
 * <p>The producer state ({@code content}) and the receive states ({@code snapshot} /
 * {@code unverifiableReason}) never coexist on the same holder binding — they arise on opposite sides
 * of the durable boundary in different executions — but are modelled on one record so the encoder and
 * decoder both operate over the registered {@code IdentitySnapshotContext} durable type.
 *
 * <p>Binding an {@code IdentitySnapshotContext} does not, by itself, imply the wrapped snapshot has
 * been verified for the current binding — see {@link IdentitySnapshotDurableDecoder} for the
 * signature-and-freshness-verifying decode path and {@link IdentitySnapshotCapture} for the
 * producer-side capture gate.
 *
 * @param content            the captured, to-be-encoded identity content on the producer side; empty
 *                           on the receive side
 * @param snapshot           the verified identity snapshot when this context is a receive-side
 *                           verified context; empty otherwise
 * @param unverifiableReason the typed reason the durably-carried snapshot could not be verified when
 *                           this context is present-but-unverifiable; empty otherwise
 */
public record IdentitySnapshotContext(
        Optional<IdentitySnapshotContent> content,
        Optional<IdentitySnapshot> snapshot,
        Optional<SnapshotDegradationReason> unverifiableReason)
        implements ContextValue {

    /**
     * Compact constructor enforcing the exactly-one invariant: precisely one of {@code content},
     * {@code snapshot}, and {@code unverifiableReason} is present.
     *
     * @throws NullPointerException     if any component is {@code null}
     * @throws IllegalArgumentException if the number of present components is not exactly one
     */
    public IdentitySnapshotContext {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(unverifiableReason, "unverifiableReason");
        int present = (content.isPresent() ? 1 : 0)
                + (snapshot.isPresent() ? 1 : 0)
                + (unverifiableReason.isPresent() ? 1 : 0);
        if (present != 1) {
            throw new IllegalArgumentException(
                    "exactly one of content / snapshot / unverifiableReason must be present");
        }
    }

    /**
     * Creates a <em>captured-content</em> context wrapping the credential-free content the
     * producer-side capture bound, to be assembled and signed into a durable envelope by the encoder.
     *
     * @param content the captured identity content; must not be {@code null}
     * @return a captured-content {@code IdentitySnapshotContext}
     * @throws NullPointerException if {@code content} is {@code null}
     */
    public static IdentitySnapshotContext of(IdentitySnapshotContent content) {
        return new IdentitySnapshotContext(
                Optional.of(Objects.requireNonNull(content, "content")), Optional.empty(), Optional.empty());
    }

    /**
     * Creates a <em>verified</em> context wrapping a decoded, HMAC-and-freshness-verified snapshot on
     * the receive side.
     *
     * @param snapshot the verified identity snapshot; must not be {@code null}
     * @return a verified {@code IdentitySnapshotContext}
     * @throws NullPointerException if {@code snapshot} is {@code null}
     */
    public static IdentitySnapshotContext verified(IdentitySnapshot snapshot) {
        return new IdentitySnapshotContext(
                Optional.empty(), Optional.of(Objects.requireNonNull(snapshot, "snapshot")), Optional.empty());
    }

    /**
     * Creates a <em>present-but-unverifiable</em> context carrying only the typed reason a
     * durably-carried snapshot could not be verified. No snapshot is retained — an unverifiable
     * snapshot must never yield a reconstructed identity, only a degradation.
     *
     * @param reason the typed reason verification failed; must not be {@code null}
     * @return a present-but-unverifiable {@code IdentitySnapshotContext}
     * @throws NullPointerException if {@code reason} is {@code null}
     */
    public static IdentitySnapshotContext unverifiable(SnapshotDegradationReason reason) {
        return new IdentitySnapshotContext(
                Optional.empty(), Optional.empty(), Optional.of(Objects.requireNonNull(reason, "reason")));
    }

    /**
     * Returns whether this context carries a verified snapshot (as opposed to captured producer-side
     * content or a present-but-unverifiable degradation reason).
     *
     * @return {@code true} if {@link #snapshot()} is present
     */
    public boolean verified() {
        return snapshot.isPresent();
    }
}
