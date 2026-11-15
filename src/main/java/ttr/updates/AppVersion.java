package ttr.updates;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;

public final class AppVersion {
    private AppVersion() {}
    public static String label() { return "v " + current(); }
    public static String current() {
        try (InputStream input = AppVersion.class.getResourceAsStream("/ttr/build-version.txt")) {
            if (input == null) throw new IOException("Missing build version");
            String value = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)).readLine();
            if (value == null || value.trim().isEmpty()) throw new IOException("Empty build version");
            return value.trim();
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
    static String stable(String tag) throws IOException {
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        if (!version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")) throw new IOException("Unsupported release version: " + tag);
        return version;
    }
    public static boolean newer(String candidate, String installed) throws IOException {
        String[] a = stable(candidate).split("\\."), b = stable(installed).split("\\.");
        for (int i = 0; i < 3; i++) { int c = new BigInteger(a[i]).compareTo(new BigInteger(b[i])); if (c != 0) return c > 0; }
        return false;
    }
}
