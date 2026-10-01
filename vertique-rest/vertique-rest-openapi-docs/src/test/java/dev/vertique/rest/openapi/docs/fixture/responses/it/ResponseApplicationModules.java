// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * Dagger modules that each register one declared application of the response integration tests and
 * contribute its resources. Every registration is built exactly as the annotation processor would
 * emit it, {@code GeneratedRestApplicationRegistration.of(declaringType, name, path, resources,
 * false, "", true)}, since the processor does not run on framework test sources; every resource
 * instance is contributed as a manual {@code @JaxRsResources} instance.
 *
 * <p>A component lists exactly one of these modules, so each deployment is its own composition: the
 * applications {@code notes} of {@link NotesApi} and {@link NoteReceiptsApi} share a name and path
 * and are never composed together. {@link Accounts} includes {@link SnakeOutputProfileModule},
 * whose profile its account resource selects; no other module needs a test profile.
 */
public final class ResponseApplicationModules {

    private ResponseApplicationModules() {}

    /**
     * Registers an active application exactly as the generated registration module does.
     *
     * @param declaringType the declaring interface
     * @param name          the application's name
     * @param path          the application's path
     * @param resources     the resource classes the application lists, in declaration order
     * @return the registration
     */
    static GeneratedRestApplicationRegistration register(
            Class<?> declaringType, String name, String path, List<Class<?>> resources) {
        return GeneratedRestApplicationRegistration.of(declaringType, name, path, resources, false, "", true);
    }

    /**
     * The application {@code accounts}, declared by {@link AccountsApi}: the asymmetric DTO, the
     * dynamic response, and the declared content; includes {@link SnakeOutputProfileModule}.
     */
    @Module(includes = SnakeOutputProfileModule.class)
    public static final class Accounts {

        private Accounts() {}

        /**
         * Registers {@link AccountsApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(
                    AccountsApi.class,
                    AccountsApi.NAME,
                    AccountsApi.PATH,
                    List.of(AccountResource.class, ReportResource.class));
        }

        /**
         * Contributes {@link AccountResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object accountResource() {
            return new AccountResource();
        }

        /**
         * Contributes {@link ReportResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object reportResource() {
            return new ReportResource();
        }
    }

    /**
     * The application {@code notes}, declared by {@link NotesApi}: an inferred output type with a
     * renamed member.
     */
    @Module
    public static final class Notes {

        private Notes() {}

        /**
         * Registers {@link NotesApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(NotesApi.class, NotesApi.NAME, NotesApi.PATH, List.of(NoteResource.class));
        }

        /**
         * Contributes {@link NoteResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object noteResource() {
            return new NoteResource();
        }
    }

    /**
     * The application {@code notesexplicit}, declared by {@link NotesExplicitApi}: a declared
     * output type with a renamed member.
     */
    @Module
    public static final class NotesExplicit {

        private NotesExplicit() {}

        /**
         * Registers {@link NotesExplicitApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(
                    NotesExplicitApi.class,
                    NotesExplicitApi.NAME,
                    NotesExplicitApi.PATH,
                    List.of(NoteExplicitResource.class));
        }

        /**
         * Contributes {@link NoteExplicitResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object noteExplicitResource() {
            return new NoteExplicitResource();
        }
    }

    /**
     * The application {@code receipts}, declared by {@link ReceiptsApi}: an inferred output type
     * with a {@code @Hidden}-only field.
     */
    @Module
    public static final class Receipts {

        private Receipts() {}

        /**
         * Registers {@link ReceiptsApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(ReceiptsApi.class, ReceiptsApi.NAME, ReceiptsApi.PATH, List.of(ReceiptResource.class));
        }

        /**
         * Contributes {@link ReceiptResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object receiptResource() {
            return new ReceiptResource();
        }
    }

    /**
     * The application {@code receiptsexplicit}, declared by {@link ReceiptsExplicitApi}: a declared
     * output type with a {@code @Hidden}-only field.
     */
    @Module
    public static final class ReceiptsExplicit {

        private ReceiptsExplicit() {}

        /**
         * Registers {@link ReceiptsExplicitApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(
                    ReceiptsExplicitApi.class,
                    ReceiptsExplicitApi.NAME,
                    ReceiptsExplicitApi.PATH,
                    List.of(ReceiptExplicitResource.class));
        }

        /**
         * Contributes {@link ReceiptExplicitResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object receiptExplicitResource() {
            return new ReceiptExplicitResource();
        }
    }

    /**
     * The application {@code ledger}, declared by {@link LedgerApi}: an output type reaching a
     * {@code @Hidden} type.
     */
    @Module
    public static final class Ledger {

        private Ledger() {}

        /**
         * Registers {@link LedgerApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(LedgerApi.class, LedgerApi.NAME, LedgerApi.PATH, List.of(LedgerResource.class));
        }

        /**
         * Contributes {@link LedgerResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object ledgerResource() {
            return new LedgerResource();
        }
    }

    /**
     * The application {@code fixed}, declared by {@link FixedApi}: the fixed output type.
     */
    @Module
    public static final class Fixed {

        private Fixed() {}

        /**
         * Registers {@link FixedApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(FixedApi.class, FixedApi.NAME, FixedApi.PATH, List.of(FixedReceiptResource.class));
        }

        /**
         * Contributes {@link FixedReceiptResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object fixedReceiptResource() {
            return new FixedReceiptResource();
        }
    }

    /**
     * The application {@code hiddenop}, declared by {@link HiddenOpApi}: a hidden operation beside
     * a visible one.
     */
    @Module
    public static final class HiddenOp {

        private HiddenOp() {}

        /**
         * Registers {@link HiddenOpApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(
                    HiddenOpApi.class, HiddenOpApi.NAME, HiddenOpApi.PATH, List.of(HiddenOperationResource.class));
        }

        /**
         * Contributes {@link HiddenOperationResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object hiddenOperationResource() {
            return new HiddenOperationResource();
        }
    }

    /**
     * The application {@code pins}, declared by {@link PinsApi}: an output type whose setter
     * carries {@code @Schema(hidden = true)}.
     */
    @Module
    public static final class Pins {

        private Pins() {}

        /**
         * Registers {@link PinsApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(PinsApi.class, PinsApi.NAME, PinsApi.PATH, List.of(PinResource.class));
        }

        /**
         * Contributes {@link PinResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object pinResource() {
            return new PinResource();
        }
    }

    /**
     * The application {@code tiers}, declared by {@link TiersApi}: an output type reaching a hidden
     * enum constant.
     */
    @Module
    public static final class Tiers {

        private Tiers() {}

        /**
         * Registers {@link TiersApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(TiersApi.class, TiersApi.NAME, TiersApi.PATH, List.of(TierResource.class));
        }

        /**
         * Contributes {@link TierResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object tierResource() {
            return new TierResource();
        }
    }

    /**
     * The application {@code notes}, declared by {@link NoteReceiptsApi}: an output type reaching a
     * type marked {@code @Schema(hidden = true)}.
     */
    @Module
    public static final class NoteReceipts {

        private NoteReceipts() {}

        /**
         * Registers {@link NoteReceiptsApi}.
         *
         * @return the registration
         */
        @Provides
        @IntoSet
        static GeneratedRestApplicationRegistration registration() {
            return register(
                    NoteReceiptsApi.class,
                    NoteReceiptsApi.NAME,
                    NoteReceiptsApi.PATH,
                    List.of(NoteReceiptResource.class));
        }

        /**
         * Contributes {@link NoteReceiptResource}.
         *
         * @return a new resource instance
         */
        @Provides
        @IntoSet
        @JaxRsResources
        static Object noteReceiptResource() {
            return new NoteReceiptResource();
        }
    }
}
