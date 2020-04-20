package name.neykov.secrets;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

public class AgentAttach {
    // Called on "java -jar" execution. Will attach self to the target process.
    public static void main(String[] args) throws Exception {
        URL jarUrl = AgentAttach.class.getProtectionDomain().getCodeSource().getLocation();
        File jarFile = new File(jarUrl.toURI());
        if (!jarFile.getName().endsWith(".jar")) {
            System.err.println("The agent is not running from a jar file. Attachment will likely fail.");
        }

        if (args.length == 0 || args.length > 2 || (args.length == 2 && args[0].equals("list"))) {
            System.err.println("Missing required argument: pid (the process to attach to)");
            System.out.println();
            System.out.println("Usage: java -jar " + jarFile.getName() + " <pid> [<secrets_file>]");
            System.out.println("       java -jar " + jarFile.getName() + " list");
            System.out.println();
            System.out.println("Options:");
            System.out.println("  * list - shows available Java processes to attach to");
            System.out.println("  * pid - the process ID to attach to (required)");
            System.out.println("  * secrets_file - file path to log the shared secrets to (optional);");
            System.out.println("                   if a relative path is used it's resolved against the target process working folder;");
            System.out.println("                   default value is '" + AgentMain.DEFAULT_SECRETS_FILE + "'");
            System.out.println();
            System.out.println("Note: The location of the secrets file will be logged at INFO level in the target process.");
            System.exit(1);
        }

        String pid = args[0];
        String logFile = null;
        if (args.length == 2) {
            logFile = args[1];
        }

        try {
            attach(jarUrl, jarFile, pid, logFile);
        } catch (MessageException e) {
            for (String line : e.msg) {
                System.err.println(line);
            }
            System.exit(1);
        }
    }

    public static void attach(URL jarUrl, File jarFile, String pid, String logFile) throws Exception {
        if (isAttachApiAvailable()) {
            // Either Java 9 or tools.jar already on classpath
            AttachHelper.handle(jarFile.getAbsolutePath(), pid, logFile);
        } else {
            File toolsFile = getToolsFile();
            URL toolsUrl = toolsFile.toURI().toURL();
            URL[] cp = new URL[] {jarUrl, toolsUrl};
            URLClassLoader classLoader = new URLClassLoader(cp, null);
            Thread.currentThread().setContextClassLoader(classLoader);
            Class<?> helper = classLoader.loadClass("name.neykov.secrets.AttachHelper");

            Method handleMethod = helper.getMethod("handle", String.class, String.class, String.class);
            handleMethod.invoke(null, jarFile.getAbsolutePath(), pid, logFile);
        }

    }

    private static File getToolsFile() throws MessageException {
        File javaHome = getJavaHome();

        // javaHome is a JDK
        File toolsFile = new File(javaHome, "lib/tools.jar");
        if (toolsFile.exists()) {
            return toolsFile;
        }

        // javaHome is the jre subfolder **inside** a JDK home
        File toolsFileAlt = new File(javaHome, "../lib/tools.jar");
        if (toolsFileAlt.exists()) {
            return toolsFileAlt;
        }

        // Apple packaged Java
        File classesFile = new File(javaHome, "../Classes/classes.jar");
        if (classesFile.exists()) {
            return classesFile;
        }

        // Someone decided to copy the tools.jar from a JDK inside working dir
        File localToolsFile = new File("tools.jar");
        if (localToolsFile.exists()) {
            return localToolsFile;
        }

        // Java version format:
        // * Java 8 and lower: 1.X.0 (e.x. 1.6.0, 1.7.0, 1.8.0)
        // * Java 9 and higher: X.0.0 (e.x. 9.0.0, 11.0.0)
        if (System.getProperty("java.version").startsWith("1.")) {
            // JAVA_HOME required
            throw new MessageException(
                "Invalid JAVA_HOME environment variable '" + javaHome.getAbsolutePath() + "'.",
                "Must point to a local JDK installation containing a 'lib/tools.jar' file."
            );
        } else {
            // No need for JAVA_HOME. Not executed from a JDK java executable.
            throw new MessageException(
                "No access to JDK classes. Make sure to use the java executable from a JDK install."
            );
        }
    }

    private static File getJavaHome() throws MessageException {
        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null) {
            return new File(javaHomeEnv);
        }

        throw new MessageException("No JAVA_HOME environment variable found. Must point to a local JDK installation.");
    }

    private static boolean isAttachApiAvailable() {
        try {
            AgentAttach.class.getClassLoader().loadClass("com.sun.tools.attach.VirtualMachine");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

}
