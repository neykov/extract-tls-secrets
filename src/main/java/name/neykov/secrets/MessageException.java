package name.neykov.secrets;

class MessageException extends Exception {
    String[] msg;

    protected MessageException(String... msg) {
        this.msg = msg;
    }
}
