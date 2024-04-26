package name.neykov.secrets;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
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

    private static abstract class InjectCallback {
        private String[] handledClasses;

        public InjectCallback(String[] handledClasses) {
            this.handledClasses = handledClasses;
        }

        public boolean handles(String className) {
            for (String cls : handledClasses) {
                if (className.equals(cls)) {
                    return true;
                }
            }
            return false;
        }

       public byte[] transform(String className, byte[] classfileBuffer, String jarFile) {
            if (handles(className)) {
                try {
                    ClassPool pool = new ClassPool();
                    pool.appendSystemPath();
                    // Needed for Java 9+
                    pool.insertClassPath(new ClassClassPath(Transformer.class));
                    CtClass instrumentedClass = pool.makeClass(new ByteArrayInputStream(classfileBuffer));
                    instrumentClass(instrumentedClass);
                    return instrumentedClass.toBytecode();
                } catch (Throwable e) {
                    log.log(Level.WARNING, "Failed instrumenting " + className, e);
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return classfileBuffer;
        }

        protected abstract void instrumentClass(CtClass instrumentedClass) throws CannotCompileException, NotFoundException;
    }

    private static class SessionInjectCallback extends InjectCallback {
        public SessionInjectCallback() {
            super(new String[] {"sun.security.ssl.SSLSessionImpl", "com.sun.net.ssl.internal.ssl.SSLSessionImpl"});
        }

        @Override
        protected void instrumentClass(CtClass instrumentedClass) throws CannotCompileException, NotFoundException {
            CtMethod method = instrumentedClass.getDeclaredMethod("setMasterSecret");
            method.insertAfter(MasterSecretCallback.class.getName() + ".onMasterSecret(this, $1);");
            
            CtMethod method2 = instrumentedClass.getDeclaredMethod("setLocalPrivateKey");
            method2.insertAfter(MasterSecretCallback.class.getName() + ".onSetLocalPrivateKey(this, $1);");
            
            CtMethod method3 = instrumentedClass.getDeclaredMethod("setLocalCertificates");
            method3.insertAfter(MasterSecretCallback.class.getName() + ".onSetLocalCertificates(this, $1);");

        }
    }

    private static class HandshakerInjectCallback extends InjectCallback {

        public HandshakerInjectCallback() {
            super(new String [] {"sun.security.ssl.Handshaker", "com.sun.net.ssl.internal.ssl.Handshaker"});
        }

        @Override
        protected void instrumentClass(CtClass instrumentedClass) throws CannotCompileException, NotFoundException {
            CtMethod method = instrumentedClass.getDeclaredMethod("calculateConnectionKeys");
            method.insertBefore(MasterSecretCallback.class.getName() + ".onCalculateKeys(session, clnt_random, $1);");
        }

    }

    private static class SSLTrafficKeyDerivation extends InjectCallback {

        public SSLTrafficKeyDerivation() {
            super(new String[] {"sun.security.ssl.SSLTrafficKeyDerivation"});
        }

        @Override
        protected void instrumentClass(CtClass instrumentedClass) throws CannotCompileException, NotFoundException {
            CtMethod method = instrumentedClass.getDeclaredMethod("createKeyDerivation");
            method.insertAfter(MasterSecretCallback.class.getName() + ".onKeyDerivation($1, $2);");
        }

    }

    private static final InjectCallback[] TRANSFORMERS = new InjectCallback[] {
            new SessionInjectCallback(),
            new HandshakerInjectCallback(),
            new SSLTrafficKeyDerivation()
    };
    private File jarFile;


    public Transformer(File jarFile) {
        this.jarFile = jarFile;
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String classPath,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) throws IllegalClassFormatException {
        String className = classPath.replace("/", ".");
        // loader should be null (boot loader), so don't use it
        for (InjectCallback ic : TRANSFORMERS) {
            if (ic.handles(className)) {
                return ic.transform(className, classfileBuffer, jarFile.getAbsolutePath());
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
