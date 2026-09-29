# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# Disposable runtime for mutation testing (scripts/pit-sandbox.sh). Mutants run
# with real side effects, so they run here, as an unprivileged user, against a
# throwaway copy of the source.

FROM eclipse-temurin:21-jdk@sha256:4d06038800655fe1211760cd561de70ef2ed7a47f5d69255e9834414602b7026

RUN useradd --create-home --uid 10001 pit \
    && mkdir -p /home/pit/.m2/repository /home/pit/.m2/wrapper /work \
    && chown -R pit:pit /home/pit /work

USER pit
ENV HOME=/home/pit
WORKDIR /work
