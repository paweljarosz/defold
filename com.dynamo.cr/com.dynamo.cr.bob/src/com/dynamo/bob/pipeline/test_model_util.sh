#!/usr/bin/env bash

# We create the jar via "./scripts/build.py build_bob_light --skip-tests"

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

set -e

CLASS_NAME=ModelUtil

DEFOLD_DIR=$DYNAMO_HOME/../..
MODELC_BUILD_DIR=${DEFOLD_DIR}/engine/modelc/build/default/src

PACKAGE_CLASS=com.dynamo.bob.pipeline.$CLASS_NAME
JAR=${DYNAMO_HOME}/share/java/bob-light.jar

#JNI_DEBUG_FLAGS="-Xcheck:jni"

echo "Running jar:" $JAR

java ${JNI_DEBUG_FLAGS} -Djava.library.path=${MODELC_BUILD_DIR} -cp ${JAR} ${PACKAGE_CLASS} $*
