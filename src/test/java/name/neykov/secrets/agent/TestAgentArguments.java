package name.neykov.secrets.agent;

@SuppressWarnings("unused")
public class TestAgentArguments {

    @SuppressWarnings("unused")
    public void testParseArguments() {
        check("", "");
        check("secrets.txt", "secrets.txt");
    }

    private static void check(String secretPath, String options) {
        AgentArguments actualArg = AgentArguments.parseArguments(options);
        AgentArguments expectedArg = new AgentArguments(secretPath);
        assert expectedArg.equals(actualArg) : actualArg;
    }
}
