// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import com.fasterxml.jackson.core.ErrorReportConfiguration;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.io.ContentReference;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.json.ByteSourceJsonBootstrapper;
import com.fasterxml.jackson.core.util.BufferRecycler;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Whole-source durable conversion, applied only after the ordinary document pipeline succeeds. */
final class DefinitionSourceText {
    private DefinitionSourceText() {}

    static WorkflowDefinitionDocument decode(
            DefinitionKey key, byte[] bytes, DocumentFormat format, WorkflowDefinitionsConfig config) {
        try {
            Charset charset = StandardCharsets.UTF_8;
            if (format == DocumentFormat.JSON) {
                try (IOContext context = new IOContext(
                        StreamReadConstraints.defaults(),
                        StreamWriteConstraints.defaults(),
                        ErrorReportConfiguration.defaults(),
                        new BufferRecycler(),
                        ContentReference.rawReference(bytes),
                        false)) {
                    JsonEncoding encoding =
                            new ByteSourceJsonBootstrapper(context, bytes, 0, bytes.length).detectEncoding();
                    charset = Charset.forName(encoding.getJavaName());
                }
            }
            String text = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            // JSON's byte bootstrap consumes its leading encoding marker; YAML's UTF8 reader preserves it.
            if (format == DocumentFormat.JSON && text.startsWith("\uFEFF")) text = text.substring(1);
            WorkflowDefinitionDocument document = new WorkflowDefinitionDocument(key, text, format.name());
            validate(document, config);
            return document;
        } catch (IOException | IllegalArgumentException e) {
            throw new WorkflowDefinitionException(
                    "Cannot preserve source text for " + key + ": invalid source representation", e);
        }
    }

    static void validate(WorkflowDefinitionDocument document, WorkflowDefinitionsConfig config) {
        String text = document.document();
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        int points = text.codePointCount(0, text.length());
        if (bytes > config.maxStoredDocumentSizeBytes() || points > config.maxDocumentSizeBytes())
            throw new WorkflowDefinitionException("Stored document " + document.key()
                    + " exceeds source envelope: UTF8 bytes " + bytes + "/" + config.maxStoredDocumentSizeBytes()
                    + ", code points " + points + "/" + config.maxDocumentSizeBytes());
    }
}
