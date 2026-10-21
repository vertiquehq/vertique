// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.parser;

/**
 * Hands tests in other packages the production parser with its production mapper settings, whose constructors are
 * package-private.
 */
public final class ProductionParserFixture {

    private ProductionParserFixture() {}

    /** Returns a parser backed by the production mapper factory with its default policy. */
    public static WorkflowDefinitionParser parser() {
        return new WorkflowDefinitionParser(new WorkflowDefinitionMapperFactory());
    }
}
