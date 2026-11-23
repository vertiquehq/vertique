# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# Disposable runtime for mutation testing (scripts/pit-sandbox.sh). Mutants run
# with real side effects, so they run here, as an unprivileged user, against a
# throwaway copy of the source.

FROM eclipse-temurin:25-jdk@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3

RUN useradd --create-home --uid 10001 pit \
    && mkdir -p /home/pit/.m2/repository /home/pit/.m2/wrapper /work \
    && chown -R pit:pit /home/pit /work

USER pit
ENV HOME=/home/pit
WORKDIR /work
