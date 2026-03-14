package name.neykov.secrets.cli;

@SuppressWarnings("unused")
public class TestCliArguments {

    @SuppressWarnings("unused")
    public void testParseArguments() {
        fail(new String[] {});
        pass(new String[] {"list"}, "list", false, "");
        pass(new String[] {"1234"}, "1234", false, "");
        pass(new String[] {"1234", "--log-private-key"}, "1234", true, "");
        pass(new String[] {"1234", "secrets.txt"}, "1234", false, "secrets.txt");
        pass(new String[] {"1234", "--log-private-key", "secrets.txt"}, "1234", true, "secrets.txt");
        pass(new String[] {"--log-private-key", "1234", "secrets.txt"}, "1234", true, "secrets.txt");
        fail(new String[] {"1234", "secrets.txt", "extra"});
        fail(new String[] {"--log-private-key"});
        fail(new String[] {"1234", "--unknown"});
    }

    @SuppressWarnings("unused")
    public void testAttachOptions() {
        assert "secrets.txt".equals(new CliArguments("1234", false, "secrets.txt").attachOptions);
        assert "log-private-key:secrets.txt".equals(new CliArguments("1234", true, "secrets.txt").attachOptions);
        assert "log-private-key:".equals(new CliArguments("1234", true, "").attachOptions);
    }

    private static void pass(String[] args, String pidOrList, boolean isLogPrivateKey, String secretPath) {
        CliArguments actualArgs = CliArguments.parse(args);
        CliArguments expectedArg = new CliArguments(pidOrList, isLogPrivateKey, secretPath);
        assert expectedArg.equals(actualArgs) : actualArgs;
    }

    private static void fail(String[] args) {
        try {
            CliArguments actualArgs = CliArguments.parse(args);
            assert false : actualArgs;
        } catch (IllegalArgumentException ignored) {
        }
    }
}
