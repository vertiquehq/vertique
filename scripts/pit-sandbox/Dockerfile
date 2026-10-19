# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# Disposable runtime for mutation testing (scripts/pit-sandbox.sh). Mutants run
# with real side effects, so they run here, as an unprivileged user, against a
# throwaway copy of the source.

FROM eclipse-temurin:25-jdk@sha256:2d5d5e9b533342a565907c84f47a2ec298c40cde7b13a3ca85d4950fa456a641

RUN useradd --create-home --uid 10001 pit \
    && mkdir -p /home/pit/.m2/repository /home/pit/.m2/wrapper /work \
    && chown -R pit:pit /home/pit /work

USER pit
ENV HOME=/home/pit
WORKDIR /work
