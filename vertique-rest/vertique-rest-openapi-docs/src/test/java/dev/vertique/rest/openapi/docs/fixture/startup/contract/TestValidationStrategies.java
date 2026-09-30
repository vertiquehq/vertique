// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.contract;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.Optional;

/**
 * Custom test {@link RequestValidationStrategy}s that install no gate, each with a module contributing
 * it into the strategy set. They differ only in their id and in whether they report that they
 * resolve operations from the mount's contract, so a composition can select a strategy by id without
 * the id alone deciding how the mount's contract is treated.
 */
public final class TestValidationStrategies {

    private TestValidationStrategies() {}

    /** The id of {@link DocsTestStrategy}. */
    public static final String DOCS_TEST_ID = "custom-docs-test";

    /** The id of {@link ContractTestStrategy}. */
    public static final String CONTRACT_TEST_ID = "custom-contract-test";

    /** A strategy of id {@value #DOCS_TEST_ID} that keeps the default: it resolves nothing from a contract. */
    public static final class DocsTestStrategy implements RequestValidationStrategy {

        /** Creates the strategy. */
        public DocsTestStrategy() {}

        @Override
        public String id() {
            return DOCS_TEST_ID;
        }

        @Override
        public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
            return Optional.empty();
        }
    }

    /**
     * A strategy of id {@value #CONTRACT_TEST_ID} that reports resolving operations from the mount's
     * contract, while installing no gate.
     */
    public static final class ContractTestStrategy implements RequestValidationStrategy {

        /** Creates the strategy. */
        public ContractTestStrategy() {}

        @Override
        public String id() {
            return CONTRACT_TEST_ID;
        }

        @Override
        public boolean resolvesOperationsFromMountContract() {
            return true;
        }

        @Override
        public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
            return Optional.empty();
        }
    }

    /** Contributes a {@link DocsTestStrategy} into the strategy set. */
    @Module
    public static final class DocsTest {

        private DocsTest() {}

        /**
         * Contributes the strategy.
         *
         * @return a new strategy
         */
        @Provides
        @IntoSet
        static RequestValidationStrategy docsTestStrategy() {
            return new DocsTestStrategy();
        }
    }

    /** Contributes a {@link ContractTestStrategy} into the strategy set. */
    @Module
    public static final class ContractTest {

        private ContractTest() {}

        /**
         * Contributes the strategy.
         *
         * @return a new strategy
         */
        @Provides
        @IntoSet
        static RequestValidationStrategy contractTestStrategy() {
            return new ContractTestStrategy();
        }
    }
}
