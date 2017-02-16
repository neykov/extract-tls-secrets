package name.neykov.secrets;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

// Loaded in the App class loader
public class AgentMain {
    private static final Logger log = Logger.getLogger(AgentMain.class.getName());

    // Created in process working directory
    public static final String DEFAULT_SECRETS_FILE = "ssl-master-secrets.txt";

    // Called from inside the target process when using "-javaagent:" option.
    public static void premain(String agentArgs, Instrumentation inst) {
        File jarFile = getJarFile();
        initClassPath(inst, jarFile);
        main(agentArgs, inst, jarFile.getAbsolutePath());
    }

    // Called from inside the target process when attaching at runtime.
    public static void agentmain(String agentArgs, Instrumentation inst) {
        File jarFile = getJarFile();
        initClassPath(inst, jarFile);
        main(agentArgs, inst, jarFile.getAbsolutePath());
        reloadClasses(inst);
    }

    /**
     * The agent is loaded in the App class loader. Instrumented
     * classes are in the boot class loader so can't see "MasterSecretCallback"
     * by default. Adding self to the boot class loader will make
     * MasterSecretCallback visible to core classes. Not that this leads
     * to a split-brain state where some classes of the jar are loaded
     * by the App class loader and some in the boot class loader.
     * @param jarFile2 
     */
    private static void initClassPath(Instrumentation inst, File jarFile) {
        try {
            inst.appendToBootstrapClassLoaderSearch(new JarFile(jarFile));
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed attaching to process. Can't access jar file" + jarFile, e);
            throw new IllegalStateException(e);
        }
    }

    private static File getJarFile() {
        URL jarUrl = AgentMain.class.getProtectionDomain().getCodeSource().getLocation();
        try {
            return new File(jarUrl.toURI());
        } catch (URISyntaxException e) {
            log.log(Level.WARNING, "Failed attaching to process. Can't convert jar to a local path " + jarUrl, e);
            throw new IllegalStateException(e);
        }
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

    private static void main(String agentArgs, Instrumentation inst, String jarFile) {
        String secretsFile;
        if (agentArgs != null && !agentArgs.isEmpty()) {
            secretsFile = agentArgs;
        } else {
            secretsFile = DEFAULT_SECRETS_FILE;
        }

        // MasterSecretCallback is loaded in boot class loader
        MasterSecretCallback.setSecretsFileName(secretsFile);
        String secretsLocation = new File(System.getProperty("user.dir"), secretsFile).getAbsolutePath();

        inst.addTransformer(new Transformer());

        log.info("Successfully attached agent " + jarFile + ". Logging to " + secretsLocation + ". ");
    }

}
