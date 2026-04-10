package name.neykov.secrets.cli;

@SuppressWarnings("unused")
public class TestCliArguments {

    @SuppressWarnings("unused")
    public void testParseArguments() {
        fail(new String[] {});
        pass(new String[] {"list"}, "list", null, "");
        pass(new String[] {"1234"}, "attach", "1234", "");
        pass(new String[] {"1234", "secrets.txt"}, "attach", "1234", "secrets.txt");
        fail(new String[] {"1234", "secrets.txt", "extra"});
        fail(new String[] {"1234", "--unknown"});
        pass(new String[] {"attach", "1234"}, "attach", "1234", "");
        pass(new String[] {"attach", "1234", "secrets.txt"}, "attach", "1234", "secrets.txt");
        fail(new String[] {"attach"});
        fail(new String[] {"attach", "1234", "secrets.txt", "extra"});
        pass(new String[] {"detach", "1234"}, "detach", "1234", "");
        fail(new String[] {"detach"});
        fail(new String[] {"detach", "1234", "extra"});
    }

    @SuppressWarnings("unused")
    public void testSecretsPath() {
        assert "secrets.txt".equals(new CliArguments("attach", "1234", "secrets.txt").secretsPath);
        assert "".equals(new CliArguments("attach", "1234", "").secretsPath);
    }

    private static void pass(String[] args, String action, String pid, String secretsPath) {
        CliArguments actualArgs = CliArguments.parse(args);
        CliArguments expectedArg = new CliArguments(action, pid, secretsPath);
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
