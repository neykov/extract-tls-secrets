#!/bin/bash

set -x

echo $@

JAR_PATH=$1

CWD="$( cd "$(dirname "$0")" ; pwd -P )"
ROOT=$( cd "$CWD/../../.." && pwd )

docker image pull openjdk:8
docker image pull openjdk:8-jre
docker image pull openjdk:11-jre

OUT=$(docker run --rm --network none \
  -v $ROOT:/project \
  openjdk:8 \
  java -jar /project/$JAR_PATH 2>&1)

[[ "$OUT" == *"Missing required argument"* ]] || exit 1
[[ "$OUT" == *"Usage"* ]] || exit 1

OUT=$(docker run --rm --network none \
  -v $ROOT:/project \
  openjdk:8-jre \
  java -jar /project/$JAR_PATH list 2>&1)

[[ "$OUT" == *"Invalid JAVA_HOME environment variable"* ]] || exit 1
[[ "$OUT" == *"Must point to a local JDK installation containing a 'lib/tools.jar'"* ]] || exit 1

OUT=$(docker run --rm --network none \
  -v $ROOT:/project \
  openjdk:11-jre \
  java -jar /project/$JAR_PATH list 2>&1)

[[ "$OUT" == *"No access to JDK classes. Make sure to use the java executable from a JDK install."* ]] || exit 1

OUT=$(docker run --rm --network none \
  -v $ROOT:/project \
  openjdk:8 \
  bash -c "unset JAVA_HOME; java -jar /project/$JAR_PATH list 2>&1")

[[ "$OUT" == *"No JAVA_HOME environment variable found. Must point to a local JDK installation"* ]] || exit 1

OUT=$(docker run --rm --network none \
  -v $ROOT:/project \
  openjdk:8 \
  bash -c "JAVA_HOME=/tmp java -jar /project/$JAR_PATH list 2>&1")

[[ "$OUT" == *"Invalid JAVA_HOME environment variable"* ]] || exit 1
[[ "$OUT" == *"Must point to a local JDK installation containing a 'lib/tools.jar'"* ]] || exit 1
