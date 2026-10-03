// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A file a test writes at a relative path under the JVM working directory, where Vert.x's file
 * resolver looks for a relative path before the classpath. {@link #close()} deletes the file and every
 * directory {@link #write} created for it, deepest first, and nothing that existed before.
 *
 * <p>Use it in try-with-resources, or close it in {@code finally}:
 *
 * <pre>{@code
 * try (WorkingDirectoryFile file = WorkingDirectoryFile.write(ContractFiles.SHADOWED, text)) {
 *     // file.absolutePath() is the location the source line names
 * }
 * }</pre>
 */
public final class WorkingDirectoryFile implements AutoCloseable {

    private final Path absolutePath;
    private final Deque<Path> createdDirectories;

    private WorkingDirectoryFile(Path absolutePath, Deque<Path> createdDirectories) {
        this.absolutePath = absolutePath;
        this.createdDirectories = createdDirectories;
    }

    /**
     * Writes a UTF-8 text file at a relative path under the working directory, creating the missing
     * parent directories. An existing file at that path is refused, so a test never overwrites or
     * later deletes a file it did not write.
     *
     * @param relativePath the relative path, such as {@value ContractFiles#SHADOWED}
     * @param text         the file's content
     * @return the written file
     * @throws IOException when the file exists already or cannot be written
     */
    public static WorkingDirectoryFile write(String relativePath, String text) throws IOException {
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path file = workingDirectory.resolve(relativePath).normalize();
        if (!file.startsWith(workingDirectory) || file.equals(workingDirectory)) {
            throw new IOException("not a relative file path under the working directory: " + relativePath);
        }
        if (Files.exists(file)) {
            throw new IOException("a file exists already at " + file);
        }
        Deque<Path> created = new ArrayDeque<>();
        Path parent = file.getParent();
        Deque<Path> missing = new ArrayDeque<>();
        for (Path directory = parent; !Files.exists(directory); directory = directory.getParent()) {
            missing.push(directory);
        }
        WorkingDirectoryFile written = new WorkingDirectoryFile(file, created);
        try {
            while (!missing.isEmpty()) {
                Path directory = missing.pop();
                Files.createDirectory(directory);
                created.push(directory);
            }
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException failure) {
            try {
                written.close();
            } catch (IOException | RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        return written;
    }

    /**
     * Returns the file's absolute, normalized path under the working directory.
     *
     * @return the path
     */
    public Path absolutePath() {
        return absolutePath;
    }

    /**
     * Deletes the file, if present, then every directory {@link #write} created, deepest first. Every
     * deletion is attempted even when an earlier one failed. A created directory that something else
     * wrote into is not empty and stays, without a failure: it is not this file's to delete.
     *
     * @throws IOException when a deletion failed for any other reason: the first failure, with every
     *     later one suppressed
     */
    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            Files.deleteIfExists(absolutePath);
        } catch (IOException fileNotDeleted) {
            failure = fileNotDeleted;
        }
        while (!createdDirectories.isEmpty()) {
            try {
                Files.deleteIfExists(createdDirectories.pop());
            } catch (DirectoryNotEmptyException writtenIntoByOthers) {
                // A directory something else wrote into stays.
            } catch (IOException directoryNotDeleted) {
                if (failure == null) {
                    failure = directoryNotDeleted;
                } else {
                    failure.addSuppressed(directoryNotDeleted);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
