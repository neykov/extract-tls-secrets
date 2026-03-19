package name.neykov.secrets;

import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;

class BcjsseSetup {
    static void register() {
        BouncyCastleProvider bc = new BouncyCastleProvider();
        Security.insertProviderAt(bc, 1);
        Security.insertProviderAt(new BouncyCastleJsseProvider(bc), 2);
    }
}
