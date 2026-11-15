package ttr.updates;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Anonymous HTTPS requests to the project's public GitHub Releases. */
public final class ReleaseClient {
    static final String REPO = "https://github.com/uranic2/ttwr-sqlite";
    static final String LATEST = "https://api.github.com/repos/uranic2/ttwr-sqlite/releases/latest";
    static final long MAX_ZIP = 128L * 1024 * 1024;
    public static final class Release {
        public final String version, url, sha256;
        public final long size;
        Release(String version, String url, String sha256, long size) { this.version=version; this.url=url; this.sha256=sha256; this.size=size; }
    }
    public Release latest() throws IOException {
        HttpURLConnection connection = connect(new URL(LATEST));
        try {
            int status = connection.getResponseCode();
            if (status == 404) return null;
            if (status == 403 || status == 429) throw new IOException("GitHub временно ограничил запросы. Повторите проверку позже.");
            if (status != 200) throw new IOException("GitHub: HTTP " + status);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) { copy(input, output, 4 * 1024 * 1024); }
            return parseRelease(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }
    static Release parseRelease(String json) throws IOException {
        Map<String,Object> release = Json.object(Json.parse(json));
        if (!Boolean.FALSE.equals(release.get("draft")) || !Boolean.FALSE.equals(release.get("prerelease"))) return null;
        String tag = Json.string(release, "tag_name"), version = AppVersion.stable(tag);
        String name = "ttwr-sqlite-" + version + "-system-java.zip";
        for (Object value : Json.array(release.get("assets"))) {
            Map<String,Object> asset = Json.object(value);
            if (!name.equals(asset.get("name"))) continue;
            String url = Json.string(asset, "browser_download_url");
            if (!url.equals(REPO + "/releases/download/" + tag + "/" + name)) throw new IOException("Unexpected release download URL");
            String digest = Json.string(asset, "digest");
            if (!digest.matches("sha256:[0-9a-fA-F]{64}")) throw new IOException("В релизе отсутствует контрольная сумма SHA-256.");
            Object bytes = asset.get("size");
            long size;
            try { size = ((java.math.BigDecimal)bytes).longValueExact(); } catch (Exception ex) { throw new IOException("Invalid asset size", ex); }
            if (size <= 0 || size > MAX_ZIP) throw new IOException("Release ZIP is too large");
            return new Release(version, url, digest.substring(7).toLowerCase(Locale.ROOT), size);
        }
        throw new IOException("В релизе нет комплекта system-java для автоматического обновления.");
    }
    private static boolean trusted(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(url.getProtocol()) && (url.getPort() == -1 || url.getPort() == 443)
                && url.getUserInfo() == null && (host.equals("github.com") || host.equals("api.github.com")
                || host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com"));
    }
    static HttpURLConnection connect(URL url) throws IOException {return connect(url,null);}
    private static HttpURLConnection connect(URL url,DownloadControl control) throws IOException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!trusted(url)) throw new IOException("Untrusted update URL");
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "TTWR-SQLite/" + AppVersion.current());
            connection.setRequestProperty("Accept", url.getHost().equals("api.github.com") ? "application/vnd.github+json" : "application/octet-stream");
            int code;
            try {if(control!=null){control.connection(connection);control.check();}code = connection.getResponseCode();if(control!=null)control.check();}
            catch (IOException ex) { connection.disconnect();if(control!=null)control.disconnected(connection);throw ex; }
            if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) return connection;
            String next = connection.getHeaderField("Location"); connection.disconnect();if(control!=null)control.disconnected(connection);
            if (next == null) throw new IOException("Empty update redirect");
            url = new URL(url, next);
        }
        throw new IOException("Too many update redirects");
    }
    public interface Progress {
        void received(long bytes,long total);
        default void downloaded(){}
    }
    public void download(Release release,Path zip)throws IOException{
        DownloadControl control=new DownloadControl();try{download(release,zip,null,control);}finally{control.finish();}
    }
    public void download(Release release,Path zip,Progress progress,DownloadControl control)throws IOException{
        control.start();HttpURLConnection connection=null;boolean created=false,success=false;
        try {
            connection=connect(new URL(release.url),control);
            if(connection.getResponseCode()!=200)throw new IOException("Download HTTP "+connection.getResponseCode());
            try(InputStream input=connection.getInputStream();OutputStream output=Files.newOutputStream(zip,StandardOpenOption.CREATE_NEW)){
                created=true;control.input(input);long total=0;byte[] buffer=new byte[65536];
                if(progress!=null)progress.received(0,release.size);
                while(true){control.check();int n=input.read(buffer);control.check();if(n<0)break;
                    total+=n;if(total>release.size)throw new IOException("Update size limit exceeded");output.write(buffer,0,n);
                    if(progress!=null)progress.received(total,release.size);
                }
            }
            if(progress!=null)progress.downloaded();control.check();
            if(Files.size(zip)!=release.size||!sha256(zip).equals(release.sha256))throw new IOException("Контрольная сумма загруженного ZIP не совпадает.");
            control.check();success=true;
        } finally {
            if(connection!=null){connection.disconnect();control.disconnected(connection);}
            // Never remove a pre-existing file if CREATE_NEW failed.
            if(!success&&created)Files.deleteIfExists(zip);
        }
    }
    static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[65536]; long total = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Update cancelled");
            total += count; if (total > limit) throw new IOException("Update size limit exceeded"); output.write(buffer, 0, count);
        }
        return total;
    }
    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) { byte[] bytes = new byte[65536]; int n; while ((n=input.read(bytes))!=-1) {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Update cancelled");digest.update(bytes,0,n);} }
            StringBuilder result = new StringBuilder(); for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 255)); return result.toString();
        } catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
}
