package name.neykov.secrets.agent;

public class AgentArguments {
    public final String secretsPath;

    AgentArguments(String secretsPath) {
        this.secretsPath = secretsPath;
    }

    public static AgentArguments parseArguments(String agentArgs) {
        return new AgentArguments(agentArgs);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AgentArguments that = (AgentArguments) o;

        return secretsPath.equals(that.secretsPath);
    }

    @Override
    public String toString() {
        return "Arguments{" +
                "secretsPath='" + secretsPath + '\'' +
                '}';
    }

}
