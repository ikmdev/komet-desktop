#!/bin/sh
# Maven Wrapper launcher — installed by ike:init
# Downloads and caches the Maven version specified in
# .mvn/wrapper/maven-wrapper.properties
#
# This is a minimal bootstrap. For the full-featured wrapper script,
# run: mvn wrapper:wrapper

set -e

PROPS_FILE="$(dirname "$0")/.mvn/wrapper/maven-wrapper.properties"
if [ ! -f "$PROPS_FILE" ]; then
    echo "Error: $PROPS_FILE not found" >&2
    exit 1
fi

DIST_URL=$(grep '^distributionUrl=' "$PROPS_FILE" | cut -d'=' -f2-)
MAVEN_VERSION=$(grep '^maven.version=' "$PROPS_FILE" | cut -d'=' -f2-)

WRAPPER_HOME="${HOME}/.m2/wrapper/dists/apache-maven-${MAVEN_VERSION}"
MAVEN_HOME="${WRAPPER_HOME}/apache-maven-${MAVEN_VERSION}"

if [ ! -d "$MAVEN_HOME" ]; then
    echo "Downloading Maven ${MAVEN_VERSION}..."
    mkdir -p "$WRAPPER_HOME"
    ZIP_FILE="${WRAPPER_HOME}/apache-maven-${MAVEN_VERSION}-bin.zip"
    curl -fsSL -o "$ZIP_FILE" "$DIST_URL"
    unzip -qo "$ZIP_FILE" -d "$WRAPPER_HOME"
    rm -f "$ZIP_FILE"
    echo "Maven ${MAVEN_VERSION} installed to ${MAVEN_HOME}"
fi

exec "$MAVEN_HOME/bin/mvn" "$@"
