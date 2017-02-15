package name.neykov.secrets;

import java.io.FileWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.security.Key;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLSession;

//Secrets file format:
//https://github.com/boundary/wireshark/blob/d029f48e4fd74b09848fc309630e5dfdc5d602f2/epan/dissectors/packet-ssl-utils.c#L4164-L4182
public class MasterSecretCallback {
	private static final Logger log = Logger.getLogger(MasterSecretCallback.class.getName());
	
	private static String secretsFileName;
	public static void setSecretsFileName(String secretsFileName) {
		MasterSecretCallback.secretsFileName = secretsFileName;
	}
	
	public static void onMasterSecret(SSLSession sslSession, Key masterSecret) {
		try {
			String sessionKey = bytesToHex(sslSession.getId());
			String masterKey = bytesToHex(masterSecret.getEncoded());
			Writer out = new FileWriter(secretsFileName, true);
			out.write("RSA Session-ID:" + sessionKey + " Master-Key:" + masterKey + "\n");
			out.close();
		} catch (Exception e) {
			log.log(Level.WARNING, "Error retrieving master secret from " + sslSession, e);
		}
	}
	
	public static void onCalculateKeys(SSLSession sslSession, Object randomCookie, Key masterSecret) {
		try {
			String clientRandom = bytesToHex((byte[])get(randomCookie, "random_bytes"));
			String masterKey = bytesToHex(masterSecret.getEncoded());
			Writer out = new FileWriter(secretsFileName, true);
			out.write("CLIENT_RANDOM " + clientRandom + " " + masterKey + "\n");
			out.close();
		} catch (Exception e) {
			log.log(Level.WARNING, "Error retrieving master secret from " + sslSession, e);
		}
	}

	final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
	public static String bytesToHex(byte[] bytes) {
	    char[] hexChars = new char[bytes.length * 2];
	    for ( int j = 0; j < bytes.length; j++ ) {
	        int v = bytes[j] & 0xFF;
	        hexChars[j * 2] = hexArray[v >>> 4];
	        hexChars[j * 2 + 1] = hexArray[v & 0x0F];
	    }
	    return new String(hexChars);
	}

	private static Object get(Object newObj, String field) throws IllegalAccessException, NoSuchFieldException {
		Field f = newObj.getClass().getDeclaredField(field);
		f.setAccessible(true);
		return f.get(newObj);
	}
}
