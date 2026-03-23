package name.neykov.secrets;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

public class TestServer {
    public static void main(String[] args) throws Exception {
        String provider = System.getProperty("provider");
        if ("BCJSSE".equals(provider)) {
            BcjsseSetup.register();
        }

        String keystoreFile = System.getProperty("keystore.file");
        char[] password = "password".toCharArray();

        KeyStore ks = KeyStore.getInstance("PKCS12");
        InputStream in = new FileInputStream(keystoreFile);
        try {
            ks.load(in, password);
        } finally {
            in.close();
        }

        KeyManagerFactory kmf;
        SSLContext ctx;
        if ("BCJSSE".equals(provider)) {
            kmf = KeyManagerFactory.getInstance("PKIX", provider);
            ctx = SSLContext.getInstance("TLS", provider);
        } else {
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            ctx = SSLContext.getInstance("TLS");
        }
        kmf.init(ks, password);
        ctx.init(kmf.getKeyManagers(), null, null);

        SSLServerSocket serverSocket =
                (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(443);
        System.out.println("server ready");
        System.out.flush();

        while (true) {
            SSLSocket socket = (SSLSocket) serverSocket.accept();
            try {
                OutputStream out = socket.getOutputStream();
                out.write("PLAIN TEXT\n".getBytes("UTF-8"));
                out.flush();
            } catch (Exception ignored) {
            } finally {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
