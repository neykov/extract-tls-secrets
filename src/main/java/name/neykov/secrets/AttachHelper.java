package name.neykov.secrets;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

//Alternative when tools.jar not available
//https://github.com/apangin/jattach
//
//Byte Buddy (https://github.com/raphw/byte-buddy) abstracts
//the API, including a fallback implementing the attach api.
public class AttachHelper {
    public static void handle(String jarPath, String pid, String logFile) throws FailureMessageException {
        if (isWindows()) {
            loadAttachLibrary();
        }

        if (pid.equals("list")) {
            System.out.print(AttachHelper.list());
        } else {
            try {
                AttachHelper.attach(pid, jarPath, logFile);
                System.out.println("Successfully attached to process ID " + pid + ".");
            } catch (IllegalStateException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "Failed attaching to java process " + pid;
                throw new FailureMessageException(msg);
            }
        }

    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    private static void loadAttachLibrary() throws FailureMessageException {
        try {
            System.loadLibrary("attach");
            // All good - system is set up properly. Nothing to do.
        } catch (UnsatisfiedLinkError e) {
            // "attach.dll" not on the default search path, let's try some well known locations.
            // Could happen if using the JRE with JAVA_HOME pointing to a JDK install.
            if (!tryLoadLibrary("jre/bin/attach.dll")) {
                throw new FailureMessageException(
                        "Failed loading attach provider. Make sure you are running with a JDK java executable. " +
                            "Alternatively locate 'attach.dll' on your system, typically found in " +
                            "'<jdk home>/jre/bin' folder for Oracle JDK installs, and pass the path at startup as: ",
                        "    java -Djava.library.path=\"<jdk home>/jre/bin\" -jar extract-tls-secrets.jar"
                );
            }
        }
    }

    private static File getJavaHome() throws FailureMessageException {
        // Duplicated from AttachHelper, but can't be shared due to ClassLoader boundary
        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null) {
            return new File(javaHomeEnv);
        }

        throw new FailureMessageException("No JAVA_HOME environment variable found. Must point to a local JDK installation.");
    }

    private static boolean tryLoadLibrary(String attachPath) throws FailureMessageException {
        File javaHome = getJavaHome();
        File attachAbsolutePath = new File(javaHome, attachPath);
        if (attachAbsolutePath.exists()) {
            // Check the file path is a valid library
            try {
                System.load(attachAbsolutePath.getAbsolutePath());
            } catch (UnsatisfiedLinkError ex) {
                return false;
            }

            // Extend the path. On good installs the path is supposed to come from "sun.boot.library.path".
            String initialPath = System.getProperty("java.library.path");
            String extendedPath;
            if (initialPath != null && initialPath.length() > 0) {
                extendedPath = initialPath + File.pathSeparator + attachAbsolutePath.getParent();
            } else {
                extendedPath = attachAbsolutePath.getParent();
            }
            System.setProperty("java.library.path", extendedPath);

            // Force reload of the java.library.path property
            Field fieldSysPath = null;
            try {
                fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
            } catch (NoSuchFieldException ex) {
                return false;
            }
            fieldSysPath.setAccessible(true);
            try {
                fieldSysPath.set(null, null);
            } catch (IllegalAccessException ex) {
                return false;
            }

            // Check patching was successful
            try {
                System.loadLibrary("attach");
                System.out.println("Loaded attach " + attachAbsolutePath.getAbsolutePath());
                return true;
            } catch (UnsatisfiedLinkError ex) {
            }
        }
        return false;
    }

    private static void attach(String pid, String jarPath, String options) {
        try {
            VirtualMachine vm = VirtualMachine.attach(pid);
            vm.loadAgent(jarPath, options);
            vm.detach();
        } catch (AgentLoadException e) {
            throw error(pid, e);
        } catch (AgentInitializationException e) {
            throw error(pid, e);
        } catch (IOException e) {
            throw error(pid, e);
        } catch (AttachNotSupportedException e) {
            throw error(pid, e);
        }
    }

    private static String list() {
        StringBuilder msg = new StringBuilder();
        for (VirtualMachineDescriptor vm : VirtualMachine.list()) {
            msg.append("  ").append(vm.id()).append(" ").append(vm.displayName()).append("\n");
        }
        return msg.toString();
    }

    private static IllegalStateException error(String pid, Exception e) {
        StringBuilder msg = new StringBuilder("Failed to attach to java process ").append(pid).append(".");
        if (!pidExists(pid)) {
            msg.append("\n\nNo Java process with ID ").append(pid).append(" found. Running Java processes:\n");
            msg.append(list());
        } else {
            msg.append(" Cause: " + e.getMessage());
        }
        return new IllegalStateException(msg.toString(), e);
    }

    private static boolean pidExists(String pid) {
        for (VirtualMachineDescriptor vm : VirtualMachine.list()) {
            if (vm.id().equals(pid)) {
                return true;
            }
        }
        return false;
    }
}
