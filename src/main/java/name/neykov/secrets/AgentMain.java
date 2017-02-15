package name.neykov.secrets;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AgentMain {
    private static final Logger log = Logger.getLogger(Transformer.class.getName());

    // Created in process working directory
    public static final String DEFAULT_SECRETS_FILE = "ssl-master-secrets.txt";

    public static void premain(String agentArgs, Instrumentation inst) {
        main(agentArgs, inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        main(agentArgs, inst);
        reloadClasses(inst);
    }

    // When attaching to a running VM the classes we are interested
    // in might already be loaded and used. Need to force a reload
    // so our transformer kicks in.
    private static void reloadClasses(Instrumentation inst) {
        for (Class<?> loadedClass : inst.getAllLoadedClasses()) {
            if (Transformer.needsTransform(loadedClass.getName())) {
                try {
                    reloadClass(inst, loadedClass);
                } catch (ClassNotFoundException e) {
                    log.log(Level.WARNING, "Failed instrumenting " + loadedClass.getName() + ". Shared secret extraction might fail.", e);
                } catch (UnmodifiableClassException e) {
                    log.log(Level.WARNING, "Failed instrumenting " + loadedClass.getName() + ". Shared secret extraction might fail.", e);
                } catch (IOException e) {
                    log.log(Level.WARNING, "Failed instrumenting " + loadedClass.getName() + ". Shared secret extraction might fail.", e);
                }
            }
        }
    }

    private static void reloadClass(Instrumentation inst, Class<?> loadedClass)
            throws ClassNotFoundException, UnmodifiableClassException, IOException {
        byte[] classBuffer = readFully(loadedClass.getResourceAsStream(loadedClass.getSimpleName() + ".class"));
        inst.redefineClasses(new ClassDefinition(loadedClass, classBuffer));
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int read;
        byte[] buff = new byte[4096];
        while ((read = in.read(buff, 0, buff.length)) != -1) {
            buffer.write(buff, 0, read);
        }
        return buffer.toByteArray();
    }

    private static void main(String agentArgs, Instrumentation inst) {
        String secretsFile;
        if (agentArgs != null && !agentArgs.isEmpty()) {
            secretsFile = agentArgs;
        } else {
            secretsFile = DEFAULT_SECRETS_FILE;
        }
        MasterSecretCallback.setSecretsFileName(secretsFile);
        inst.addTransformer(new Transformer());

        String secretsLocation = new File(System.getProperty("user.dir"), secretsFile).getAbsolutePath();
        log.info("Successfully attached extract-ssl-secrets agent. Logging to " + secretsLocation);
    }
    
}
