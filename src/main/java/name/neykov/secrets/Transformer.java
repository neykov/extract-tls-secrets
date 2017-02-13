package name.neykov.secrets;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.logging.Level;
import java.util.logging.Logger;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

public class Transformer implements ClassFileTransformer {
	private static final Logger log = Logger.getLogger(Transformer.class.getName());

	public byte[] transform(
			ClassLoader loader,
			String classPath,
			Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain,
			byte[] classfileBuffer) throws IllegalClassFormatException {
		String className = classPath.replace("/", ".");
		if (className.equals("sun.security.ssl.SSLSessionImpl") ||
				className.equals("com.sun.net.ssl.internal.ssl.SSLSessionImpl")) {
			try {
				ClassPool pool = ClassPool.getDefault();
				CtClass sslSessionClass = pool.get(className);
				CtMethod method = sslSessionClass.getDeclaredMethod("setMasterSecret");
				method.insertAfter(MasterSecretCallback.class.getName() + ".onMasterSecret(this, $1);");
				return sslSessionClass.toBytecode();
			} catch (Throwable e) {
				log.log(Level.WARNING, "Error instrumenting " + className, e);
				return classfileBuffer;
			}
		} else if (className.equals("sun.security.ssl.Handshaker") ||
				className.equals("com.sun.net.ssl.internal.ssl.Handshaker")) {
			try {
				ClassPool pool = ClassPool.getDefault();
				CtClass sslSessionClass = pool.get(className);
				CtMethod method = sslSessionClass.getDeclaredMethod("calculateConnectionKeys");
				method.insertBefore(MasterSecretCallback.class.getName() + ".onCalculateKeys(session, clnt_random, $1);");
				return sslSessionClass.toBytecode();
			} catch (Throwable e) {
				log.log(Level.WARNING, "Error instrumenting " + className, e);
				return classfileBuffer;
			}
		} else {
			return classfileBuffer;
		}
	}

}
