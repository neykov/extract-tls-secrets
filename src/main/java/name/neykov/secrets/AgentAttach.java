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

        if (isAttachApiAvailable()) {
            // Either Java 9 or tools.jar already on classpath
            if (pid.equals("list")) {
                System.out.print(AttachHelper.list());
            } else {
                try {
                    AttachHelper.attach(pid, jarFile.getAbsolutePath(), logFile);
                    System.out.println("Successfully attached to process ID " + pid + ".");
                } catch (IllegalStateException e) {
                    System.err.println(e.getMessage() != null ? e.getMessage() : "Failed attaching to java process " + pid);
                    System.exit(1);
                }
            }
        } else {
            File toolsFile = getToolsFileOrComplain();
            if (toolsFile == null) {
                System.exit(1);
            }

            URL toolsUrl = toolsFile.toURI().toURL();
            URL[] cp = new URL[] {jarUrl, toolsUrl};
            URLClassLoader classLoader = new URLClassLoader(cp, null);
            Thread.currentThread().setContextClassLoader(classLoader);
            Class<?> helper = classLoader.loadClass("name.neykov.secrets.AttachHelper");

            if (pid.equals("list")) {
                Method loadMethod = helper.getMethod("list");
                System.out.println(loadMethod.invoke(null));
            } else {
                try {
                    Method loadMethod = helper.getMethod("attach", String.class, String.class, String.class);
                    loadMethod.invoke(null, pid, jarFile.getAbsolutePath(), logFile);
                    System.out.println("Successfully attached to process ID " + pid + ".");
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    System.err.println(cause.getMessage() != null ? cause.getMessage() : "Failed attaching to java process " + pid);
                    System.exit(1);
                }
            }
        }

    }

    private static File getToolsFileOrComplain() {
        File javaHome = getJavaHomeOrComplain();
        if (javaHome == null) {
            return null;
        }

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

        System.err.println("Invalid JAVA_HOME/java.home environment variable '" + javaHome.getAbsolutePath() + "'.");
        System.err.println("Must point to a local JDK installation containing a 'lib/tools.jar' file.");
        return null;
    }

    private static File getJavaHomeOrComplain() {
        String winJavaHomeEnv = System.getenv("JAVA_HOME");
        if (winJavaHomeEnv != null) {
            return new File(winJavaHomeEnv);
        }

        String unixJavaHomeEnv = System.getenv("java.home");
        if (unixJavaHomeEnv != null) {
            return new File(unixJavaHomeEnv);
        }

        System.err.println("No JAVA_HOME/java.home environment variable found. Must point to a local JDK installation.");
        return null;
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
