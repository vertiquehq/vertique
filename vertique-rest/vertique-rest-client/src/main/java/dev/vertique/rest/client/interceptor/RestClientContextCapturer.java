// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.interceptor;

import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.extension.OrderedExtension;
import jakarta.annotation.Nullable;

/**
 * System-owned SPI that captures per-request context at dispatch entry and observes each physical
 * attempt with that captured context. The dispatcher invokes {@link #captureRequestContext()}
 * exactly once at request entry — BEFORE any application interceptor runs — holds the returned
 * value in call scope across retries and recovery, and passes it back to
 * {@link #onAttemptCompleted} per physical attempt. The captured value is never exposed to
 * application interceptors (no public attribute map), so it cannot be read, replaced, dropped, or
 * forged by application code.
 *
 * <p>Capture precedes the interceptor chain <em>structurally</em> — the dispatcher invokes
 * {@link #captureRequestContext()} at dispatch entry, ahead of the {@code beforeRequest} interceptor
 * chain — so it does not depend on phase or priority. Implementations are system/platform-owned and
 * should still set {@link #phase()} to
 * {@link dev.vertique.core.extension.ExtensionPhase#SYSTEM_FIRST}: this marks them as system extensions
 * and orders them among <em>other capturers</em> (it is a trusted platform-ordering hint, not a security
 * boundary — see {@link dev.vertique.core.extension.ExtensionPhase}).
 *
 * <p>At completion, this capturer's {@link #onAttemptCompleted} and the application-facing
 * {@link RestClientInterceptor#onAttemptCompleted} are independent fire-and-forget observers of one
 * immutable {@link RestClientAttemptCompletion}; their relative notification order is unspecified and not
 * load-bearing.
 *
 * @param <C> the captured-context type owned by this capturer
 */
public interface RestClientContextCapturer<C> extends OrderedExtension {

    /**
     * Captures the system context for one logical call, once, at dispatch entry on the caller's
     * context.
     *
     * @return the captured context (may be {@code null} when nothing was captured)
     */
    @Nullable
    C captureRequestContext();

    /**
     * Observes one completed physical attempt with the value captured by
     * {@link #captureRequestContext()}. Fire-and-forget; implementations MUST NOT block and
     * exceptions are swallowed by the dispatcher.
     *
     * @param capturedContext the value returned by this capturer's
     *                        {@link #captureRequestContext()} (may be {@code null})
     * @param request         the request context for this attempt
     * @param completion      the attempt's completion facts
     */
    void onAttemptCompleted(
            @Nullable C capturedContext, RestClientRequestContext request, RestClientAttemptCompletion completion);

    /**
     * Four-argument variant of {@link #onAttemptCompleted(Object, RestClientRequestContext,
     * RestClientAttemptCompletion)} that additionally carries the dispatcher-owned
     * {@link MethodMetadata} for the invoked client-interface operation.
     *
     * <p>{@code operation} is always supplied by {@code DefaultRestClientDispatcher} from its own
     * {@code ClientMethodMeta} — it is never interceptor-supplied and never derived from
     * {@link RestClientRequestContext}, which deliberately carries no {@link java.lang.reflect.Method}.
     * A capturer that needs annotation-driven behavior (e.g. binding a policy from an
     * annotation declared on the method or its declaring type) overrides this variant instead of
     * the three-argument one.
     *
     * <p>The default implementation delegates to
     * {@link #onAttemptCompleted(Object, RestClientRequestContext, RestClientAttemptCompletion)},
     * ignoring {@code operation}, so existing implementations of this interface keep compiling and
     * behaving unchanged without needing to override this method.
     *
     * @param capturedContext the value returned by this capturer's
     *                        {@link #captureRequestContext()} (may be {@code null})
     * @param request         the request context for this attempt
     * @param completion      the attempt's completion facts
     * @param operation       the dispatcher-owned method metadata for the invoked client-interface
     *                        operation; never {@code null}
     */
    default void onAttemptCompleted(
            @Nullable C capturedContext,
            RestClientRequestContext request,
            RestClientAttemptCompletion completion,
            MethodMetadata operation) {
        onAttemptCompleted(capturedContext, request, completion);
    }
}
