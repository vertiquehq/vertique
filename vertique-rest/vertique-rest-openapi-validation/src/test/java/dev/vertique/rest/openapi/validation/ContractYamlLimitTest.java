// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vertx.core.Vertx;
import io.vertx.openapi.contract.OpenAPIContract;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit proof of the claim the module reference makes about the request-validation library's YAML
 * limit: a YAML contract of 3,145,728 code points (characters, not bytes) loads, and one twice as
 * long fails to load. The contracts are generated into a temporary directory.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ContractYamlLimitTest {

    private static final int CODE_POINT_LIMIT = 3_145_728;

    private static final String HEAD = "openapi: 3.1.0\ninfo:\n  title: Limits\n  version: \"1\"\n  description: ";

    private static final String TAIL = "\npaths: {}\n";

    private Vertx vertx;

    @BeforeEach
    void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void closeVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a YAML contract of the code-point limit loads and one of twice the limit fails to load")
    void yamlContractFarOverTheCodePointLimitFailsToLoad(@TempDir Path directory) throws Exception {
        // "é" is one code point and two bytes in UTF-8, so the first file is longer than the limit in bytes
        int padding = CODE_POINT_LIMIT - HEAD.length() - TAIL.length();
        Path atLimit = write(directory, "at-limit.yaml", HEAD + "é".repeat(padding) + TAIL);
        Path doubled = write(directory, "doubled.yaml", HEAD + "é".repeat(2 * padding) + TAIL);

        assertDoesNotThrow(() -> load(atLimit));
        assertThrows(ExecutionException.class, () -> load(doubled));
    }

    private static Path write(Path directory, String name, String content) throws Exception {
        return Files.writeString(directory.resolve(name), content, StandardCharsets.UTF_8);
    }

    private void load(Path path) throws Exception {
        OpenAPIContract.from(vertx, path.toString())
                .toCompletionStage()
                .toCompletableFuture()
                .get(30, TimeUnit.SECONDS);
    }
}
