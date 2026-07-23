// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.store.ssm;

import java.util.List;
import software.amazon.awssdk.services.ssm.model.Parameter;

/**
 * Internal seam between {@link SsmConfigStore} and the AWS SDK {@code SsmClient}.
 *
 * <p>All SDK calls are isolated behind this interface so that unit tests can inject a stub
 * without a live AWS endpoint. The single method performs paginated
 * {@code GetParametersByPath} calls and returns the collected parameters.
 *
 * <p>This interface is package-private: it is an internal implementation detail of the
 * {@code vertique-config-aws-ssm} module and must not be exposed to callers.
 *
 * <h2>Fail-Closed Contract</h2>
 * <p>Implementations must propagate SDK exceptions as {@link RuntimeException} (or its
 * subclasses). The caller ({@link SsmConfigStore}) wraps them in a failed
 * {@link io.vertx.core.Future}. Exception messages MUST name the path but MUST NOT include
 * any parameter value or credential.
 */
interface SsmGateway {

    /**
     * Fetches all parameters under the given path using paginated
     * {@code GetParametersByPath} calls.
     *
     * <p>Implementations must collect all pages before returning. The caller does not need
     * to handle pagination — the full list is returned in a single call.
     *
     * @param path            the SSM path prefix to search under; never {@code null}; includes
     *                        leading and trailing {@code "/"}
     * @param recursive       whether to descend recursively under {@code path}
     * @param withDecryption  whether to decrypt {@code SecureString} parameters
     * @return the complete list of parameters found under {@code path}; never {@code null};
     *         may be empty if no parameters exist under the path
     * @throws RuntimeException if the SDK call fails for any reason (network error, auth
     *                           failure, invalid path); message names the path but not values
     */
    List<Parameter> fetchAll(String path, boolean recursive, boolean withDecryption);
}
