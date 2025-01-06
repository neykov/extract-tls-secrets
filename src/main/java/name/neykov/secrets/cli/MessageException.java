package name.neykov.secrets.cli;

import name.neykov.secrets.Java6Compat;

public class MessageException extends Exception {
    final String[] msg;

    protected MessageException(String... msg) {
        super(Java6Compat.join("\n", msg));
        this.msg = msg;
    }
}
