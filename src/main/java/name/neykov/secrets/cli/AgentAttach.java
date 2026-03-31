package name.neykov.secrets.cli;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import name.neykov.secrets.agent.AgentMain;

/** Client application that will load the agent in the target process at runtime. */
public class AgentAttach {
    public static void main(String[] args) throws Exception {
        URL jarUrl = AgentAttach.class.getProtectionDomain().getCodeSource().getLocation();
        File jarFile = new File(jarUrl.toURI());
        if (!jarFile.getName().endsWith(".jar")) {
            System.err.println(
                    "The agent is not running from a jar file." + " Attachment will likely fail.");
        }

        try {
            CliArguments cliArguments = CliArguments.parse(args);
            handle(jarUrl, jarFile, cliArguments.listOrPid, cliArguments.secretsPath);
        } catch (IllegalArgumentException e) {
            help(jarFile, e.getMessage());
            System.exit(1);
        } catch (MessageException e) {
            for (String line : e.msg) {
                System.err.println(line);
            }
            System.exit(1);
        }
    }

    private static void help(File jarFile, String message) {
        System.err.println(message + ".");
        System.out.println();
        System.out.println("Usage: java -jar " + jarFile.getName() + " <pid> [<secrets_file>]");
        System.out.println("       java -jar " + jarFile.getName() + " list");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  * list - shows available Java processes to attach to");
        System.out.println("  * pid - the process ID to attach to (required)");
        System.out.println("  * secrets_file - file path to log the shared secrets to (optional);");
        System.out.println(
                "                   if a relative path is used it's resolved against"
                        + " the target process working folder;");
        System.out.println(
                "                   default value is '" + AgentMain.DEFAULT_SECRETS_FILE + "'");
        System.out.println();
        System.out.println(
                "Note: The absolute path to the secrets file will be logged at INFO level"
                        + " in the target process.");
    }

    private static void handle(URL jarUrl, File jarFile, String listOrPid, String secretsPath)
            throws Exception {
        if (isAttachApiAvailable()) {
            // Either Java 9 or tools.jar already on classpath
            AttachHelper.handle(jarFile.getAbsolutePath(), listOrPid, secretsPath);
        } else {
            File toolsFile = getToolsFile();
            URL toolsUrl = toolsFile.toURI().toURL();
            URL[] cp = new URL[] {jarUrl, toolsUrl};
            URLClassLoader classLoader = new URLClassLoader(cp, null);
            Thread.currentThread().setContextClassLoader(classLoader);
            Class<?> helper = classLoader.loadClass("name.neykov.secrets.cli.AttachHelper");

            Method handleMethod =
                    helper.getMethod("handle", String.class, String.class, String.class);
            try {
                handleMethod.invoke(null, jarFile.getAbsolutePath(), listOrPid, secretsPath);
            } catch (InvocationTargetException e) {
                Throwable targetEx = e.getTargetException();
                if (targetEx.getClass().getName().equals(MessageException.class.getName())) {
                    throw new MessageException(targetEx.getMessage().split("\n"));
                } else {
                    throw e;
                }
            }
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
                    "Must point to a local JDK installation containing a"
                            + " 'lib/tools.jar' file.");
        } else {
            // No need for JAVA_HOME. Not executed from a JDK java executable.
            throw new MessageException(
                    "No access to JDK classes."
                            + " Make sure to use the java executable"
                            + " from a JDK install.");
        }
    }

    private static File getJavaHome() throws MessageException {
        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null) {
            return new File(javaHomeEnv);
        }

        throw new MessageException(
                "No JAVA_HOME environment variable found."
                        + " Must point to a local JDK installation.");
    }

    private static boolean isAttachApiAvailable() {
        try {
            AgentAttach.class.getClassLoader().loadClass("com.sun.tools.attach.VirtualMachine");
            return true;
        } catch (ClassNotFoundException e) {
            // IBM Java 8 ships its own attach API under com.ibm.tools.attach.
            // IBM's tools.jar also provides com.sun.tools.attach as a wrapper,
            // so AttachHelper (which uses com.sun.tools.attach) works once
            // tools.jar is on the classpath.
            try {
                AgentAttach.class.getClassLoader().loadClass("com.ibm.tools.attach.VirtualMachine");
                return true;
            } catch (ClassNotFoundException e2) {
                return false;
            }
        }
    }
}
