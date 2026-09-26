// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.apps.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Module;
import dagger.Provides;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Support bindings {@link AppComponent} needs: a {@link ConfigParser} that deserializes each config
 * section through a private Jackson mapper, and the unsecured {@link SecurityPolicyValidator} stand-
 * in {@code JaxRsRouterMount.Factory} requires. Mirrors T002's
 * {@code application.ApplicationTestSupportModule}, the reference shape for a bare component.
 */
@Module
final class AppSupportModule {

    private AppSupportModule() {}

    /**
     * Provides the unsecured {@link SecurityPolicyValidator} stand-in. {@code null} is a legal value
     * here: {@code JaxRsRouterMount.Factory}'s constructor parameter is {@code @Nullable}.
     *
     * @return {@code null}
     */
    @Provides
    @Nullable
    static SecurityPolicyValidator securityPolicyValidator() {
        return null;
    }

    /**
     * Provides a minimal {@link ConfigParser} backed by a private, isolated Jackson mapper.
     *
     * @return the config parser
     */
    @Provides
    static ConfigParser configParser() {
        return new ConfigParser() {
            private final ObjectMapper mapper = new ObjectMapper();

            @Override
            public <T> T parse(JsonObject section, Class<T> type) {
                JsonObject json = section != null ? section : new JsonObject();
                try {
                    return mapper.readValue(json.encode(), type);
                } catch (Exception e) {
                    throw new ConfigurationException("failed to parse test config into " + type.getName(), e);
                }
            }

            @Override
            public <T> List<T> parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType) {
                throw new UnsupportedOperationException("not needed by this fixture");
            }

            @Override
            public <T> List<T> parseKeyedObject(
                    JsonObject section, String identityProp, Class<T> elementType, Map<String, Object> fixedProps) {
                throw new UnsupportedOperationException("not needed by this fixture");
            }
        };
    }
}
