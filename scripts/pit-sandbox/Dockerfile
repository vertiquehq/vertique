# SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
# SPDX-License-Identifier: EUPL-1.2
#
# Disposable runtime for mutation testing (scripts/pit-sandbox.sh). Mutants run
# with real side effects, so they run here, as an unprivileged user, against a
# throwaway copy of the source.

FROM eclipse-temurin:25-jdk@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc

RUN useradd --create-home --uid 10001 pit \
    && mkdir -p /home/pit/.m2/repository /home/pit/.m2/wrapper /work \
    && chown -R pit:pit /home/pit /work

USER pit
ENV HOME=/home/pit
WORKDIR /work
