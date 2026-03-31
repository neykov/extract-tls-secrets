package name.neykov.secrets.agent;

import java.io.ByteArrayInputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javassist.CannotCompileException;
import javassist.ClassClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;

public class Transformer implements ClassFileTransformer {
    private static final Logger log = Logger.getLogger(Transformer.class.getName());
    private static final AtomicBoolean bcjsseLogged = new AtomicBoolean(false);
    private static final AtomicBoolean ibmjsse2Logged = new AtomicBoolean(false);

    private static void logBcjsseDetected() {
        if (bcjsseLogged.compareAndSet(false, true)) {
            String version = getBcVersion();
            log.info(
                    "BouncyCastle JSSE (BCJSSE) detected and instrumented"
                            + (version != null ? ", version " + version : "")
                            + ".");
        }
    }

    private static void logIbmJsse2Detected() {
        if (ibmjsse2Logged.compareAndSet(false, true)) {
            log.info("IBM JSSE2 detected and instrumented.");
        }
    }

    private static String getBcVersion() {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = ClassLoader.getSystemClassLoader();
            }
            Class<?> cls =
                    Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider", false, cl);
            Package pkg = cls.getPackage();
            return pkg != null ? pkg.getImplementationVersion() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private abstract static class InjectCallback {
        private final Set<String> handledClasses;

        public InjectCallback(String... handledClasses) {
            this.handledClasses = new HashSet<String>(Arrays.asList(handledClasses));
        }

        public boolean handles(String className) {
            return handledClasses.contains(className);
        }

        public byte[] transform(String className, byte[] classfileBuffer) {
            if (handles(className)) {
                try {
                    ClassPool pool = new ClassPool();
                    pool.appendSystemPath();
                    // Needed for Java 9+
                    pool.insertClassPath(new ClassClassPath(Transformer.class));
                    CtClass instrumentedClass =
                            pool.makeClass(new ByteArrayInputStream(classfileBuffer));
                    instrumentClass(instrumentedClass);
                    return instrumentedClass.toBytecode();
                } catch (Throwable e) {
                    log.log(Level.WARNING, "Failed instrumenting " + className, e);
                }
            }
            return classfileBuffer;
        }

        protected abstract void instrumentClass(CtClass instrumentedClass)
                throws CannotCompileException, NotFoundException;
    }

    private static class SessionInjectCallback extends InjectCallback {
        public SessionInjectCallback() {
            super("sun.security.ssl.SSLSessionImpl", "com.sun.net.ssl.internal.ssl.SSLSessionImpl");
        }

        @Override
        protected void instrumentClass(CtClass instrumentedClass)
                throws CannotCompileException, NotFoundException {
            String cb = MasterSecretCallback.class.getName();
            CtMethod method = instrumentedClass.getDeclaredMethod("setMasterSecret");
            method.insertAfter(cb + ".onMasterSecret(this, $1);");
        }
    }

    private static class HandshakerInjectCallback extends InjectCallback {

        public HandshakerInjectCallback() {
            super("sun.security.ssl.Handshaker", "com.sun.net.ssl.internal.ssl.Handshaker");
        }

        @Override
        protected void instrumentClass(CtClass instrumentedClass)
                throws CannotCompileException, NotFoundException {
            String cb = MasterSecretCallback.class.getName();
            CtMethod method = instrumentedClass.getDeclaredMethod("calculateConnectionKeys");
            method.insertBefore(cb + ".onCalculateKeys(session, clnt_random, $1);");
        }
    }

    private static class SSLTrafficKeyDerivation extends InjectCallback {

        public SSLTrafficKeyDerivation() {
            super("sun.security.ssl.SSLTrafficKeyDerivation");
        }

        @Override
        protected void instrumentClass(CtClass instrumentedClass)
                throws CannotCompileException, NotFoundException {
            String cb = MasterSecretCallback.class.getName();
            CtMethod method = instrumentedClass.getDeclaredMethod("createKeyDerivation");
            method.insertAfter(cb + ".onKeyDerivation($1, $2);");
        }
    }

    private static class BcTlsProtocolInjectCallback extends InjectCallback {
        public BcTlsProtocolInjectCallback() {
            super("org.bouncycastle.tls.TlsProtocol");
        }

        @Override
        protected void instrumentClass(CtClass instrumentedClass)
                throws CannotCompileException, NotFoundException {
            String cb = MasterSecretCallback.class.getName();
            CtMethod method = instrumentedClass.getDeclaredMethod("establishMasterSecret");
            method.insertAfter(cb + ".onBcMasterSecret($1);");
            logBcjsseDetected();
        }
    }

    private static class BcTlsUtilsInjectCallback extends InjectCallback {
        public BcTlsUtilsInjectCallback() {
            super("org.bouncycastle.tls.TlsUtils");
        }

        @Override
        protected void instrumentClass(CtClass instrumentedClass)
                throws CannotCompileException, NotFoundException {
            try {
                String cb = MasterSecretCallback.class.getName();
                CtMethod handshakePhase =
                        instrumentedClass.getDeclaredMethod("establish13PhaseHandshake");
                handshakePhase.insertAfter(cb + ".onBcTls13HandshakeSecrets($1);");

                CtMethod appPhase =
                        instrumentedClass.getDeclaredMethod("establish13PhaseApplication");
                appPhase.insertAfter(cb + ".onBcTls13ApplicationSecrets($1);");
                logBcjsseDetected();
            } catch (NotFoundException e) {
                log.info("BCJSSE TLS 1.3 support not detected; TLS 1.0-1.2 only.");
            }
        }
    }

    // IBM Java 8's JSSE2 provider classes are obfuscated; the stable hook is the
    // TlsKeyMaterialGenerator in the crypto layer, which holds both the master
    // secret and the client random in its TlsKeyMaterialParameterSpec field.
    // Multiple provider implementations exist (IBMJCEPlus, IBMJCE, FIPS, PKCS11);
    // the callback resolves the spec field via reflection so a single injected
    // expression covers all of them.
    private static class IbmKeyMaterialInjectCallback extends InjectCallback {
        public IbmKeyMaterialInjectCallback() {
            super(
                    "com.ibm.crypto.plus.provider.TlsKeyMaterialGenerator",
                    "com.ibm.crypto.provider.TlsKeyMaterialGenerator",
                    "com.ibm.crypto.fips.provider.TlsKeyMaterialGenerator",
                    "com.ibm.crypto.pkcs11impl.provider.PKCS11TlsKeyMaterialGenerator");
        }

        @Override
        protected void instrumentClass(CtClass instrumentedClass)
                throws CannotCompileException, NotFoundException {
            String cb = MasterSecretCallback.class.getName();
            CtMethod method = instrumentedClass.getDeclaredMethod("engineGenerateKey");
            method.insertBefore(cb + ".onIbmKeyMaterial((java.lang.Object)this);");
            logIbmJsse2Detected();
        }
    }

    private static final InjectCallback[] TRANSFORMERS =
            new InjectCallback[] {
                new SessionInjectCallback(),
                new HandshakerInjectCallback(),
                new SSLTrafficKeyDerivation(),
                new BcTlsProtocolInjectCallback(),
                new BcTlsUtilsInjectCallback(),
                new IbmKeyMaterialInjectCallback()
            };

    @Override
    public byte[] transform(
            ClassLoader loader,
            String classPath,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        String className = classPath.replace("/", ".");
        // loader should be null (bootloader), so don't use it
        for (InjectCallback ic : TRANSFORMERS) {
            if (ic.handles(className)) {
                return ic.transform(className, classfileBuffer);
            }
        }
        return classfileBuffer;
    }

    public static boolean needsTransform(String className) {
        for (InjectCallback ic : TRANSFORMERS) {
            if (ic.handles(className)) {
                return true;
            }
        }
        return false;
    }
}
