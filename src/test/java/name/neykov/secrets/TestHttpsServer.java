package name.neykov.secrets;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

public class TestHttpsServer {
    public static void main(String[] args) throws Exception {
        String keystoreFile = System.getProperty("keystore.file");
        char[] password = "password".toCharArray();

        KeyStore ks = KeyStore.getInstance("PKCS12");
        InputStream in = new FileInputStream(keystoreFile);
        try {
            ks.load(in, password);
        } finally {
            in.close();
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);

        SSLServerSocketFactory serverSocketFactory = ctx.getServerSocketFactory();
        SSLServerSocket serverSocket = (SSLServerSocket)serverSocketFactory.createServerSocket(443);
        System.out.println("server ready");
        System.out.flush();

        while (true) {
            SSLSocket socket = (SSLSocket)serverSocket.accept();
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    // consume request headers
                }
                byte[] body = "PLAIN TEXT".getBytes("UTF-8");
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.0 200 OK\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes("UTF-8"));
                out.write(body);
                out.flush();
            } finally {
                socket.close();
            }
        }
    }
}
