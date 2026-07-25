// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

assert new File(basedir, "target/classes/dev/vertique/it/CoreApp.class").isFile()

File generatedSources = new File(basedir, "target/generated-sources/annotations")
List<File> generatedJava = []
if (generatedSources.isDirectory()) {
    generatedSources.eachFileRecurse { file ->
        if (file.isFile() && file.name.endsWith(".java")) {
            generatedJava.add(file)
        }
    }
}
assert generatedJava.isEmpty(): "Core-only fixture generated unrelated sources: ${generatedJava}"

return true
