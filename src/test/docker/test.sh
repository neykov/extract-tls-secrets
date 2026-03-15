#!/bin/bash

set -e
set -x

echo $@

case "$1" in
  "agent") INJECT_TYPE="agent";;
  "attach") INJECT_TYPE="attach";;
  *) echo "Invalid parameter"; exit 1
esac

JAR_PATH=$2

CWD="$( cd "$(dirname "$0")" ; pwd -P )"
ROOT=$( cd "$CWD/../../.." && pwd )
TEST_TMP="$ROOT/target/test/temp"
SECRETS_VOLUME="$TEST_TMP/secrets"

# Include all LTS releases plus the latest non-LTS if available.
# To update: check https://hub.docker.com/r/azul/zulu-openjdk/tags for available versions.
JAVA_VERSIONS="25 21 17 11 8 6"

# ── Provider declarations ──────────────────────────────────────────────────
# SKIP:   space-separated Java versions to skip for this provider
# MARKER: string expected in both keylog files to confirm instrumentation fired
# The provider name is passed as -Dprovider=<name> to both server and client.

PROVIDERS="JSSE BCJSSE"

JSSE_SKIP=""
JSSE_MARKER="CipherSuite:"

BCJSSE_SKIP=""
BCJSSE_MARKER="BCJSSE CipherSuite:"
# ──────────────────────────────────────────────────────────────────────────

$CWD/test_errors.sh $JAR_PATH

rm -r $TEST_TMP || true
mkdir -p $SECRETS_VOLUME

docker network create ssl-secrets || true

docker build -f $CWD/Dockerfile.utils $CWD -t ssl-secrets-utils

cat <<EOF | docker run -i --rm --name ssl-secrets-keystore --network none \
  -v $SECRETS_VOLUME:/secrets \
  azul/zulu-openjdk:8 bash
    keytool -genkey -noprompt -alias server -dname "CN=ssl-secrets-server, OU=Unit, O=Company, L=Sofia, ST=Unknown, C=BG" \
      -storepass password -keypass password -keyalg RSA -keystore /secrets/keystore -deststoretype pkcs12 \
      -ext SAN=dns:ssl-secrets-server
EOF

ssl-secrets-utils()
{
  docker run --rm --network none \
        -v $SECRETS_VOLUME:/secrets \
        ssl-secrets-utils "$@"
}

for JAVA_IMAGE_TAG in $JAVA_VERSIONS; do

  echo -e "\n" \
    "=============================================\n" \
    "   Java $JAVA_IMAGE_TAG \n" \
    "=============================================\n\n"

  RUNNING_CONTAINERS="$(docker ps -qa)"
  if [ -n "$RUNNING_CONTAINERS" ]; then
    docker rm -f $RUNNING_CONTAINERS || true
  fi

  docker build -f $CWD/Dockerfile.server $CWD -t ssl-secrets-server \
    --build-arg JAVA_IMAGE_TAG=$JAVA_IMAGE_TAG

  for PROVIDER in $PROVIDERS; do
    SKIP_VAR="${PROVIDER}_SKIP"
    SKIP="${!SKIP_VAR}"
    MARKER_VAR="${PROVIDER}_MARKER"
    SECRET_MARKER="${!MARKER_VAR}"
    PROVIDER_FLAG="-Dprovider=$PROVIDER"

    [[ " $SKIP " == *" $JAVA_IMAGE_TAG "* ]] && continue

    docker rm -f ssl-secrets-server 2>/dev/null || true

    SERVER_AGENT_OPT=""
    if [ "$INJECT_TYPE" = "agent" ]; then
      SERVER_AGENT_OPT="-javaagent:/project/$JAR_PATH=/secrets/server.keys"
    fi
    docker run -d --name ssl-secrets-server --network ssl-secrets \
      -v $ROOT:/project \
      -v $SECRETS_VOLUME:/secrets \
      ssl-secrets-server java -cp "/project/target/test-classes:/project/target/test-lib/*" \
      $PROVIDER_FLAG \
      $SERVER_AGENT_OPT \
      -Dkeystore.file=/secrets/keystore \
      name.neykov.secrets.TestServer

    while ! docker logs ssl-secrets-server 2>&1 | grep -qs "server ready"; do sleep 0.1; done

    if [ "$INJECT_TYPE" = "attach" ]; then
      docker exec ssl-secrets-server java -jar /project/$JAR_PATH list
      docker exec ssl-secrets-server java -jar /project/$JAR_PATH 1 /secrets/server.keys
    fi

    for PROTO in "TLSv1.3" "TLSv1.2"; do

      case "$PROTO-$JAVA_IMAGE_TAG-$PROVIDER" in
        # JDK versions without native TLS 1.3 support (any provider)
        "TLSv1.3-6-"*|"TLSv1.3-7-"*|"TLSv1.3-9-"*|"TLSv1.3-10-"*) continue;;
      esac

      echo -e "\n" \
        "=============================================\n" \
        "   Java $JAVA_IMAGE_TAG - $PROVIDER $PROTO\n" \
        "=============================================\n\n"

      rm $SECRETS_VOLUME/client.keys $SECRETS_VOLUME/server.keys \
         $SECRETS_VOLUME/secrets.pcap || true

      docker rm -f ssl-secrets-tcpdump 2>/dev/null || true
      docker run -d --name ssl-secrets-tcpdump --rm --network container:ssl-secrets-server \
        -v $SECRETS_VOLUME:/secrets ssl-secrets-utils \
        tcpdump 'port 443' -Uw /secrets/secrets.pcap
      while ! docker logs ssl-secrets-tcpdump 2>&1 | grep -qs "listening on"; do sleep 0.1; done

      docker run --network ssl-secrets --rm \
        -v $ROOT:/project -v $SECRETS_VOLUME:/secrets \
        ssl-secrets-server java -cp "/project/target/test-classes:/project/target/test-lib/*" \
        -Djavax.net.ssl.trustStoreType=pkcs12 \
        -Djavax.net.ssl.trustStore=/secrets/keystore \
        -Djavax.net.ssl.trustStorePassword=password \
        -Djdk.tls.client.protocols=$PROTO \
        -javaagent:/project/$JAR_PATH=/secrets/client.keys \
        $PROVIDER_FLAG \
        name.neykov.secrets.TestClient https://ssl-secrets-server/secret.txt

      # Sometimes there won't be any captured packets — wait for a flush timeout.
      sleep 1
      docker stop ssl-secrets-tcpdump || true

      # Show captured keys
      ssl-secrets-utils cat /secrets/server.keys /secrets/client.keys

      # Assert both sides captured secrets via the expected instrumentation path
      grep -q "$SECRET_MARKER" $SECRETS_VOLUME/server.keys
      grep -q "$SECRET_MARKER" $SECRETS_VOLUME/client.keys

      # Check we can decrypt the capture using the server keys
      ssl-secrets-utils tshark \
        -o "tls.keylog_file:/secrets/server.keys" \
        -nr /secrets/secrets.pcap -q -z follow,tls,ascii,0 | \
        grep 'PLAIN TEXT'

      # Check we can decrypt the capture using the client keys
      ssl-secrets-utils tshark \
        -o "tls.keylog_file:/secrets/client.keys" \
        -nr /secrets/secrets.pcap -q -z follow,tls,ascii,0 | \
        grep 'PLAIN TEXT'

    done

  done

done

docker rm -f ssl-secrets-server
