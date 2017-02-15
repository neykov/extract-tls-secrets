package name.neykov.secrets;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.logging.Level;
import java.util.logging.Logger;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;
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

        public byte[] transform(String className, byte[] classfileBuffer, ClassLoader cl) {
            if (handles(className)) {
                try {
                    ClassPool pool = new ClassPool();
                    pool.appendClassPath(new LoaderClassPath(cl));
                    CtClass instrumentedClass = pool.makeClass(new ByteArrayInputStream(classfileBuffer));
                    instrumentClass(instrumentedClass);
                    return instrumentedClass.toBytecode();
                } catch (CannotCompileException e) {
                    log.log(Level.WARNING, "Error instrumenting " + className, e);
                } catch (NotFoundException e) {
                    log.log(Level.WARNING, "Error instrumenting " + className, e);
                } catch (IOException e) {
                    log.log(Level.WARNING, "Error instrumenting " + className, e);
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
    
    private static final InjectCallback[] TRANSFORMERS = new InjectCallback[] {new SessionInjectCallback(), new HandshakerInjectCallback()};
    

    public byte[] transform(
            ClassLoader loader,
            String classPath,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) throws IllegalClassFormatException {
        String className = classPath.replace("/", ".");
        for (InjectCallback ic : TRANSFORMERS) {
            if (ic.handles(className)) {
                return ic.transform(className, classfileBuffer, loader);
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
