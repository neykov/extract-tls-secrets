package name.neykov.secrets.cli;

class CliArguments {
    final String action;

    final String pid;

    final String secretsPath;

    CliArguments(String action, String pid, String secretsPath) {
        this.action = action;
        this.pid = pid;
        this.secretsPath = secretsPath;
    }

    static CliArguments parse(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("No arguments provided");
        }
        if ("list".equals(args[0])) {
            if (args.length > 1) {
                throw new IllegalArgumentException("'list' action does not take any arguments");
            }
            return new CliArguments("list", null, "");
        } else if ("detach".equals(args[0])) {
            if (args.length != 2) {
                throw new IllegalArgumentException(
                        "'detach' action requires exactly one argument: the process ID");
            }
            return new CliArguments("detach", args[1], "");
        } else if ("attach".equals(args[0])) {
            if (args.length < 2 || args.length > 3) {
                throw new IllegalArgumentException(
                        "'attach' action requires a process ID and an optional secrets file path");
            }
            return new CliArguments("attach", args[1], args.length == 3 ? args[2] : "");
        } else {
            String pid = null;
            String secretPath = null;

            for (String arg : args) {
                if (arg.startsWith("-")) {
                    throw new IllegalArgumentException("Unrecognised named parameter " + arg);
                } else {
                    if (pid == null) {
                        pid = arg;
                    } else if (secretPath == null) {
                        secretPath = arg;
                    } else {
                        throw new IllegalArgumentException(
                                "Too many positional parameters: " + arg);
                    }
                }
            }
            if (pid == null) {
                throw new IllegalArgumentException("The required 'pid' argument is missing");
            }
            if (secretPath == null) {
                secretPath = "";
            }

            return new CliArguments("attach", pid, secretPath);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        CliArguments that = (CliArguments) o;

        if (!action.equals(that.action)) {
            return false;
        }
        if (pid != null ? !pid.equals(that.pid) : that.pid != null) {
            return false;
        }
        return secretsPath.equals(that.secretsPath);
    }

    @Override
    public String toString() {
        return "CliArguments{"
                + "action='"
                + action
                + '\''
                + ", pid='"
                + pid
                + '\''
                + ", secretsPath='"
                + secretsPath
                + '\''
                + '}';
    }
}
