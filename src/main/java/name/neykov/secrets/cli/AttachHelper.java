package name.neykov.secrets.cli;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;

/**
 * A companion to AgentAttach that needs to be loaded in a different class loader, due to the
 * com.sun.tools dependencies. When tools.jar is not already on the classpath it's added to a new
 * class loader along with AttachHelper and loaded from there.
 *
 * <p>Alternative when tools.jar not available - <a
 * href="https://github.com/apangin/jattach">jattach</a>
 *
 * <p><a href="https://github.com/raphw/byte-buddy">Byte Buddy</a> abstracts the API, including a
 * fallback implementing the attach api.
 */
public class AttachHelper {
    public static void handle(String jarPath, String pid, String attachOptions)
            throws FailureMessageException {
        if (isWindows()) {
            loadAttachLibrary();
        }
        if (pid.equals("list")) {
            System.out.print(AttachHelper.list());
        } else {
            try {
                AttachHelper.attach(pid, jarPath, attachOptions);
                System.out.println("Successfully attached to process ID " + pid + ".");
            } catch (IllegalStateException e) {
                String msg =
                        e.getMessage() != null
                                ? e.getMessage()
                                : "Failed attaching to java process " + pid;
                throw new FailureMessageException(msg);
            }
        }
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
        StringBuilder msg =
                new StringBuilder("Failed to attach to java process ").append(pid).append(".");
        if (!pidExists(pid)) {
            msg.append("\n\nNo Java process with ID ")
                    .append(pid)
                    .append(" found. Running Java processes:\n");
            msg.append(list());
        } else {
            msg.append(" Cause: ").append(e.getMessage()).append(".");
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

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    // The Windows library-loading fix lives here rather than in AgentAttach because native
    // library ownership is classloader-scoped. The JVM keeps a global set of loaded library
    // names; if a library is already owned by one classloader, any other classloader that
    // attempts to load it by name receives UnsatisfiedLinkError("already loaded in another
    // classloader") — unconditionally, with no exception for parent/child relationships.
    // See: NativeLibraries.loadLibrary() in OpenJDK
    //   src/java.base/share/classes/jdk/internal/loader/NativeLibraries.java
    //   (Holder.loadedLibraryNames global set, checked before every load attempt)
    // and the JNI spec: https://docs.oracle.com/en/java/javase/21/docs/specs/jni/design.html
    //   ("the VM internally maintains a list of loaded native libraries for each class loader")
    //
    // AttachHelper is loaded inside URLClassLoader(cp, null). Moving System.load() to
    // AgentAttach (app classloader) would register "attach" there; when
    // WindowsAttachProvider's static initializer calls System.loadLibrary("attach") from
    // within the URLClassLoader it would hit the global name check and fail. Keeping
    // loadAttachLibrary() in AttachHelper ensures both the pre-load and the SPI-triggered
    // load share the same classloader.
    private static void loadAttachLibrary() throws FailureMessageException {
        try {
            System.loadLibrary("attach");
            // All good — system is set up properly.
        } catch (UnsatisfiedLinkError e) {
            // attach.dll not on the default search path; try well-known fallback locations.
            // This can happen when running with a standalone JRE whose bin/ has no attach.dll
            // while JAVA_HOME points to a JDK that does have it under jre/bin/.
            if (!tryLoadLibrary("jre/bin/attach.dll")) {
                throw new FailureMessageException(
                        "Failed loading attach provider."
                                + " Make sure you are running with a JDK java executable."
                                + " Alternatively locate 'attach.dll' on your system,"
                                + " typically found in '<jdk home>/jre/bin' for Oracle JDK,"
                                + " and pass the path at startup as:",
                        "    java -Djava.library.path=\"<jdk home>/jre/bin\""
                                + " -jar extract-tls-secrets.jar");
            }
        }
    }

    // Duplicated from AgentAttach — cannot be shared across the ClassLoader boundary.
    private static File getJavaHome() throws FailureMessageException {
        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null) {
            return new File(javaHomeEnv);
        }
        throw new FailureMessageException(
                "No JAVA_HOME environment variable found."
                        + " Must point to a local JDK installation.");
    }

    private static boolean tryLoadLibrary(String relativePath) throws FailureMessageException {
        File javaHome = getJavaHome();
        File attachAbsolutePath = new File(javaHome, relativePath);
        if (!attachAbsolutePath.exists()) {
            return false;
        }

        // Check that file path is a valid library by preloading it.
        // The load is cached by canonical path.
        try {
            System.load(attachAbsolutePath.getAbsolutePath());
        } catch (UnsatisfiedLinkError e) {
            return false;
        }

        // System.load() caches the library by its full canonical path. When a relative
        // path is used, it will be resolved to an absolute path by appending it to the
        // java.library.path and sun.boot.library.path paths. So even though the library
        // is already loaded, for follow-up code to be able to re-use it, we need to
        // massage its folder in java.library.path. The following System.loadLibrary("attach")
        // will be able to find it by resolving it to the appended path.
        String initialPath = System.getProperty("java.library.path");
        String extendedPath;
        if (initialPath != null && !initialPath.isEmpty()) {
            extendedPath = initialPath + File.pathSeparator + attachAbsolutePath.getParent();
        } else {
            extendedPath = attachAbsolutePath.getParent();
        }
        System.setProperty("java.library.path", extendedPath);

        // Force reload of the java.library.path property
        Field sysPathsField;
        try {
            sysPathsField = ClassLoader.class.getDeclaredField("sys_paths");
        } catch (NoSuchFieldException e) {
            return false;
        }
        sysPathsField.setAccessible(true);
        try {
            sysPathsField.set(null, null);
        } catch (IllegalAccessException e) {
            return false;
        }

        // Check patching was successful
        try {
            System.loadLibrary("attach");
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
        return true;
    }
}
