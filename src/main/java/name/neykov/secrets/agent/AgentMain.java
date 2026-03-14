package name.neykov.secrets.agent;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * Entry point of the agent. Loaded in the "App" class loader
 */
public class AgentMain {
    private static final Logger log = Logger.getLogger(AgentMain.class.getName());

    // Created in process working directory
    public static final String DEFAULT_SECRETS_FILE = "ssl-master-secrets.txt";

    // Called from inside the target process when using "-javaagent:" option.
    public static void premain(String agentArgs, Instrumentation inst) {
        File jarFile = getJarFile();
        initClassPath(inst, jarFile);
        attach(agentArgs, inst, jarFile);
    }

    // Called from inside the target process when attaching at runtime.
    public static void agentmain(String agentArgs, Instrumentation inst) {
        File jarFile = getJarFile();
        initClassPath(inst, jarFile);
        attach(agentArgs, inst, jarFile);
        reloadClasses(inst);
    }

    /**
     * The agent is loaded in the App class loader. Instrumented
     * classes are in the boot class loader so can't see "MasterSecretCallback"
     * by default. Adding self to the boot class loader will make
     * MasterSecretCallback visible to core classes. Note that this leads
     * to a split-brain state where some classes of the jar are loaded
     * by the App class loader and some in the boot class loader.
     */
    private static void initClassPath(Instrumentation inst, File jarFile) {
        try {
            // Will cause the logging of:
            //  OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader
            //  classes because bootstrap classpath has been appended
            inst.appendToBootstrapClassLoaderSearch(new JarFile(jarFile));
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed attaching to process. Can't access jar file " + jarFile, e);
            throw new IllegalStateException(e);
        }
    }

  public static URL getJarFileOrClassFolder(Class<?> clz){
    URL path = clz.getProtectionDomain().getCodeSource().getLocation();
    if (null == path)
    {
      //the class is loaded by Java Extension Class Loader, not System Class Loader, so try parse by full path
      String classFullName = clz.getName().replace('.', '/') + ".class";
      URL selfFullResourceUrl = clz.getClassLoader().getResource(classFullName);
      log.log(Level.INFO, "selfFullResourceUrl=" + selfFullResourceUrl);

      if (null != selfFullResourceUrl){
        String strFullUrlPath = selfFullResourceUrl.toString();

        int classNameIndex = strFullUrlPath.indexOf(classFullName);
        if (classNameIndex > 0) {
          //sample: jar:file:/E:/CodeGithub/xxx/xxx.jar!/ , so need remove "jar:" and "!/"
          String strRealUrl = strFullUrlPath.substring(0, classNameIndex);

          int startIndex = 0;
          int endIndex = strRealUrl.length();
          if(strRealUrl.startsWith("jar:")){
            startIndex = 4;
          }
          if(strRealUrl.endsWith("!/")) {
            endIndex = strRealUrl.length() - 2;
          }
          strRealUrl = strRealUrl.substring(startIndex, endIndex);
          log.log(Level.INFO, "selfFullResourceUrl=" + selfFullResourceUrl);
          try {
            path = new URL(strRealUrl);
          } catch (MalformedURLException e) {
            log.log(Level.WARNING, "generate URL failed, strRealUrl = " + strRealUrl, e);
            path = null;
          }
        }
      }
    }
    //URLDecoder.decode(path.getPath(), StandardCharsets.UTF_8);
    return path;
  }

    private static File getJarFile() {
        URL jarUrl = getJarFileOrClassFolder(AgentMain.class);
        try {
            return new File(jarUrl.toURI());
        } catch (URISyntaxException e) {
            log.log(Level.WARNING, "Failed attaching to process. Can't convert jar to a local path " + jarUrl, e);
            throw new IllegalStateException(e);
        }
    }

    // When attaching to a running VM, the classes we are interested
    // in might already have been loaded and used. Need to force a reload
    // so our transformer kicks in.
    private static void reloadClasses(Instrumentation inst) {
        for (Class<?> loadedClass : inst.getAllLoadedClasses()) {
            if (Transformer.needsTransform(loadedClass.getName())) {
                try {
                    inst.retransformClasses(loadedClass);
                } catch (Throwable e) {
                    log.log(Level.WARNING, "Failed instrumenting " + loadedClass.getName() + ". Shared secret extraction might fail.", e);
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private static void attach(String agentArgs, Instrumentation inst, File jarFile) {
        openBaseModule(inst);

        AgentArguments args = AgentArguments.parseArguments(agentArgs);

        // MasterSecretCallback is loaded in boot class loader
        MasterSecretCallback.setIsLogPrivateKey(args.isLogPrivateKey);
        String canonicalSecretsPath = getCanonicalSecretsPath(args.secretsPath);
        MasterSecretCallback.setSecretsFileName(canonicalSecretsPath);

        inst.addTransformer(new Transformer(), true);

        log.info("Successfully attached agent " + jarFile + ". Logging to " + canonicalSecretsPath + ". ");
    }

    private static void openBaseModule(Instrumentation inst) {
        Method getModule;
        try {
            getModule = Class.class.getMethod("getModule");
        } catch (NoSuchMethodException e) {
            // No modules available, no need to open them (< Java 9)
            return;
        } catch (SecurityException e) {
            // No modules available, no need to open them (< Java 9)
            return;
        }

        try {
            Map<String, Set<Object>> extraOpens = new HashMap<String, Set<Object>>();
            extraOpens.put("sun.security.ssl", new HashSet<Object>(Collections.singletonList(getModule.invoke(MasterSecretCallback.class))));

            Method redefineModule = Instrumentation.class.getMethod("redefineModule",
                    getModule.getReturnType(), Set.class, Map.class,
                    Map.class, Set.class, Map.class);

            redefineModule.invoke(inst,
                    getModule.invoke(SSLEngine.class),
                    Collections.EMPTY_SET,
                    Collections.EMPTY_MAP,
                    extraOpens,
                    Collections.EMPTY_SET,
                    Collections.EMPTY_MAP);
        } catch (IllegalAccessException e) {
            log.log(Level.WARNING, "Failed opening modules.", e);
            throw new IllegalStateException(e);
        } catch (IllegalArgumentException e) {
            log.log(Level.WARNING, "Failed opening modules.", e);
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            log.log(Level.WARNING, "Failed opening modules.", e);
            throw new IllegalStateException(e);
        } catch (NoSuchMethodException e) {
            log.log(Level.WARNING, "Failed opening modules.", e);
            throw new IllegalStateException(e);
        } catch (SecurityException e) {
            log.log(Level.WARNING, "Failed opening modules.", e);
            throw new IllegalStateException(e);
        }
    }

    private static String getCanonicalSecretsPath(String secretsPath) {
        String secretsPathOrDefault;
        if (secretsPath != null && !secretsPath.isEmpty()) {
            secretsPathOrDefault = secretsPath;
        } else {
            secretsPathOrDefault = DEFAULT_SECRETS_FILE;
        }

        File secretsFile = new File(secretsPathOrDefault);
        if (!secretsFile.isAbsolute()) {
            secretsFile = new File(System.getProperty("user.dir"), secretsPathOrDefault);
        }

        try {
            return secretsFile.getCanonicalPath();
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed getting the canonical path for " + secretsFile, e);
            throw new IllegalStateException(e);
        }
    }

}
