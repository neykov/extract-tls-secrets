package name.neykov.secrets;

class FailureMessageException extends Exception {
    String[] msg;

    protected FailureMessageException(String... msg) {
        this.msg = msg;
    }
}
