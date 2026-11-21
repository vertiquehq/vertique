# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# Disposable runtime for mutation testing (scripts/pit-sandbox.sh). Mutants run
# with real side effects, so they run here, as an unprivileged user, against a
# throwaway copy of the source.

FROM eclipse-temurin:25-jdk@sha256:b8705e936964c228dfce359e63dbf08f6c69cb380ed1d576b1f6b1064f15567d

RUN useradd --create-home --uid 10001 pit \
    && mkdir -p /home/pit/.m2/repository /home/pit/.m2/wrapper /work \
    && chown -R pit:pit /home/pit /work

USER pit
ENV HOME=/home/pit
WORKDIR /work
