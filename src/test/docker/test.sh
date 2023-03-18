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
JAVA_VERSIONS="21 20 19 18 17 16 15 14 13 12 11 10 9 8 7 6"

$CWD/test_errors.sh $JAR_PATH

rm -r $TEST_TMP || true
mkdir -p $SECRETS_VOLUME

docker network create ssl-secrets || true

docker build -f $CWD/Dockerfile.utils $CWD -t ssl-secrets-utils

# Passing "-deststoretype pkcs12" breaks Java 6
cat <<EOF | docker run -i --rm --name ssl-secrets-keystore --network none \
  -v $SECRETS_VOLUME:/secrets \
  openjdk:8 bash
    keytool -genkey -noprompt -alias tomcat -dname "CN=ssl-secrets-tomcat, OU=Unit, O=Company, L=Sofia, ST=Unknown, C=BG" \
      -storepass password -keypass password -keyalg RSA -keystore /secrets/keystore
EOF

for JAVA_IMAGE_TAG in $JAVA_VERSIONS; do

    echo -e "\n" \
      "=============================================\n" \
      "   Java $JAVA_IMAGE_TAG \n" \
      "=============================================\n\n"

  RUNNING_CONTAINERS="$(docker ps -qa)"
  if [ -n "$RUNNING_CONTAINERS" ]; then
    docker rm -f $RUNNING_CONTAINERS || true
  fi

  docker build -f $CWD/Dockerfile.tomcat $CWD -t ssl-secrets-tomcat --build-arg JAVA_IMAGE_TAG=$JAVA_IMAGE_TAG

  if [ "$INJECT_TYPE" = "agent" ]; then
    AGENT_OPT="-javaagent:/project/$JAR_PATH=/secrets/server.keys"
  fi
  # Start Tomcat
  docker run -d --name ssl-secrets-tomcat --network ssl-secrets -p 443:443 \
    -v $ROOT:/project \
    -v $CWD/server.xml:/apache-tomcat/conf/server.xml \
    -v $SECRETS_VOLUME:/secrets \
    -e CATALINA_OPTS="$AGENT_OPT -Dkeystore.file=/secrets/keystore " \
    ssl-secrets-tomcat /apache-tomcat/bin/catalina.sh run

  # Wait for tomcat to complete starting up
  while ! docker run --network ssl-secrets --rm \
      -v $ROOT:/project -v $SECRETS_VOLUME:/secrets \
      ssl-secrets-tomcat java -cp /project/target/test-classes \
      -Djavax.net.ssl.trustStore=/secrets/keystore -Djavax.net.ssl.trustStorePassword=password \
      name.neykov.secrets.TestURLConnection https://ssl-secrets-tomcat/secret.txt 2> /dev/null;
  do
    sleep 1;
  done

  if [ "$INJECT_TYPE" = "attach" ]; then
    docker exec ssl-secrets-tomcat java -jar /project/$JAR_PATH list
    docker exec ssl-secrets-tomcat java -jar /project/$JAR_PATH 1 /secrets/server.keys
  fi
  docker logs ssl-secrets-tomcat

  # Start tcpdump listening on the tomcat port
  docker run -d --name ssl-secrets-tcpdump --rm --network container:ssl-secrets-tomcat \
    -v $SECRETS_VOLUME:/secrets ssl-secrets-utils \
    tcpdump 'port 443' -Uw /secrets/secrets.pcap


  for PROTO in "TLSv1.3" "TLSv1.1"; do

    case "$PROTO-$JAVA_IMAGE_TAG" in
      "TLSv1.3-6"|"TLSv1.3-7"|"TLSv1.3-9"|"TLSv1.3-10") continue;;
    esac

    echo -e "\n" \
      "=============================================\n" \
      "   Java $JAVA_IMAGE_TAG - $PROTO\n" \
      "=============================================\n\n"

    rm $SECRETS_VOLUME/client.keys $SECRETS_VOLUME/server.keys || true

    # Run a test request
    docker run --network ssl-secrets --rm \
      -v $ROOT:/project -v $SECRETS_VOLUME:/secrets \
      ssl-secrets-tomcat java -cp /project/target/test-classes \
      -Djavax.net.ssl.trustStore=/secrets/keystore -Djavax.net.ssl.trustStorePassword=password \
      -Dhttps.protocols=$PROTO \
      -Djdk.tls.client.protocols=$PROTO \
      -javaagent:/project/$JAR_PATH=/secrets/client.keys \
      name.neykov.secrets.TestURLConnection https://ssl-secrets-tomcat/secret.txt

    # Show captured keys
    docker run --rm --network none \
      -v $SECRETS_VOLUME:/secrets \
      ssl-secrets-utils \
      cat /secrets/server.keys /secrets/client.keys

    LAST_STREAM_ID=$(docker run --rm --network none \
      -v $SECRETS_VOLUME:/secrets \
      ssl-secrets-utils tshark \
      -nr /secrets/secrets.pcap \
      -T fields -e tcp.stream | sort -n | tail -1 )

    # Check we can decrypt the capture using the server keys
    docker run --rm --network none \
      -v $SECRETS_VOLUME:/secrets \
      ssl-secrets-utils tshark \
      -o "tls.keylog_file:/secrets/server.keys" \
      -nr /secrets/secrets.pcap -q -z follow,http,ascii,$LAST_STREAM_ID | \
      grep 'PLAIN TEXT'

    # Check we can decrypt the capture using the client keys
    docker run --rm --network none \
      -v $SECRETS_VOLUME:/secrets \
      ssl-secrets-utils tshark \
      -o "tls.keylog_file:/secrets/client.keys" \
      -nr /secrets/secrets.pcap -q -z follow,http,ascii,$LAST_STREAM_ID | \
      grep 'PLAIN TEXT'

  done
done

docker rm -f $(docker ps -qa)
