#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/.." && pwd)"
maven_wrapper="$repository_root/mvnw"

if [[ ! -x "$maven_wrapper" ]]; then
    echo "Maven wrapper is not executable: $maven_wrapper" >&2
    exit 1
fi

path_to_file_uri() {
    local absolute_path="$1"
    local character
    local encoded_path=""
    local hex
    local byte_value
    local index=0
    local LC_ALL=C

    if [[ "$absolute_path" != /* ]]; then
        echo "Cannot create a file URI from a non-absolute path: $absolute_path" >&2
        return 1
    fi

    while (( index < ${#absolute_path} )); do
        character="${absolute_path:index:1}"
        case "$character" in
            [A-Za-z0-9._~-] | /)
                encoded_path+="$character"
                ;;
            *)
                printf -v byte_value '%d' "'$character"
                printf -v hex '%02X' "$((byte_value & 0xff))"
                encoded_path+="%$hex"
                ;;
        esac
        index=$((index + 1))
    done

    printf 'file://%s\n' "$encoded_path"
}

assert_xml_safe_element_text() {
    local value="$1"

    case "$value" in
        *'&'* | *'<'* | *'>'*)
            echo "Generated repository URI is not safe for XML element text: $value" >&2
            return 1
            ;;
    esac
}

assert_repository_cache_uri_encoding() {
    local smoke_path='/tmp/vertique cache & #?%[]'
    local expected_uri='file:///tmp/vertique%20cache%20%26%20%23%3F%25%5B%5D'
    local unicode_smoke_path='/tmp/vertique-é-cache'
    local expected_unicode_uri='file:///tmp/vertique-%C3%A9-cache'
    local actual_uri

    actual_uri="$(path_to_file_uri "$smoke_path")"
    if [[ "$actual_uri" != "$expected_uri" ]]; then
        echo "Repository URI encoding smoke test failed." >&2
        echo "Expected: $expected_uri" >&2
        echo "Actual:   $actual_uri" >&2
        return 1
    fi
    assert_xml_safe_element_text "$actual_uri"

    actual_uri="$(path_to_file_uri "$unicode_smoke_path")"
    if [[ "$actual_uri" != "$expected_unicode_uri" ]]; then
        echo "Repository URI Unicode encoding smoke test failed." >&2
        echo "Expected: $expected_unicode_uri" >&2
        echo "Actual:   $actual_uri" >&2
        return 1
    fi
    assert_xml_safe_element_text "$actual_uri"
}

assert_repository_cache_uri_encoding
if (( $# > 1 )) || { (( $# == 1 )) && [[ "$1" != "--repository-uri-smoke-test" ]]; }; then
    echo "Usage: $0 [--repository-uri-smoke-test]" >&2
    exit 2
fi

repository_cache_input="${MAVEN_REPOSITORY_CACHE:-${HOME:?HOME must be set when MAVEN_REPOSITORY_CACHE is unset}/.m2/repository}"
if [[ "$repository_cache_input" != /* ]]; then
    echo "MAVEN_REPOSITORY_CACHE must be an absolute path: $repository_cache_input" >&2
    exit 1
fi
if [[ ! -d "$repository_cache_input" ]]; then
    echo "Maven repository cache directory does not exist: $repository_cache_input" >&2
    exit 1
fi
if ! repository_cache="$(cd "$repository_cache_input" && pwd -P)"; then
    echo "Cannot resolve Maven repository cache directory: $repository_cache_input" >&2
    exit 1
fi
repository_cache_uri="$(path_to_file_uri "$repository_cache")"
assert_xml_safe_element_text "$repository_cache_uri"

if [[ "${1:-}" == "--repository-uri-smoke-test" ]]; then
    echo "Repository URI encoding smoke test passed: $repository_cache_uri"
    exit 0
fi

temporary_root="${TMPDIR:-/tmp}"
run_root="$(mktemp -d "$temporary_root/vertique-codegen-reactor-XXXXXXXX")"
isolated_repository="$run_root/repository"
settings_file="$run_root/settings.xml"
poison_payload="$run_root/poison-payload"
poison_jar="$run_root/stale-processor.jar"
poison_manifest_before="$run_root/poison-before.sha256"
poison_manifest_after="$run_root/poison-after.sha256"

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
    printf '      <url>%s</url>\n' "$repository_cache_uri"
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
