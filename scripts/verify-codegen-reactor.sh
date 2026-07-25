#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/.." && pwd)"
maven_wrapper="$repository_root/mvnw"
temporary_root="${TMPDIR:-/tmp}"
run_root="$(mktemp -d "$temporary_root/vertique-codegen-reactor-XXXXXXXX")"
isolated_repository="$run_root/repository"
settings_file="$run_root/settings.xml"
poison_payload="$run_root/poison-payload"
poison_jar="$run_root/stale-processor.jar"
poison_manifest_before="$run_root/poison-before.sha256"
poison_manifest_after="$run_root/poison-after.sha256"

if [[ ! -x "$maven_wrapper" ]]; then
    echo "Maven wrapper is not executable: $maven_wrapper" >&2
    exit 1
fi

resolve_reactor_revision() {
    awk '
        /<revision>/ {
            line = $0
            sub(/^.*<revision>[[:space:]]*/, "", line)
            sub(/[[:space:]]*<\/revision>.*$/, "", line)
            print line
            exit
        }
    ' "$repository_root/pom.xml"
}

reactor_revision="$(resolve_reactor_revision)"
if [[ -z "$reactor_revision" || "$reactor_revision" == *'${'* ]]; then
    echo "Cannot derive a concrete reactor revision from $repository_root/pom.xml" >&2
    exit 1
fi

mkdir -p "$isolated_repository" "$poison_payload/META-INF/services"
printf '%s\n' 'dev.vertique.codegen.poison.StaleProcessor' \
    > "$poison_payload/META-INF/services/javax.annotation.processing.Processor"
printf '%s\n' 'CODEGEN-014-STALE-SENTINEL' > "$poison_payload/CODEGEN-014-STALE-SENTINEL"
jar --create --file "$poison_jar" -C "$poison_payload" .

create_poison_artifact() {
    local artifact_id="$1"
    local artifact_directory="$isolated_repository/dev/vertique/$artifact_id/$reactor_revision"

    mkdir -p "$artifact_directory"
    cp "$poison_jar" "$artifact_directory/$artifact_id-$reactor_revision.jar"
    {
        printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
        printf '%s\n' '<project xmlns="http://maven.apache.org/POM/4.0.0">'
        printf '%s\n' '  <modelVersion>4.0.0</modelVersion>'
        printf '%s\n' '  <groupId>dev.vertique</groupId>'
        printf '  <artifactId>%s</artifactId>\n' "$artifact_id"
        printf '  <version>%s</version>\n' "$reactor_revision"
        printf '%s\n' '</project>'
    } > "$artifact_directory/$artifact_id-$reactor_revision.pom"
}

processor_artifacts=(
    vertique-codegen-all
    vertique-codegen-aop
    vertique-codegen-application
    vertique-codegen-cron
    vertique-codegen-dagger
    vertique-codegen-delayed-job
    vertique-codegen-events
    vertique-codegen-jaxrs
    vertique-codegen-kafka
    vertique-codegen-rest-client
    vertique-codegen-sanitization
    vertique-codegen-services
    vertique-codegen-workflow
)

for artifact_id in "${processor_artifacts[@]}"; do
    create_poison_artifact "$artifact_id"
done

{
    printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
    printf '%s\n' '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">'
    printf '%s\n' '  <mirrors>'
    printf '%s\n' '    <mirror>'
    printf '%s\n' '      <id>existing-local-cache</id>'
    printf '%s\n' '      <mirrorOf>*</mirrorOf>'
    printf '      <url>file://%s</url>\n' "${MAVEN_REPOSITORY_CACHE:-$HOME/.m2/repository}"
    printf '%s\n' '    </mirror>'
    printf '%s\n' '  </mirrors>'
    printf '%s\n' '</settings>'
} > "$settings_file"

record_poison_manifest() {
    local destination="$1"

    : > "$destination"
    for artifact_id in "${processor_artifacts[@]}"; do
        shasum -a 256 \
            "$isolated_repository/dev/vertique/$artifact_id/$reactor_revision/$artifact_id-$reactor_revision.jar" \
            >> "$destination"
    done
}

run_build() {
    local label="$1"
    shift
    local log_file="$run_root/$label.log"

    echo "Running $label reactor verification..."
    if ! "$maven_wrapper" -ntp -nsu \
        -s "$settings_file" \
        -Dmaven.repo.local="$isolated_repository" \
        -f "$repository_root/pom.xml" \
        "$@" \
        -pl examples/vertique-example-hello \
        -am \
        clean verify \
        -DskipTests \
        > "$log_file" 2>&1; then
        tail -n 200 "$log_file" >&2
        return 1
    fi
}

record_poison_manifest "$poison_manifest_before"
run_build sequential
run_build parallel -T 2
record_poison_manifest "$poison_manifest_after"

if ! cmp -s "$poison_manifest_before" "$poison_manifest_after"; then
    echo "Maven replaced one or more poisoned processor artifacts instead of using the reactor." >&2
    exit 1
fi

hello_generated="$repository_root/examples/vertique-example-hello/target/generated-sources/annotations"
expected_generated_sources=(
    dev/vertique/examples/hello/AppComponentVertiqueComponentFactory.java
    dev/vertique/examples/hello/DaggerAppComponent.java
    dev/vertique/examples/hello/resource/GeneratedJaxRsResourcesModule.java
    dev/vertique/examples/hello/resource/HelloResource_JaxRsDescriptor.java
)

for generated_source in "${expected_generated_sources[@]}"; do
    if [[ ! -f "$hello_generated/$generated_source" ]]; then
        echo "Missing generated source after poisoned-repository build: $generated_source" >&2
        exit 1
    fi
done

effective_pom="$run_root/vertique-example-hello-effective-pom.xml"
effective_log="$run_root/effective-pom.log"
"$maven_wrapper" -ntp -nsu \
    -s "$settings_file" \
    -Dmaven.repo.local="$isolated_repository" \
    -f "$repository_root/examples/vertique-example-hello/pom.xml" \
    org.apache.maven.plugins:maven-help-plugin:3.5.2:effective-pom \
    -Doutput="$effective_pom" \
    > "$effective_log" 2>&1

if ! awk '
    /<annotationProcessorPaths/ { capturing = 1 }
    capturing { print }
    /<\/annotationProcessorPaths>/ { capturing = 0 }
' "$effective_pom" | grep -q '<artifactId>vertique-codegen-all</artifactId>'; then
    echo "Effective compiler configuration does not contain vertique-codegen-all." >&2
    exit 1
fi

echo "CODEGEN-014 reactor verification passed."
echo "Evidence: $run_root"
