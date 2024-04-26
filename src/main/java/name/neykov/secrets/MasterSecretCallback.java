package name.neykov.secrets;

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
import java.util.Base64;

import java.util.Date;
import java.text.SimpleDateFormat;

//Secrets file format:
//https://github.com/boundary/wireshark/blob/d029f48e4fd74b09848fc309630e5dfdc5d602f2/epan/dissectors/packet-ssl-utils.c#L4164-L4182
public class MasterSecretCallback {
    private static final Logger log = Logger.getLogger(MasterSecretCallback.class.getName());
    private static final String NL = System.getProperty("line.separator");
    private static final String LINE_SEPARATOR = System.getProperty("line.separator");
    private static final Base64.Encoder b64Encoder = Base64.getMimeEncoder(64, LINE_SEPARATOR.getBytes());
    private static SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSXXX");
    private static String secretsFileName;
    public static void setSecretsFileName(String secretsFileName) {
        MasterSecretCallback.secretsFileName = secretsFileName;
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
        String connectionDetails =
                "# " + dateNow + " " + peerHostSection +
                "CipherSuite: " + cipherSuite + ", Protocol: " + protocol;
        return connectionDetails;
    }

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

    public static void onSetLocalPrivateKey(SSLSession sslSession, PrivateKey privateKey) {
        try {
            //String masterKey = bytesToHex(privateKey.getEncoded());
            String masterKey = b64Encoder.encodeToString(privateKey.getEncoded());
            writePrivateKey(masterKey);
        } catch (Exception e) {
            log.log(Level.WARNING, "Error retrieving master secret from " + sslSession, e);
        }
    }
    
    public static void onSetLocalCertificates(SSLSession sslSession, X509Certificate[] certs) {
        try {
            for(int i = 0; i<certs.length; i++) {
                byte[] rawCrtText = certs[i].getEncoded();
                String encodedCertText = b64Encoder.encodeToString(rawCrtText);
                writeCert(encodedCertText);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Error retrieving master secret from " + sslSession, e);
        }
        
    }

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
        secrets.put("TlsClientEarlyTrafficSecret", "CLIENT_EARLY_TRAFFIC_SECRET");
        secrets.put("TlsEarlyExporterMasterSecret", "EARLY_EXPORTER_SECRET");
        secrets.put("TlsClientHandshakeTrafficSecret", "CLIENT_HANDSHAKE_TRAFFIC_SECRET");
        secrets.put("TlsServerHandshakeTrafficSecret", "SERVER_HANDSHAKE_TRAFFIC_SECRET");
        secrets.put("TlsClientAppTrafficSecret", "CLIENT_TRAFFIC_SECRET_0");
        secrets.put("TlsServerAppTrafficSecret", "SERVER_TRAFFIC_SECRET_0");
        secrets.put("TlsExporterMasterSecret", "EXPORTER_SECRET");

        TLS13_SECRET_NAMES = Collections.unmodifiableMap(secrets);
    }

    public static void onKeyDerivation(Object context, SecretKey key) {
        String secretName = TLS13_SECRET_NAMES.get(key.getAlgorithm());
        if (secretName == null) {
            return;
        }
        try {
            SSLSession sslSession = (SSLSession) get(context, "handshakeSession");
            String connectionDetails = getConnectionDetails(sslSession);
            Object clientRandom = get(context, "clientHelloRandom");
            String clientRandomBytes = bytesToHex((byte[]) get(clientRandom, "randomBytes"));
            write(connectionDetails, secretName + " " + clientRandomBytes + " " + bytesToHex(key.getEncoded()));
        } catch (Exception e) {
            log.log(Level.WARNING, "Error retrieving client random secret from " + context, e);
        }
    }

    private static synchronized void write(String... secrets) throws IOException {
        Writer out = new FileWriter(secretsFileName, true);
        for (String secret : secrets) {
            out.write(secret);
            out.write(NL);
        }
        out.close();
    }
    private static synchronized void writePrivateKey(String privateKey) throws IOException {
        Writer out = new FileWriter(secretsFileName+".key", true);
        out.write("-----BEGIN PRIVATE KEY-----\n");
        out.write(privateKey);
        out.write(NL);
        out.write("-----END PRIVATE KEY-----");
        out.write(NL);
        out.close();
    }
    private static synchronized void writeCert(String cert) throws IOException {
        Writer out = new FileWriter(secretsFileName+".crt", true);
        out.write("-----BEGIN CERTIFICATE-----\n");
        out.write(cert);
        out.write(NL);
        out.write("-----END CERTIFICATE-----\n");
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
