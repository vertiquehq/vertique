// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Dagger modules that register the declared applications of the hidden-operation integration test
 * and contribute their resources. Every registration is built exactly as the annotation processor
 * would emit it, {@code GeneratedRestApplicationRegistration.of(declaringType, name, path, resources,
 * false, "", true)}, since the processor does not run on framework test sources; every resource
 * instance is contributed as a manual {@code @JaxRsResources} instance.
 *
 * <p>A component lists exactly one of these modules: {@link Served} and {@link ProtectedHidden}
 * register the same application {@code hidden} with different declaring interfaces.
 */
public final class HiddenOperationModules {

    private HiddenOperationModules() {}

    /**
     * Registers an active application exactly as the generated registration module does.
     *
     * @param declaringType the declaring interface
     * @param name          the application's name
     * @param path          the application's path
     * @param resources     the resource classes the application lists, in order
     * @return the registration
     */
    static GeneratedRestApplicationRegistration register(
            Class<?> declaringType, String name, String path, Class<?>... resources) {
        return GeneratedRestApplicationRegistration.of(declaringType, name, path, List.of(resources), false, "", true);
    }

    /**
     * Registers {@code hidden} with its four resource classes.
     *
     * @param declaringType the declaring interface, public or protected
     * @return the registration
     */
    static GeneratedRestApplicationRegistration hidden(Class<?> declaringType) {
        return register(
                declaringType,
                HiddenOperationsApi.NAME,
                HiddenOperationsApi.PATH,
                MixedOperationsResource.class,
                HiddenClassResource.class,
                HiddenContractResource.class,
                PartlyHiddenContractResource.class);
    }

    /**
     * The applications {@code hidden} ({@link HiddenOperationsApi}) and {@code hiddengen} ({@link
     * HiddenGeneratedApi}), both with public documents, in one composition.
     */
    @Module
    public static final class Served {

        private Served() {}

        /**
         * Registers {@link HiddenOperationsApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration hiddenRegistration() {
            return hidden(HiddenOperationsApi.class);
        }

        /**
         * Registers {@link HiddenGeneratedApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration generatedRegistration() {
            return register(
                    HiddenGeneratedApi.class,
                    HiddenGeneratedApi.NAME,
                    HiddenGeneratedApi.PATH,
                    GeneratedHiddenClassResource.class,
                    GeneratedHiddenContractResource.class);
        }

        /**
         * Contributes {@link MixedOperationsResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object mixedResource() {
            return new MixedOperationsResource();
        }

        /**
         * Contributes {@link HiddenClassResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object hiddenClassResource() {
            return new HiddenClassResource();
        }

        /**
         * Contributes {@link HiddenContractResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object hiddenContractResource() {
            return new HiddenContractResource();
        }

        /**
         * Contributes {@link PartlyHiddenContractResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object partlyHiddenContractResource() {
            return new PartlyHiddenContractResource();
        }

        /**
         * Contributes {@link GeneratedHiddenClassResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object generatedHiddenClassResource() {
            return new GeneratedHiddenClassResource();
        }

        /**
         * Contributes {@link GeneratedHiddenContractResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object generatedHiddenContractResource() {
            return new GeneratedHiddenContractResource();
        }
    }

    /** The application {@code hidden}, declared by {@link ProtectedHiddenOperationsApi} (protected document). */
    @Module
    public static final class ProtectedHidden {

        private ProtectedHidden() {}

        /**
         * Registers {@link ProtectedHiddenOperationsApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return hidden(ProtectedHiddenOperationsApi.class);
        }

        /**
         * Contributes {@link MixedOperationsResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object mixedResource() {
            return new MixedOperationsResource();
        }

        /**
         * Contributes {@link HiddenClassResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object hiddenClassResource() {
            return new HiddenClassResource();
        }

        /**
         * Contributes {@link HiddenContractResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object hiddenContractResource() {
            return new HiddenContractResource();
        }

        /**
         * Contributes {@link PartlyHiddenContractResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object partlyHiddenContractResource() {
            return new PartlyHiddenContractResource();
        }
    }

    /** The control application {@code visiblewrite}, declared by {@link ProtectedVisibleWriteApi}. */
    @Module
    public static final class ProtectedVisibleWrite {

        private ProtectedVisibleWrite() {}

        /**
         * Registers {@link ProtectedVisibleWriteApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(
                    ProtectedVisibleWriteApi.class,
                    ProtectedVisibleWriteApi.NAME,
                    ProtectedVisibleWriteApi.PATH,
                    VisibleWriteResource.class);
        }

        /**
         * Contributes {@link VisibleWriteResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new VisibleWriteResource();
        }
    }
}
