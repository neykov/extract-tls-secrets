package name.neykov.secrets;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

// Loaded in the App class loader
public class AgentMain {
    private static final Logger log = Logger.getLogger(AgentMain.class.getName());

    // Created in process working directory
    public static final String DEFAULT_SECRETS_FILE = "ssl-master-secrets.txt";

    // Called from inside the target process when using "-javaagent:" option.
    public static void premain(String agentArgs, Instrumentation inst) {
        main(agentArgs, inst);
    }

    // Called from inside the target process when attaching at runtime.
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
                    inst.retransformClasses(loadedClass);
                } catch (Throwable e) {
                    log.log(Level.WARNING, "Failed instrumenting " + loadedClass.getName() + ". Shared secret extraction might fail.", e);
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private static void main(String agentArgs, Instrumentation inst) {
        inst.addTransformer(new Transformer());

        URL jarUrl = AgentAttach.class.getProtectionDomain().getCodeSource().getLocation();
        File jarFile;
        try {
            jarFile = new File(jarUrl.toURI());
        } catch (URISyntaxException e) {
            log.log(Level.WARNING, "Failed attaching to process. Can't convert jar to a local path " + jarUrl, e);
            return;
        }

        String secretsFile;
        if (agentArgs != null && !agentArgs.isEmpty()) {
            secretsFile = agentArgs;
        } else {
            secretsFile = DEFAULT_SECRETS_FILE;
        }

        MasterSecretCallback.setSecretsFileName(secretsFile);
        String secretsLocation = new File(System.getProperty("user.dir"), secretsFile).getAbsolutePath();
        log.info("Successfully attached agent " + jarFile + ". Logging to " + secretsLocation);
    }

}
