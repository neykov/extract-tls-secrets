package name.neykov.secrets;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class TestClient {
    public static void main(String[] args) throws Exception {
        String provider = System.getProperty("provider");
        if ("BCJSSE".equals(provider)) {
            BcjsseSetup.register();
        }

        URL url = new URL(args[0]);
        String host = url.getHost();
        int port = url.getPort() == -1 ? 443 : url.getPort();

        SSLSocketFactory factory;
        if ("BCJSSE".equals(provider)) {
            SSLContext ctx = SSLContext.getInstance("TLS", "BCJSSE");
            ctx.init(null, null, null);
            factory = ctx.getSocketFactory();
        } else {
            factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        }

        SSLSocket socket = (SSLSocket) factory.createSocket(host, port);
        socket.setSoTimeout(10000);
        socket.startHandshake();

        String response = new BufferedReader(
                new InputStreamReader(socket.getInputStream())).readLine();
        socket.close();

        if (!"PLAIN TEXT".equals(response)) {
            System.err.println("Unexpected response: " + response);
            System.exit(1);
        }
    }
}
