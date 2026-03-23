package name.neykov.secrets;

/**
 * Functionality that's available since Java 8, but needs to be implemented for Java 6. Once Java 6
 * support is dropped these can be migrated to native functionality.
 */
public class Java6Compat {

    /** String.join */
    public static String join(String delim, String[] msg) {
        StringBuilder sb = new StringBuilder();
        String delimIter = "";
        for (String line : msg) {
            sb.append(delimIter);
            delimIter = delim;
            sb.append(line);
        }
        return sb.toString();
    }
}
