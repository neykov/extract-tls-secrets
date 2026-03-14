package name.neykov.secrets.agent;

@SuppressWarnings("unused")
public class TestAgentArguments {

    @SuppressWarnings("unused")
    public void testParseArguments() {
        check(false, "", "");
        check(true, "", "log-private-key:");
        check(false, "secrets.txt", "secrets.txt");
        check(true, "secrets.txt", "log-private-key:secrets.txt");
    }

    private static void check(boolean isPrivateKey, String secretPath, String options) {
        AgentArguments actualArg = AgentArguments.parseArguments(options);
        AgentArguments expectedArg = new AgentArguments(isPrivateKey, secretPath);
        assert expectedArg.equals(actualArg) : actualArg;
    }
}
