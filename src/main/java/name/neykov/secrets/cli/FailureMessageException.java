package name.neykov.secrets.cli;

import name.neykov.secrets.Java6Compat;

public class FailureMessageException extends Exception {
    final String[] msg;

    protected FailureMessageException(String... msg) {
        super(Java6Compat.join("\n", msg));
        this.msg = msg;
    }
}
