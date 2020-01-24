package name.neykov.secrets;

import java.net.HttpURLConnection;
import java.net.URL;

public class TestURLConnection {
    public static void main(String[] args) throws Exception {
        URL url = new URL(args[0]);
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setRequestMethod("GET");
        connection.connect();
        int code = connection.getResponseCode();
        if (code != 200) {
            System.err.println("Unexpected response code " + code);
            System.exit(1);
        }
    }
}
