// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.dispatch;

import dev.vertique.services.interceptor.ServiceInterceptor;

/**
 * Marker for a dispatch failure that must <strong>never</strong> be recovered by the
 * {@link ServiceInterceptor#recoverError} chain.
 *
 * <p>{@link ServiceMethodInvoker} routes a {@code beforeDispatch} short-circuit through the same
 * {@code recoverError} chain as a handler failure. A broad, permissive application
 * {@code recoverError} (e.g. one that declines on type but accidentally swallows everything, or one
 * that recovers on a superclass) could therefore turn a security <em>deny</em> into a successful
 * dispatch. A failure carrying this marker is recognised by {@code ServiceMethodInvoker} and skips
 * the recover chain entirely, so recovery can never resurrect it.
 *
 * <p>This is the services-layer analogue of the REST/WebSocket fail-closed guarantee, where a
 * {@code @RequiresAction} deny is enforced by a Vert.x router handler ahead of the resource
 * invocation and is structurally unreachable from any application recover hook.
 *
 * <p>The marker is intentionally narrow: only the framework's action-gate deny implements it.
 * Application code that throws an ordinary {@code ForbiddenException} from a service handler is still
 * recoverable, preserving existing {@code recoverError} semantics for business failures.
 *
 * @see ServiceMethodInvoker
 * @see ServiceInterceptor#recoverError
 */
public interface NonRecoverableDispatchFailure {}
