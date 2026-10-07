// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.contractload;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.rest.openapi.docs.fixture.ManualResource;
import java.util.List;
import java.util.Set;

/**
 * The compositions of the contract-load startup proofs whose contract location must be a compile-time
 * constant: an application whose declaration names its contract, and a hand-built JAX-RS mount with a
 * contract. Both name {@value #MALFORMED_SERVERS_RESOURCE}, a test resource whose only server URL is
 * malformed and whose file name, server URL, and {@code info.description} carry {@value #MARKER}.
 */
public final class ContractLoadModules {

    /** The marker the classpath fixture carries in its name and content; no failure may echo it. */
    public static final String MARKER = "zq16marker";

    /** The classpath location of the contract with a malformed server URL. */
    public static final String MALFORMED_SERVERS_RESOURCE = "contracts/" + MARKER + "-malformed-servers.json";

    /** The hand-built mount's path; it overlaps no application mount path. */
    public static final String HAND_BUILT_MOUNT_PATH = "/api/handbuilt/*";

    private ContractLoadModules() {}

    /** Registers {@link AnnotatedContractApi}, active, with its declared contract location. */
    @Module
    public static final class Annotated {

        private Annotated() {}

        /**
         * Registers {@link AnnotatedContractApi}, active.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration annotatedContractApiRegistration() {
            return GeneratedRestApplicationRegistration.of(
                    AnnotatedContractApi.class,
                    AnnotatedContractApi.NAME,
                    AnnotatedContractApi.PATH,
                    List.of(CatalogResource.class),
                    false,
                    AnnotatedContractApi.OPENAPI_PATH,
                    true);
        }
    }

    /**
     * Contributes a hand-built JAX-RS mount at {@value #HAND_BUILT_MOUNT_PATH} holding one {@link
     * ManualResource}, with the contract location {@value #MALFORMED_SERVERS_RESOURCE}. The mount belongs
     * to no application.
     */
    @Module
    public static final class HandBuilt {

        private HandBuilt() {}

        /**
         * Builds the hand-built mount.
         *
         * @param factory the JAX-RS mount factory
         * @return the mount, contributed into {@code Set<RouterMount>}
         */
        @Provides
        @IntoSet
        static RouterMount handBuiltMount(JaxRsRouterMount.Factory factory) {
            return factory.create(HAND_BUILT_MOUNT_PATH, MALFORMED_SERVERS_RESOURCE, Set.of(new ManualResource()));
        }
    }
}
