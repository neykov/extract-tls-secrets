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
JSSE_SSL_PROVIDER="SunJSSE"

BCJSSE_SKIP=""
BCJSSE_MARKER="BCJSSE CipherSuite:"
# BCJSSE is registered by the application after premain; not visible in the
# agent's initial provider log. Leave unset so check_provider_logs skips it.
BCJSSE_SSL_PROVIDER=""

# IBM JSSE2 — ibmjava:8 (IBM SDK for Java 8, IBM J9 JVM) registers IBMJSSE2 as
# provider #1 before premain; the provider-name check is valid for that image.
# ibm-semeru-runtimes (open edition) uses SunJSSE and is covered by JSSE above.
IBMJSSE2_SKIP=""
# IBM's TlsKeyMaterialGenerator hook writes CLIENT_RANDOM directly without a
# comment line (no SSL session available at the crypto layer).
IBMJSSE2_MARKER="CLIENT_RANDOM"
IBMJSSE2_SSL_PROVIDER="IBMJSSE2"
# ──────────────────────────────────────────────────────────────────────────

# ── BC compat matrix ───────────────────────────────────────────────────────
# Tests BCJSSE instrumentation across key BC versions, run on Java 8 only.
# "default" means the version compiled by Maven (pinned in pom.xml, currently 1.73).
#
# Versions chosen:
#   1.57 — earliest bctls release; TLS 1.0-1.2 only (no establish13Phase* methods).
#          Uses "securityParameters" field (renamed to "securityParametersHandshake"
#          in 1.61) and has no "negotiatedVersion" field; both handled via fallback.
#   1.66 — first version with TLS 1.3 support (establish13Phase* methods added to TlsUtils;
#          exporterMasterSecret added to SecurityParameters)
#   latest — resolved dynamically from Maven Central at test time
#
# Maven artifact classifier:
#   BC 1.57–1.69: bctls-jdk15on / bcprov-jdk15on
#   BC 1.70+:     bctls-jdk15to18 / bcprov-jdk15to18
#
# Skip rule within this matrix:
#   TLSv1.3 + BC < 1.70: TLSv1.3 is not reliably exposed via setEnabledProtocols()
#   in BCJSSE < 1.70 (throws IllegalArgumentException in some versions, ignored in
#   others); BC 1.70 was the major BCJSSE refactoring that wired TLS 1.3 properly.

BC_COMPAT_JAVA=8
# ──────────────────────────────────────────────────────────────────────────

# Assert that both secrets files contain keylog lines appropriate for protocol $1,
# and do NOT contain keylog lines for the other protocol.
# TLS 1.3 produces CLIENT_HANDSHAKE_TRAFFIC_SECRET; TLS 1.0-1.2 produces CLIENT_RANDOM.
# Note: "! cmd || return 1" is used for negative checks because bash set -e does not
# propagate through "!" negation — the explicit "|| return 1" ensures failure is raised.
assert_protocol_keys() {
  local proto="$1"
  if [ "$proto" = "TLSv1.3" ]; then
    grep -q "^CLIENT_HANDSHAKE_TRAFFIC_SECRET " $SECRETS_VOLUME/server.keys
    grep -q "^CLIENT_HANDSHAKE_TRAFFIC_SECRET " $SECRETS_VOLUME/client.keys
    ! grep -q "^CLIENT_RANDOM " $SECRETS_VOLUME/server.keys || return 1
    ! grep -q "^CLIENT_RANDOM " $SECRETS_VOLUME/client.keys || return 1
  else
    grep -q "^CLIENT_RANDOM " $SECRETS_VOLUME/server.keys
    grep -q "^CLIENT_RANDOM " $SECRETS_VOLUME/client.keys
    ! grep -q "^CLIENT_HANDSHAKE_TRAFFIC_SECRET " $SECRETS_VOLUME/server.keys || return 1
    ! grep -q "^CLIENT_HANDSHAKE_TRAFFIC_SECRET " $SECRETS_VOLUME/client.keys || return 1
  fi
}

# Poll docker logs for a string, failing after $3 * 0.1 s (default 100 iterations = 10 s).
wait_for_log() {
  local container="$1" pattern="$2" max="${3:-100}" i=0
  while ! docker logs "$container" 2>&1 | grep -qs "$pattern"; do
    i=$((i + 1))
    if [ "$i" -ge "$max" ]; then
      echo "Timed out waiting for '$pattern' in $container logs" >&2
      return 1
    fi
    sleep 0.1
  done
}

# Start the server container for a given classpath and provider, wait until ready,
# and attach the agent if using attach mode.
start_server() {
  local cp="$1" provider="$2"
  local agent_opt=""
  if [ "$INJECT_TYPE" = "agent" ]; then
    agent_opt="-javaagent:/project/$JAR_PATH=/secrets/server.keys"
  fi
  docker rm -f ssl-secrets-server 2>/dev/null || true
  docker run -d --name ssl-secrets-server --network ssl-secrets \
    -v $ROOT:/project \
    -v $SECRETS_VOLUME:/secrets \
    ssl-secrets-server java -cp "$cp" \
    -Dprovider=$provider \
    $agent_opt \
    -Dkeystore.file=/secrets/keystore \
    name.neykov.secrets.TestServer
  wait_for_log ssl-secrets-server "server ready"
  if [ "$INJECT_TYPE" = "attach" ]; then
    docker exec ssl-secrets-server java -jar /project/$JAR_PATH list
    docker exec ssl-secrets-server java -jar /project/$JAR_PATH 1 /secrets/server.keys
  fi
  check_provider_logs "$provider" "$INJECT_TYPE"
}

# Assert agent logged expected security provider diagnostics.
# Usage: check_provider_logs <provider> <inject_type>
check_provider_logs() {
  local provider="$1" inject_type="$2"
  wait_for_log ssl-secrets-server "Registered TLS providers"
  local ssl_provider_var="${provider}_SSL_PROVIDER"
  local expected_provider="${!ssl_provider_var}"
  if [ -n "$expected_provider" ]; then
    wait_for_log ssl-secrets-server "Registered TLS providers:.*$expected_provider"
  fi
}

# Verify that a captured pcap can be decrypted using the given keylog file.
check_decryptable() {
  local keyfile="$1"
  ssl-secrets-utils tshark \
    -o "tls.keylog_file:$keyfile" \
    -nr /secrets/secrets.pcap -q -z follow,tls,ascii,0 | \
    grep 'PLAIN TEXT'
}

# Run a single protocol test: capture traffic, run the client, assert keys and decryption.
# Usage: run_proto_test <proto> <cp> <provider> <marker>
run_proto_test() {
  local proto="$1" cp="$2" provider="$3" marker="$4"

  rm $SECRETS_VOLUME/client.keys $SECRETS_VOLUME/server.keys \
     $SECRETS_VOLUME/secrets.pcap || true

  docker rm -f ssl-secrets-tcpdump 2>/dev/null || true
  docker run -d --name ssl-secrets-tcpdump --rm --network container:ssl-secrets-server \
    -v $SECRETS_VOLUME:/secrets ssl-secrets-utils \
    tcpdump 'port 443' -Uw /secrets/secrets.pcap
  wait_for_log ssl-secrets-tcpdump "listening on"

  docker run --network ssl-secrets --rm \
    -v $ROOT:/project -v $SECRETS_VOLUME:/secrets \
    ssl-secrets-server java -cp "$cp" \
    -Djavax.net.ssl.trustStoreType=jks \
    -Djavax.net.ssl.trustStore=/secrets/truststore \
    -Djavax.net.ssl.trustStorePassword=password \
    -Djdk.tls.client.protocols=$proto \
    -javaagent:/project/$JAR_PATH=/secrets/client.keys \
    -Dprovider=$provider \
    name.neykov.secrets.TestClient https://ssl-secrets-server/secret.txt

  # Sometimes there won't be any captured packets — wait for a flush timeout.
  sleep 1
  docker stop ssl-secrets-tcpdump || true

  # Show captured keys
  ssl-secrets-utils cat /secrets/server.keys /secrets/client.keys

  # Assert both sides captured secrets via the expected instrumentation path
  grep -q "$marker" $SECRETS_VOLUME/server.keys
  grep -q "$marker" $SECRETS_VOLUME/client.keys

  # Assert the logged key type matches the negotiated protocol
  assert_protocol_keys "$proto"

  check_decryptable /secrets/server.keys
  check_decryptable /secrets/client.keys
}

# Returns 0 (true) if version $1 is strictly less than version $2.
version_lt() {
  [ "$1" != "$2" ] && [ "$(printf '%s\n%s' "$1" "$2" | sort -V | head -1)" = "$1" ]
}

# Print the Maven artifact classifier for a given BC version.
bc_classifier() {
  version_lt "$1" "1.71" && echo "jdk15on" || echo "jdk15to18"
}

# Download bctls and bcprov JARs for a BC version into target/test-lib/bc-<version>/.
# Uses the local Maven cache on subsequent runs.
download_bc_version() {
  local version="$1"
  local dir="$ROOT/target/test-lib/bc-$version"
  mkdir -p "$dir"
  local classifier
  classifier=$(bc_classifier "$version")
  (cd "$ROOT" && mvn --no-transfer-progress dependency:copy \
    -Dartifact="org.bouncycastle:bctls-${classifier}:${version}" \
    -DoutputDirectory="target/test-lib/bc-$version/")
  (cd "$ROOT" && mvn --no-transfer-progress dependency:copy \
    -Dartifact="org.bouncycastle:bcprov-${classifier}:${version}" \
    -DoutputDirectory="target/test-lib/bc-$version/")
  # BC 1.70+ (jdk15to18 classifier) introduced bcutil as a separate JAR;
  # bctls references public ASN.1 classes (e.g. OIWObjectIdentifiers) from it.
  if [ "$classifier" = "jdk15to18" ]; then
    (cd "$ROOT" && mvn --no-transfer-progress dependency:copy \
      -Dartifact="org.bouncycastle:bcutil-${classifier}:${version}" \
      -DoutputDirectory="target/test-lib/bc-$version/")
  fi
}

# Resolve the latest BC release from Maven Central metadata.
BC_LATEST=$(curl -sf "https://repo1.maven.org/maven2/org/bouncycastle/bctls-jdk15to18/maven-metadata.xml" | \
  sed -n 's:.*<release>\(.*\)</release>.*:\1:p')
BC_COMPAT_VERSIONS="1.57 1.66 $BC_LATEST"

# Pre-populate versioned BC JAR directories for the compat matrix.
for _BC_VERSION in $BC_COMPAT_VERSIONS; do
  download_bc_version "$_BC_VERSION"
done

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
    keytool -exportcert -alias server -keystore /secrets/keystore -storepass password -file /secrets/server.crt
    # The truststore must be JKS, not PKCS12. Old BCJSSE (e.g. 1.57) requires
    # TrustedCertificateEntry entries in the truststore; the PKCS12 keystore above
    # stores the cert as a PrivateKeyEntry, which old BCJSSE's TrustManagerFactory
    # ignores, producing "trustAnchors parameter must be non-empty". Importing via
    # -importcert into a JKS store creates a TrustedCertificateEntry that all
    # BCJSSE versions accept.
    keytool -importcert -noprompt -alias server -file /secrets/server.crt \
      -keystore /secrets/truststore -storepass password -deststoretype jks
EOF

ssl-secrets-utils()
{
  docker run --rm --network none \
        -v $SECRETS_VOLUME:/secrets \
        ssl-secrets-utils "$@"
}

# ══════════════════════════════════════════════════════════════════════════════
#  Cross-Java matrix: JSSE + BCJSSE (latest/default BC) across all Java versions
# ══════════════════════════════════════════════════════════════════════════════

DEFAULT_CP="/project/target/test-classes:/project/target/test-lib/*"

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

    [[ " $SKIP " == *" $JAVA_IMAGE_TAG "* ]] && continue

    start_server "$DEFAULT_CP" "$PROVIDER"

    for PROTO in "TLSv1.3" "TLSv1.2"; do

      case "$PROTO-$JAVA_IMAGE_TAG-$PROVIDER" in
        # JDK versions without native TLS 1.3 support (any provider)
        "TLSv1.3-6-"*|"TLSv1.3-7-"*|"TLSv1.3-9-"*|"TLSv1.3-10-"*) continue;;
      esac

      echo -e "\n" \
        "=============================================\n" \
        "   Java $JAVA_IMAGE_TAG - $PROVIDER $PROTO\n" \
        "=============================================\n\n"

      run_proto_test "$PROTO" "$DEFAULT_CP" "$PROVIDER" "$SECRET_MARKER"

    done

  done

done

docker rm -f ssl-secrets-server

# ══════════════════════════════════════════════════════════════════════════════
#  BC compat matrix: BCJSSE across key BC versions on Java $BC_COMPAT_JAVA
# ══════════════════════════════════════════════════════════════════════════════

docker build -f $CWD/Dockerfile.server $CWD -t ssl-secrets-server \
  --build-arg JAVA_IMAGE_TAG=$BC_COMPAT_JAVA

for BC_VERSION in $BC_COMPAT_VERSIONS; do

  CP="/project/target/test-classes:/project/target/test-lib/bc-$BC_VERSION/*"

  echo -e "\n" \
    "=============================================\n" \
    "   Java $BC_COMPAT_JAVA - BCJSSE BC $BC_VERSION\n" \
    "=============================================\n\n"

  start_server "$CP" "BCJSSE"

  for PROTO in "TLSv1.3" "TLSv1.2"; do

    # BC < 1.70: TLSv1.3 not reliably exposed via the JSSE API (setEnabledProtocols
    # and jdk.tls.client.protocols both ignore "TLSv1.3" in BCJSSE < 1.70).
    # BC 1.70 was the major BCJSSE refactoring that properly wired TLS 1.3 into
    # the standard protocol-name APIs.
    if [[ "$PROTO" == "TLSv1.3" ]]; then
      version_lt "$BC_VERSION" "1.70" && continue
    fi

    echo -e "\n" \
      "=============================================\n" \
      "   Java $BC_COMPAT_JAVA - BCJSSE BC $BC_VERSION $PROTO\n" \
      "=============================================\n\n"

    run_proto_test "$PROTO" "$CP" "BCJSSE" "$BCJSSE_MARKER"

  done

done

# ══════════════════════════════════════════════════════════════════════════════
#  IBM SDK for Java 8 (ibmjava:8): IBM J9 JVM + IBMJSSE2 provider
#  This is the JVM used by HCL Notes 12 and similar IBM products.
#  TLSv1.3 is not supported by IBM Java 8.
# ══════════════════════════════════════════════════════════════════════════════

run_ibm_jdk8_tests() {
  echo -e "\n" \
    "=============================================\n" \
    "   IBM SDK for Java 8 (IBMJSSE2) \n" \
    "=============================================\n\n"
  docker rm -f $(docker ps -qa) 2>/dev/null || true
  docker build -f $CWD/Dockerfile.ibmjdk8 $CWD -t ssl-secrets-server

  local marker="${IBMJSSE2_MARKER}"
  start_server "$DEFAULT_CP" "IBMJSSE2"
  run_proto_test "TLSv1.2" "$DEFAULT_CP" "IBMJSSE2" "$marker"
}

run_ibm_jdk8_tests

docker rm -f ssl-secrets-server
