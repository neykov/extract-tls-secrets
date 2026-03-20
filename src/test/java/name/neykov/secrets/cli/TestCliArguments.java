package name.neykov.secrets.cli;

@SuppressWarnings("unused")
public class TestCliArguments {

    @SuppressWarnings("unused")
    public void testParseArguments() {
        fail(new String[] {});
        pass(new String[] {"list"}, "list", "");
        pass(new String[] {"1234"}, "1234", "");
        pass(new String[] {"1234", "secrets.txt"}, "1234", "secrets.txt");
        fail(new String[] {"1234", "secrets.txt", "extra"});
        fail(new String[] {"1234", "--unknown"});
    }

    @SuppressWarnings("unused")
    public void testAttachOptions() {
        assert "secrets.txt".equals(new CliArguments("1234", "secrets.txt").attachOptions);
        assert "".equals(new CliArguments("1234", "").attachOptions);
    }

    private static void pass(String[] args, String pidOrList, String secretPath) {
        CliArguments actualArgs = CliArguments.parse(args);
        CliArguments expectedArg = new CliArguments(pidOrList, secretPath);
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
