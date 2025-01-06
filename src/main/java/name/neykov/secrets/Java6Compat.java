package name.neykov.secrets;

import java.util.prefs.AbstractPreferences;

/**
 * Functionality that's available since Java 8, but
 * needs to be implemented for Java 6. Once Java 6 support
 * is dropped these can be migrated to native functionality.
 */
public class Java6Compat {

    /**
     * String.join
     */
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

    /**
     * Base64.Encoder.encodeToString
     */
    public static String base64Encode(byte[] data) {
        Preferences encoder = new Preferences();
        encoder.putByteArray("", data);
        return encoder.encodedString;
    }

    private static class Preferences extends AbstractPreferences {
        String encodedString;

        protected Preferences() {
            super(null, "");
        }

        @Override
        public void put(String key, String value) {
            encodedString = value;
        }

        @Override
        protected void putSpi(String key, String value) {}

        @Override
        protected String getSpi(String key) {
            return null;
        }

        @Override
        protected void removeSpi(String key) {}

        @Override
        protected void removeNodeSpi() {}

        @Override
        protected String[] keysSpi() {
            return new String[0];
        }

        @Override
        protected String[] childrenNamesSpi() {
            return new String[0];
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            return null;
        }

        @Override
        protected void syncSpi() {
        }

        @Override
        protected void flushSpi() {
        }
    }
}
