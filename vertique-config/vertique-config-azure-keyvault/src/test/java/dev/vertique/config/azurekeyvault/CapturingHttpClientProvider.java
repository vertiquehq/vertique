// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.azurekeyvault;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpClientProvider;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.HttpClientOptions;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;

/**
 * Test-only {@link HttpClientProvider} registered via {@code META-INF/services} so that
 * {@link HttpClient#createDefault(HttpClientOptions)} — the call made by
 * {@link SdkKeyVaultGateway} — hands its {@link HttpClientOptions} to the test instead of
 * building a real Netty client.
 *
 * <p>The returned client never sends traffic: every request fails, so an accidental network call
 * from a test cannot succeed silently.
 *
 * <p>This class is public with a public no-arg constructor only because {@link java.util.ServiceLoader}
 * requires it.
 */
public final class CapturingHttpClientProvider implements HttpClientProvider {

    private static final AtomicReference<HttpClientOptions> LAST_OPTIONS = new AtomicReference<>();

    /** Required by {@link java.util.ServiceLoader}. */
    public CapturingHttpClientProvider() {}

    /**
     * Returns the options passed to the most recent {@code createInstance(HttpClientOptions)} call.
     *
     * @return the captured options, or {@code null} when none were captured since {@link #reset()}
     */
    static HttpClientOptions lastOptions() {
        return LAST_OPTIONS.get();
    }

    /** Clears the captured options. */
    static void reset() {
        LAST_OPTIONS.set(null);
    }

    @Override
    public HttpClient createInstance() {
        return new NoTrafficHttpClient();
    }

    @Override
    public HttpClient createInstance(HttpClientOptions clientOptions) {
        LAST_OPTIONS.set(clientOptions);
        return new NoTrafficHttpClient();
    }

    private static final class NoTrafficHttpClient implements HttpClient {
        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            return Mono.error(new IllegalStateException("test HttpClient does not send traffic"));
        }
    }
}
