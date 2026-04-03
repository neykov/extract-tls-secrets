package name.neykov.secrets.cli;

import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.IOException;

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
}
