package name.neykov.secrets.agent;

import name.neykov.secrets.Java6Compat;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.security.Key;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.SecretKey;
import java.security.cert.X509Certificate;
import java.security.PrivateKey;
import javax.net.ssl.SSLSession;

import java.util.Date;
import java.text.SimpleDateFormat;

//Secrets file format:
//https://github.com/boundary/wireshark/blob/d029f48e4fd74b09848fc309630e5dfdc5d602f2/epan/dissectors/packet-ssl-utils.c#L4164-L4182
//https://gitlab.com/wireshark/wireshark/-/blob/d24b1b08f51ce740aacd26537af131bed374e751/epan/dissectors/packet-tls-utils.c#L6814-6851
//https://bensmyth.com/files/Smyth19-TLS-tutorial.pdf
//https://www.ietf.org/archive/id/draft-thomson-tls-keylogfile-00.html
public class MasterSecretCallback {
    private static final Logger log = Logger.getLogger(MasterSecretCallback.class.getName());
    private static final String NL = System.getProperty("line.separator");
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ");
    private static String secretsFileName;
    private static boolean isLogPrivateKey;
    public static void setSecretsFileName(String secretsFileName) {
        MasterSecretCallback.secretsFileName = secretsFileName;
    }
    public static void setIsLogPrivateKey(boolean isLogPrivateKey) {
        MasterSecretCallback.isLogPrivateKey = isLogPrivateKey;
    }

    @SuppressWarnings("unused")
    public static void onMasterSecret(SSLSession sslSession, Key masterSecret) {
        try {
            String connectionDetails = getConnectionDetails(sslSession);
            String sessionKey = bytesToHex(sslSession.getId());
            String masterKey = bytesToHex(masterSecret.getEncoded());
            write(
                connectionDetails,
                "RSA Session-ID:" + sessionKey + " Master-Key:" + masterKey
            );
        } catch (Exception e) {
            log.log(Level.WARNING, "Error retrieving master secret from " + sslSession, e);
        }
    }

    @SuppressWarnings("unused")
    public static void onCalculateKeys(SSLSession sslSession, Object randomCookie, Key masterSecret) {
        try {
            String connectionDetails = getConnectionDetails(sslSession);
            String clientRandom = bytesToHex((byte[])get(randomCookie, "random_bytes"));
            String masterKey = bytesToHex(masterSecret.getEncoded());
            write(
                connectionDetails,
                "CLIENT_RANDOM " + clientRandom + " " + masterKey);
        } catch (Exception e) {
            log.log(Level.WARNING, "Error retrieving master secret from " + sslSession, e);
        }
    }

    private static Map<String, String> TLS13_SECRET_NAMES;
    static {
        Map<String, String> secrets = new HashMap<String, String>();

        // TLS 1.1
        secrets.put("TlsMasterSecret", "CLIENT_RANDOM");

        // TLS 1.3
        // Early data is not supported in Java
        // https://bugs.openjdk.org/browse/JDK-8209392
        secrets.put("TlsClientEarlyTrafficSecret", "CLIENT_EARLY_TRAFFIC_SECRET");
        secrets.put("TlsEarlyExporterMasterSecret", "EARLY_EXPORTER_SECRET");
        secrets.put("TlsClientHandshakeTrafficSecret", "CLIENT_HANDSHAKE_TRAFFIC_SECRET");
        secrets.put("TlsServerHandshakeTrafficSecret", "SERVER_HANDSHAKE_TRAFFIC_SECRET");
        secrets.put("TlsClientAppTrafficSecret", "CLIENT_TRAFFIC_SECRET_0");
        secrets.put("TlsServerAppTrafficSecret", "SERVER_TRAFFIC_SECRET_0");
        secrets.put("TlsExporterMasterSecret", "EXPORTER_SECRET");

        TLS13_SECRET_NAMES = Collections.unmodifiableMap(secrets);
    }

    @SuppressWarnings("unused")
    public static void onKeyDerivation(Object context, SecretKey key) {
        String secretName = TLS13_SECRET_NAMES.get(key.getAlgorithm());
        if (secretName == null) {
            return;
        }
        try {
            SSLSession sslSession = (SSLSession) get(context, "handshakeSession");
            String connectionDetails = getConnectionDetails(sslSession);
            Object clientRandom = get(context, "clientHelloRandom");
            String clientRandomBytes = bytesToHex((byte[])get(clientRandom, "randomBytes"));
            write(connectionDetails, secretName + " " + clientRandomBytes + " " + bytesToHex(key.getEncoded()));
        } catch (Exception e) {
            log.log(Level.WARNING, "Error retrieving client random secret from " + context, e);
        }
    }

    @SuppressWarnings("unused")
    public static void onSetLocalPrivateKey(SSLSession sslSession, PrivateKey privateKey) {
        if (isLogPrivateKey) {
            try {
                byte[] privateKeyData = privateKey.getEncoded();
                String masterKey =
                        Java6Compat.base64Encode(privateKeyData);
                write(
                    "# LocalPrivateKey: Algorithm - " + privateKey.getAlgorithm() +
                            ", Format: " + privateKey.getFormat(),
                    "-----BEGIN PRIVATE KEY-----",
                    masterKey,
                    "-----END PRIVATE KEY-----"
                );
            } catch (Exception e) {
                log.log(Level.WARNING, "Error retrieving master secret from " + sslSession, e);
            }
        }
    }

    @SuppressWarnings("unused")
    public static void onSetLocalCertificates(SSLSession sslSession, X509Certificate[] certs) {
        if (isLogPrivateKey) {
            try {
                for (X509Certificate cert : certs) {
                    byte[] certData = cert.getEncoded();
                    String encodedCertText =
                            Java6Compat.base64Encode(certData);
                    write(
                        "-----BEGIN CERTIFICATE-----",
                        encodedCertText,
                        "-----END CERTIFICATE-----"
                    );
                }
            } catch (Exception e) {
                log.log(Level.WARNING, "Error retrieving master secret from " + sslSession, e);
            }
        }
    }

    // BC 1.57 is the first release providing a JSSE implementation (BouncyCastleJsseProvider),
    // via the bctls artifact. Earlier BC versions had only a low-level proprietary TLS API
    // (org.bouncycastle.crypto.tls) with no SSLContext/SSLSocket integration.
    //
    // BC < 1.61 uses "securityParameters"; 1.61+ renamed it to "securityParametersHandshake".
    private static Object getBcSecurityParams(Object tlsContext) throws IllegalAccessException, NoSuchFieldException {
        try {
            return get(tlsContext, "securityParametersHandshake");
        } catch (NoSuchFieldException e) {
            return get(tlsContext, "securityParameters");
        }
    }

    @SuppressWarnings("unused")
    public static void onBcMasterSecret(Object tlsContext) {
        try {
            Object secParams = getBcSecurityParams(tlsContext);
            String clientRandom = bytesToHex((byte[])get(secParams, "clientRandom"));
            Object masterSecret = get(secParams, "masterSecret");
            String masterKey = bytesToHex((byte[])get(masterSecret, "data"));
            write(
                getBcConnectionDetails(secParams),
                "CLIENT_RANDOM " + clientRandom + " " + masterKey
            );
        } catch (Exception e) {
            log.log(Level.WARNING, "Error retrieving BCJSSE TLS 1.0-1.2 master secret", e);
        }
    }

    @SuppressWarnings("unused")
    public static void onBcTls13HandshakeSecrets(Object tlsContext) {
        try {
            Object secParams = getBcSecurityParams(tlsContext);
            String clientRandom = bytesToHex((byte[])get(secParams, "clientRandom"));
            Object trafficSecretClient = get(secParams, "trafficSecretClient");
            Object trafficSecretServer = get(secParams, "trafficSecretServer");
            String clientHandshakeTrafficSecret = bytesToHex((byte[])get(trafficSecretClient, "data"));
            String serverHandshakeTrafficSecret = bytesToHex((byte[])get(trafficSecretServer, "data"));
            write(
                getBcConnectionDetails(secParams),
                "CLIENT_HANDSHAKE_TRAFFIC_SECRET " + clientRandom + " " + clientHandshakeTrafficSecret,
                "SERVER_HANDSHAKE_TRAFFIC_SECRET " + clientRandom + " " + serverHandshakeTrafficSecret
            );
        } catch (Exception e) {
            log.log(Level.WARNING, "Error retrieving BCJSSE TLS 1.3 handshake secrets", e);
        }
    }

    @SuppressWarnings("unused")
    public static void onBcTls13ApplicationSecrets(Object tlsContext) {
        try {
            Object secParams = getBcSecurityParams(tlsContext);
            String clientRandom = bytesToHex((byte[])get(secParams, "clientRandom"));
            Object trafficSecretClient = get(secParams, "trafficSecretClient");
            Object trafficSecretServer = get(secParams, "trafficSecretServer");
            Object exporterMasterSecret = get(secParams, "exporterMasterSecret");
            String clientAppTrafficSecret = bytesToHex((byte[])get(trafficSecretClient, "data"));
            String serverAppTrafficSecret = bytesToHex((byte[])get(trafficSecretServer, "data"));
            String exporterSecret = bytesToHex((byte[])get(exporterMasterSecret, "data"));
            write(
                getBcConnectionDetails(secParams),
                "CLIENT_TRAFFIC_SECRET_0 " + clientRandom + " " + clientAppTrafficSecret,
                "SERVER_TRAFFIC_SECRET_0 " + clientRandom + " " + serverAppTrafficSecret,
                "EXPORTER_SECRET " + clientRandom + " " + exporterSecret
            );
        } catch (Exception e) {
            log.log(Level.WARNING, "Error retrieving BCJSSE TLS 1.3 application secrets", e);
        }
    }

    private static String getConnectionDetails(SSLSession sslSession) {
        String dateNow;
        synchronized (DATE_FMT) {
            dateNow = DATE_FMT.format(new Date());
        }

        String peerHost = sslSession.getPeerHost();
        int portHost = sslSession.getPeerPort();
        String protocol = sslSession.getProtocol();
        String cipherSuite = sslSession.getCipherSuite();
        String peerHostSection = "";
        if (peerHost != null) {
            peerHostSection = "Peer: " + peerHost + ":" + portHost + ", ";
        }
        return  "# " + dateNow + " " + peerHostSection +
                        "CipherSuite: " + cipherSuite + ", Protocol: " + protocol;
    }

    private static String getBcConnectionDetails(Object secParams) throws IllegalAccessException, NoSuchFieldException {
        String dateNow;
        synchronized (DATE_FMT) {
            dateNow = DATE_FMT.format(new Date());
        }
        // negotiatedVersion was added in BC 1.61; pre-1.61 supported TLS 1.0-1.2 but
        // did not record the negotiated version, so we cannot determine it after the fact.
        String protocol;
        try {
            Object negotiatedVersion = get(secParams, "negotiatedVersion");
            protocol = negotiatedVersion != null ? negotiatedVersion.toString() : "unknown";
        } catch (NoSuchFieldException e) {
            protocol = "unknown";
        }
        int cipherSuiteCode = ((Integer)get(secParams, "cipherSuite")).intValue();
        String cipherSuite = String.format("0x%04X", cipherSuiteCode);
        return "# " + dateNow + " BCJSSE CipherSuite: " + cipherSuite + ", Protocol: " + protocol;
    }

    private static synchronized void write(String... secrets) throws IOException {
        Writer out = new FileWriter(secretsFileName, true);
        for (String secret : secrets) {
            out.write(secret);
            out.write(NL);
        }
        out.close();
    }

    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    private static Object get(Object newObj, String field) throws IllegalAccessException, NoSuchFieldException {
        Class<?> type = newObj.getClass();
        while (type != null) {
            try {
                Field f = type.getDeclaredField(field);
                f.setAccessible(true);
                return f.get(newObj);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(field);
    }
}
