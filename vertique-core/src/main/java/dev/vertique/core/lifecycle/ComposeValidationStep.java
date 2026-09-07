// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

import dev.vertique.core.json.VertiqueJson;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The framework's {@link LifecyclePhase#VALIDATE VALIDATE} startup step: forces construction of
 * every {@link ComposeValidator} so their constructor-time graph-composition checks run fail-fast,
 * before any verticle deploys.
 *
 * <p>The validation happens in {@link #start()}, not at construction of this step. The step injects a
 * {@link Provider Provider&lt;Set&lt;ComposeValidator&gt;&gt;} rather than the materialized set, so
 * building the step does <em>not</em> construct any validator. The lifecycle runner materializes the
 * whole {@code Set<ApplicationStartupStep>} during the first phase (CONFIGURE); injecting the
 * materialized validator set here would force the validators to construct during CONFIGURE, before
 * that phase's own steps have run, and a validator failure would then be misattributed to the
 * CONFIGURE phase. Deferring materialization to {@code start()} keeps the fail-fast firing in the
 * VALIDATE phase, after CONFIGURE — which matters because a {@code CONFIGURE} step may still change
 * what the validators observe (the process JSON codec's mapper is installed there).
 *
 * <p>When {@code start()} calls {@link Provider#get()}, Dagger materializes the multibinding, which
 * constructs every contributed validator. Each validator's {@code @Inject} constructor asserts that
 * the bindings its module needs are present (the constructible-as-validation pattern — see
 * {@link ComposeValidator}). A missing binding fails Dagger; a violated invariant throws
 * {@link IllegalStateException} from the validator's constructor. Either failure is converted to a
 * <em>failed future</em> (not a synchronous throw) so the runner attributes it to the VALIDATE phase
 * and runs teardown.
 *
 * <p>This step replaces the manual, per-app {@code c.workflowComposeValidator()} accessor calls that
 * applications previously made by hand in {@code MainVerticle.start()}. It is contributed
 * {@code @IntoSet ApplicationStartupStep} by {@link CoreLifecycleStepsModule}, so the lifecycle
 * runner drives it automatically in {@code VALIDATE} phase order.
 */
@Singleton
public final class ComposeValidationStep implements ApplicationStartupStep {

    private static final Logger LOG = LoggerFactory.getLogger(ComposeValidationStep.class);

    private final Provider<Set<ComposeValidator>> validatorsProvider;

    /**
     * Constructs the step with a lazy provider of the compose-validator set. Construction does
     * <em>not</em> materialize the set, so no validator is built here — materialization (and thus the
     * fail-fast validation) is deferred to {@link #start()}, which runs in the VALIDATE phase.
     *
     * @param validatorsProvider a provider that, when {@link Provider#get() get()} is called,
     *     materializes the full {@code Set<ComposeValidator>} multibinding — constructing every
     *     contributed validator and running its constructor-time composition checks
     */
    @Inject
    public ComposeValidationStep(Provider<Set<ComposeValidator>> validatorsProvider) {
        this.validatorsProvider = validatorsProvider;
    }

    /**
     * Returns the phase this step runs in.
     *
     * @return {@link LifecyclePhase#VALIDATE}
     */
    @Override
    public LifecyclePhase phase() {
        return LifecyclePhase.VALIDATE;
    }

    /**
     * Materializes the {@code Set<ComposeValidator>} — forcing construction of every contributed
     * validator and running all of their constructor-time composition checks — in the VALIDATE phase.
     * A construction failure is returned as a failed future rather than thrown synchronously, so the
     * runner attributes it to the VALIDATE phase and tears down.
     *
     * @return a succeeded future when every validator constructs cleanly; a failed future carrying the
     *     first validator-construction failure otherwise
     */
    @Override
    public Future<Void> start() {
        if (VertiqueJson.ownsCodec() && VertiqueJson.profile().isEmpty()) {
            LOG.warn("No JSON profile was installed as the process JSON codec: this application's graph"
                    + " contributes no CONFIGURE-phase install step (no JSON runtime module), so"
                    + " json.systemProfile is not consumed and every Json.* and JsonObject operation runs"
                    + " Vert.x's raw JSON semantics");
        }
        try {
            validatorsProvider.get();
            return Future.succeededFuture();
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
    }
}
