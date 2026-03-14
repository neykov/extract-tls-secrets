package name.neykov.secrets.agent;

public class AgentArguments {
    private static final String LOG_PRIVATE_KEY_PREFIX = "log-private-key:";

    public final boolean isLogPrivateKey;
    public final String secretsPath;

    AgentArguments(boolean isLogPrivateKey, String secretsPath) {
        this.isLogPrivateKey = isLogPrivateKey;
        this.secretsPath = secretsPath;
    }

    public static AgentArguments parseArguments(String agentArgs) {
        if (agentArgs.startsWith(LOG_PRIVATE_KEY_PREFIX)) {
            String secretsPath = agentArgs.substring(LOG_PRIVATE_KEY_PREFIX.length());
            return new AgentArguments(true, secretsPath);
        } else {
            return new AgentArguments(false, agentArgs);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AgentArguments that = (AgentArguments) o;

        if (isLogPrivateKey != that.isLogPrivateKey) return false;
        return secretsPath.equals(that.secretsPath);
    }

    @Override
    public String toString() {
        return "Arguments{" +
                "isLogPrivateKey=" + isLogPrivateKey +
                ", secretsPath='" + secretsPath + '\'' +
                '}';
    }

}
