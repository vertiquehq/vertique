// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.hidden;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Dagger modules that each register one declared application of the hidden-input integration tests
 * and contribute its one resource. Every registration is built exactly as the annotation processor
 * would emit it, {@code GeneratedRestApplicationRegistration.of(declaringType, name, path, resources,
 * false, "", true)}, since the processor does not run on framework test sources; every resource
 * instance is contributed as a manual {@code @JaxRsResources} instance.
 *
 * <p>A component lists exactly one of these modules: the twins of one application share its name
 * and path, and the compositions of {@code accounts} differ only in the resource's body type.
 */
public final class HiddenApplicationModules {

    private HiddenApplicationModules() {}

    /**
     * Registers an active application exactly as the generated registration module does.
     *
     * @param declaringType the declaring interface
     * @param name          the application's name
     * @param path          the application's path
     * @param resource      the one resource class the application lists
     * @return the registration
     */
    static GeneratedRestApplicationRegistration register(
            Class<?> declaringType, String name, String path, Class<?> resource) {
        return GeneratedRestApplicationRegistration.of(declaringType, name, path, List.of(resource), false, "", true);
    }

    /**
     * Registers one composition of the application {@code accounts}.
     *
     * @param declaringType the composition's declaring interface
     * @param resource      the composition's resource
     * @return the registration
     */
    static GeneratedRestApplicationRegistration accounts(Class<?> declaringType, Class<?> resource) {
        return register(declaringType, AccountsApplication.NAME, AccountsApplication.PATH, resource);
    }

    /** The application {@code hidden}, declared by {@link HiddenProbeApi} (public document). */
    @Module
    public static final class Probe {

        private Probe() {}

        /**
         * Registers {@link HiddenProbeApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(HiddenProbeApi.class, HiddenProbeApi.NAME, HiddenProbeApi.PATH, HiddenProbeResource.class);
        }

        /**
         * Contributes {@link HiddenProbeResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new HiddenProbeResource();
        }
    }

    /** The application {@code hidden}, declared by {@link ProtectedHiddenProbeApi} (protected document). */
    @Module
    public static final class ProtectedProbe {

        private ProtectedProbe() {}

        /**
         * Registers {@link ProtectedHiddenProbeApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(
                    ProtectedHiddenProbeApi.class, HiddenProbeApi.NAME, HiddenProbeApi.PATH, HiddenProbeResource.class);
        }

        /**
         * Contributes {@link HiddenProbeResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new HiddenProbeResource();
        }
    }

    /**
     * The application {@code accounts}, declared by {@link AccountsHiddenFieldApi}:
     * a member carrying {@code @Hidden} only.
     */
    @Module
    public static final class HiddenField {

        private HiddenField() {}

        /**
         * Registers {@link AccountsHiddenFieldApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return accounts(AccountsHiddenFieldApi.class, HiddenFieldAccountsResource.class);
        }

        /**
         * Contributes {@link HiddenFieldAccountsResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new HiddenFieldAccountsResource();
        }
    }

    /**
     * The application {@code accounts}, declared by {@link AccountsHiddenTypeApi}:
     * a member whose type carries {@code @Hidden}.
     */
    @Module
    public static final class HiddenType {

        private HiddenType() {}

        /**
         * Registers {@link AccountsHiddenTypeApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return accounts(AccountsHiddenTypeApi.class, HiddenTypeAccountsResource.class);
        }

        /**
         * Contributes {@link HiddenTypeAccountsResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new HiddenTypeAccountsResource();
        }
    }

    /** The application {@code accounts}, declared by {@link AccountsFixedApi}: the fixed member (public document). */
    @Module
    public static final class Fixed {

        private Fixed() {}

        /**
         * Registers {@link AccountsFixedApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return accounts(AccountsFixedApi.class, FixedAccountsResource.class);
        }

        /**
         * Contributes {@link FixedAccountsResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new FixedAccountsResource();
        }
    }

    /**
     * The application {@code accounts}, declared by {@link ProtectedAccountsFixedApi}:
     * the fixed member (protected document).
     */
    @Module
    public static final class ProtectedFixed {

        private ProtectedFixed() {}

        /**
         * Registers {@link ProtectedAccountsFixedApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return accounts(ProtectedAccountsFixedApi.class, FixedAccountsResource.class);
        }

        /**
         * Contributes {@link FixedAccountsResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new FixedAccountsResource();
        }
    }

    /** The application {@code accounts}, declared by {@link AccountsFormApi}: both kinds of hidden member. */
    @Module
    public static final class Form {

        private Form() {}

        /**
         * Registers {@link AccountsFormApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return accounts(AccountsFormApi.class, FormAccountsResource.class);
        }

        /**
         * Contributes {@link FormAccountsResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new FormAccountsResource();
        }
    }

    /** The application {@code accounts}, declared by {@link AccountsBeanApi}: a hidden JavaBean getter. */
    @Module
    public static final class Bean {

        private Bean() {}

        /**
         * Registers {@link AccountsBeanApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return accounts(AccountsBeanApi.class, BeanAccountsResource.class);
        }

        /**
         * Contributes {@link BeanAccountsResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new BeanAccountsResource();
        }
    }

    /** The application {@code accounts}, declared by {@link AccountsTierApi}: a hidden enum constant. */
    @Module
    public static final class Tier {

        private Tier() {}

        /**
         * Registers {@link AccountsTierApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return accounts(AccountsTierApi.class, TierAccountsResource.class);
        }

        /**
         * Contributes {@link TierAccountsResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new TierAccountsResource();
        }
    }

    /** The application {@code accounts}, declared by {@link AccountsLedgerApi}: a member of a hidden class. */
    @Module
    public static final class Ledger {

        private Ledger() {}

        /**
         * Registers {@link AccountsLedgerApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return accounts(AccountsLedgerApi.class, LedgerAccountsResource.class);
        }

        /**
         * Contributes {@link LedgerAccountsResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new LedgerAccountsResource();
        }
    }

    /** The application {@code accounts}, declared by {@link AccountsCtorApi}: a hidden creator parameter. */
    @Module
    public static final class Ctor {

        private Ctor() {}

        /**
         * Registers {@link AccountsCtorApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return accounts(AccountsCtorApi.class, CtorAccountsResource.class);
        }

        /**
         * Contributes {@link CtorAccountsResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object resource() {
            return new CtorAccountsResource();
        }
    }
}
