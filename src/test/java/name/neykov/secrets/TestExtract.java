package name.neykov.secrets;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;


public class TestExtract {
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            String line = in.readLine();
            URL url = new URL("https://" + line);
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setRequestMethod("GET");
            connection.connect();
            int code = connection.getResponseCode();
            System.out.println(code);
        }
    }

//    Java 9
//    javac module-info.java name\neykov\secrets\TestExtract.java
//    java -cp . --add-modules jdk.incubator.httpclient name.neykov.secrets.Main
//    module extract-ssl-secrets {
//        requires jdk.incubator.httpclient;
//    }
//    public static void main(String[] args) throws Exception {
//        HttpClient client = HttpClient.newHttpClient();
//        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
//        while (true) {
//            String line = in.readLine();
//            HttpRequest req = HttpRequest.newBuilder(new URI("https://" + line)).GET().build();
//            int status = client.send(req, HttpResponse.BodyHandler.asString()).statusCode();
//            System.out.println(status);
//        }
//    }
}
