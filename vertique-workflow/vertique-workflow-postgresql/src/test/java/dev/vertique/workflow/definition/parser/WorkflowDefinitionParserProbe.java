// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.parser;

import dagger.Module;
import dagger.Provides;
import dev.vertique.workflow.definition.schema.WorkflowDefinitionDocument;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts calls while delegating every parse to the production parser and real mapper factory. */
public final class WorkflowDefinitionParserProbe {

    private final AtomicInteger calls = new AtomicInteger();

    public int calls() {
        return calls.get();
    }

    public ParserModule module() {
        return new ParserModule(this);
    }

    @Module
    public static final class ParserModule {

        private final WorkflowDefinitionParserProbe probe;

        private ParserModule(WorkflowDefinitionParserProbe probe) {
            this.probe = probe;
        }

        @Provides
        @Singleton
        public WorkflowDefinitionParser parser(WorkflowDefinitionMapperFactory mapperFactory) {
            return new WorkflowDefinitionParser(mapperFactory) {
                @Override
                public WorkflowDefinitionDocument parse(byte[] bytes, DocumentFormat format) {
                    probe.calls.incrementAndGet();
                    return super.parse(bytes, format);
                }
            };
        }
    }
}
