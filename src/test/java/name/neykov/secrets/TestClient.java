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
        // BCJSSE 1.80+ ignores jdk.tls.client.protocols; set explicitly.
        // SunJSSE processes the property internally, so skip for JSSE to avoid
        // Java 6 compat issues (Java 6 server defaults to TLSv1.1).
        // Use try-catch: older BC (e.g. 1.66) throws on protocol names it doesn't
        // expose via setEnabledProtocols (e.g. "TLSv1.3"), but still negotiates TLS 1.3
        // by default when both sides support it.
        if ("BCJSSE".equals(provider)) {
            String proto = System.getProperty("jdk.tls.client.protocols");
            if (proto != null && !proto.isEmpty()) {
                try {
                    socket.setEnabledProtocols(proto.split(","));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        socket.startHandshake();

        String response =
                new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine();
        socket.close();

        if (!"PLAIN TEXT".equals(response)) {
            System.err.println("Unexpected response: " + response);
            System.exit(1);
        }
    }
}
