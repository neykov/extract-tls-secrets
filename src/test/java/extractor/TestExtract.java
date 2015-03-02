package extractor;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;


public class TestExtract {

	public static void main(String[] args) throws Exception {
		URL url = new URL("https://google.com");
		BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
		StringBuffer str = new StringBuffer();
		char[] buff = new char[4098];
		int read;
		while ((read = reader.read(buff)) != -1) {
			str.append(buff, 0, read);
		}
		System.out.println(str);
	}

}
