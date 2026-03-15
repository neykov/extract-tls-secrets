#!/bin/bash

set -x

echo $@

JAR_PATH=$1

CWD="$( cd "$(dirname "$0")" ; pwd -P )"
ROOT=$( cd "$CWD/../../.." && pwd )

docker image pull azul/zulu-openjdk:8
docker image pull azul/zulu-openjdk:8-jre
docker image pull eclipse-temurin:11-jre

OUT=$(docker run --rm --network none \
  -v $ROOT:/project \
  azul/zulu-openjdk:8 \
  java -jar /project/$JAR_PATH 2>&1)

[[ "$OUT" == *"No arguments provided"* ]] || exit 1
[[ "$OUT" == *"Usage"* ]] || exit 1

OUT=$(docker run --rm --network none \
  -v $ROOT:/project \
  azul/zulu-openjdk:8-jre \
  java -jar /project/$JAR_PATH list 2>&1)

[[ "$OUT" == *"Invalid JAVA_HOME environment variable"* ]] || exit 1
[[ "$OUT" == *"Must point to a local JDK installation containing a 'lib/tools.jar'"* ]] || exit 1

# On Java 9+, a JRE without jdk.attach should fail with a clear message.
# eclipse-temurin:11-jre is used here because Zulu JRE images bundle jdk.attach.
OUT=$(docker run --rm --network none \
  -v $ROOT:/project \
  eclipse-temurin:11-jre \
  java -jar /project/$JAR_PATH list 2>&1)

[[ "$OUT" == *"No access to JDK classes. Make sure to use the java executable from a JDK install."* ]] || exit 1

OUT=$(docker run --rm --network none \
  -v $ROOT:/project \
  azul/zulu-openjdk:8 \
  bash -c "unset JAVA_HOME; java -jar /project/$JAR_PATH list 2>&1")

[[ "$OUT" == *"No JAVA_HOME environment variable found. Must point to a local JDK installation"* ]] || exit 1

OUT=$(docker run --rm --network none \
  -v $ROOT:/project \
  azul/zulu-openjdk:8 \
  bash -c "JAVA_HOME=/tmp java -jar /project/$JAR_PATH list 2>&1")

[[ "$OUT" == *"Invalid JAVA_HOME environment variable"* ]] || exit 1
[[ "$OUT" == *"Must point to a local JDK installation containing a 'lib/tools.jar'"* ]] || exit 1
