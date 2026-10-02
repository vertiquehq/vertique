// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/**
 * Application-specific Dagger module: the JWT authentication provider, the HTTP verticle
 * deployment, and the API documentation page.
 */
@Module
public abstract class AppModule {

    /** The configuration setting that holds the HS256 key. */
    static final String JWT_KEY_SETTING = "jwt.hs256Key";

    /** The shortest accepted HS256 key, in characters. */
    static final int MIN_JWT_KEY_LENGTH = 32;

    private AppModule() {}

    /**
     * Provides the JWT authentication provider from the symmetric HMAC key configured at
     * {@code jwt.hs256Key}.
     *
     * <p>The key is configuration, never source: the shipped {@code config/application.json} holds
     * none, so a deployment supplies its own, of at least 32 characters. A missing, blank, non-string,
     * or shorter key fails startup with a message that names the setting and never the value.
     *
     * <p>A deployment supplies the key in one of these ways:
     * <ul>
     *   <li>as a system property, {@code -Djwt.hs256Key=<at least 32 characters>}, which the
     *       configuration's system-property store adds as the top-level key {@code "jwt.hs256Key"};
     *   <li>as an environment variable named exactly {@code jwt.hs256Key} (as a container
     *       environment entry or through {@code env}; most shells cannot export a dotted name),
     *       which the environment-variable store adds the same way;
     *   <li>in the operator's own JSON file in a configuration directory listed in
     *       {@code VERTX_CONFIG_LOCATIONS}, as {@code {"jwt": {"hs256Key": "${JWT_HS256_KEY}"}}},
     *       where the placeholder takes the value of the {@code JWT_HS256_KEY} environment variable.
     * </ul>
     * An environment variable such as {@code JWT_HS256_KEY} on its own is not read as this setting.
     * When both forms are present, the top-level {@code "jwt.hs256Key"} wins over the nested
     * {@code jwt} section, because the system-property and environment-variable stores outrank
     * configuration files.
     *
     * <p><b>Warning:</b> a shared symmetric key suits an example. Production applications should
     * verify tokens against the issuer's published keys instead:
     * <pre>{@code
     * return JwtAuthFactory.fromJwks(vertx, "https://auth.example.com/.well-known/jwks.json");
     * }</pre>
     *
     * @param vertx  the Vert.x instance
     * @param config the application configuration
     * @return the JWT authentication provider
     * @throws IllegalStateException when the key is missing, blank, not a string, or shorter than
     *     32 characters
     */
    @Provides
    @Singleton
    static JWTAuth jwtAuth(Vertx vertx, @VertxConfig JsonObject config) {
        return JwtAuthFactory.fromSymmetricKey(vertx, "HS256", configuredKey(config));
    }

    /**
     * Contributes the API documentation page at {@code /apidocs/ui/}.
     *
     * @return the page's router mount
     */
    @Provides
    @IntoSet
    static RouterMount apiDocsUiMount() {
        return new ApiDocsUiMount();
    }

    /**
     * Reads the HS256 key from the top-level {@code "jwt.hs256Key"} entry or, when that is absent,
     * from {@code hs256Key} in the nested {@code jwt} section.
     *
     * @param config the application configuration
     * @return the key, at least {@value #MIN_JWT_KEY_LENGTH} characters
     * @throws IllegalStateException when no usable key is configured; the message never holds the
     *     value
     */
    private static String configuredKey(JsonObject config) {
        Object key = config.getValue(JWT_KEY_SETTING);
        if (key == null && config.getValue("jwt") instanceof JsonObject jwt) {
            key = jwt.getValue("hs256Key");
        }
        if (!(key instanceof String text) || text.isBlank() || text.length() < MIN_JWT_KEY_LENGTH) {
            throw new IllegalStateException(JWT_KEY_SETTING + " must be configured as a string of at least "
                    + MIN_JWT_KEY_LENGTH + " characters, for example with -D" + JWT_KEY_SETTING
                    + "=<key>; the configured value is missing, not a string, blank, or too short");
        }
        return text;
    }

    /**
     * Registers the HTTP verticle for deployment in the {@link LifecyclePhase#EDGE} phase.
     *
     * @param provider Dagger provider creating fresh instances per deployment
     * @return the deployment descriptor
     */
    @Provides
    @IntoSet
    static VerticleDeployment httpVerticle(Provider<HttpVerticle> provider) {
        return VerticleDeployment.of("http", provider::get, LifecyclePhase.EDGE);
    }
}
