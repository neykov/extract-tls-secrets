//package name.neykov.secrets;
//
//import java.net.URI;
//import java.net.http.HttpClient;
//import java.net.http.HttpRequest;
//import java.net.http.HttpResponse;
//
//public class TestHttpClient {
////    Java 9
////    javac module-info.java name\neykov\secrets\TestExtract.java
////    java -cp . --add-modules jdk.incubator.httpclient name.neykov.secrets.Main
////    module extract-ssl-secrets {
////        requires jdk.incubator.httpclient;
////    }
//    public static void main(String[] args) throws Exception {
//        HttpClient client = HttpClient.newHttpClient();
//        HttpRequest req = HttpRequest.newBuilder(new URI(args[0])).GET().build();
//        int status = client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
//        if (status != 200) {
//            System.exit(1);
//        }
//     }
//}
