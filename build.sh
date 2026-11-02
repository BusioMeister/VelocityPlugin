#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
sh ./mvnw -B -ntp -N -f build-support/pom.xml install
sh ./mvnw -B -ntp -f build-support/shared/pom.xml clean install
sh ./mvnw -B -ntp clean verify
