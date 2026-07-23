// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import io.vertx.core.Vertx;
import jakarta.inject.Singleton;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Dagger wiring tests for the opt-in {@link MagicBytesVerifierModule}. */
class MagicBytesVerifierModuleTest {

    @Singleton
    @Component(modules = {RestModule.class, MagicBytesVerifierModule.class})
    interface OptInComponent {

        Set<FileContentVerifier> fileContentVerifiers();

        @Component.Factory
        interface Factory {
            OptInComponent create(@BindsInstance Vertx vertx);
        }
    }

    @Singleton
    @Component(modules = RestModule.class)
    interface DefaultComponent {
        Set<FileContentVerifier> fileContentVerifiers();
    }

    @Test
    void moduleContributesIntoVerifierSet() {
        OptInComponent component =
                DaggerMagicBytesVerifierModuleTest_OptInComponent.factory().create(mock(Vertx.class));

        Set<FileContentVerifier> verifiers = component.fileContentVerifiers();

        assertEquals(1, verifiers.size());
        assertInstanceOf(
                MagicBytesFileContentVerifier.class, verifiers.iterator().next());
    }

    @Test
    void verifierSetEmptyByDefault() {
        DefaultComponent component = DaggerMagicBytesVerifierModuleTest_DefaultComponent.create();

        assertTrue(component.fileContentVerifiers().isEmpty());
    }
}
