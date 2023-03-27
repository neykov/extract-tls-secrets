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
import javax.net.ssl.SSLSession;

//Secrets file format:
//https://github.com/boundary/wireshark/blob/d029f48e4fd74b09848fc309630e5dfdc5d602f2/epan/dissectors/packet-ssl-utils.c#L4164-L4182
public class MasterSecretCallback {
    private static final Logger log = Logger.getLogger(MasterSecretCallback.class.getName());
    private static final String NL = System.getProperty("line.separator");

    private static String secretsFileName;
    public static void setSecretsFileName(String secretsFileName) {
        MasterSecretCallback.secretsFileName = secretsFileName;
    }

    public static void onMasterSecret(SSLSession sslSession, Key masterSecret) {
        try {
            String sessionKey = bytesToHex(sslSession.getId());
            String masterKey = bytesToHex(masterSecret.getEncoded());
            write("RSA Session-ID:" + sessionKey + " Master-Key:" + masterKey);
        } catch (Exception e) {
            log.log(Level.WARNING, "Error retrieving master secret from " + sslSession, e);
        }
    }

    public static void onCalculateKeys(SSLSession sslSession, Object randomCookie, Key masterSecret) {
        try {
            String clientRandom = bytesToHex((byte[])get(randomCookie, "random_bytes"));
            String masterKey = bytesToHex(masterSecret.getEncoded());
            write("CLIENT_RANDOM " + clientRandom + " " + masterKey);
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
            Object clientRandom = get(context, "clientHelloRandom");
            String clientRandomBytes = bytesToHex((byte[]) get(clientRandom, "randomBytes"));
            write(secretName + " " + clientRandomBytes + " " + bytesToHex(key.getEncoded()));
        } catch (Exception e) {
            log.log(Level.WARNING, "Error retrieving client random secret from " + context, e);
        }
    }

    private static synchronized void write(String secret) throws IOException {
        Writer out = new FileWriter(secretsFileName, true);
        out.write(secret);
        out.write(NL);
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
