package name.neykov.secrets.cli;

class CliArguments {
    final String listOrPid;

    final String secretsPath;

    CliArguments(String listOrPid, String secretsPath) {
        this.listOrPid = listOrPid;
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
            return new CliArguments("list", "");
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

            return new CliArguments(pid, secretPath);
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

        if (!listOrPid.equals(that.listOrPid)) {
            return false;
        }
        return secretsPath.equals(that.secretsPath);
    }

    @Override
    public String toString() {
        return "CliArguments{"
                + "listOrPid='"
                + listOrPid
                + '\''
                + ", secretsPath='"
                + secretsPath
                + '\''
                + '}';
    }
}
