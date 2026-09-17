/*
 * Project Dogwood -- a client, reduced to the decision a key rotation turns on.
 *
 * `rotation-drill.sh` needs two clients at once: one that holds only the OLD Ed25519 public key and
 * one that holds only the NEW one. Every host in this repository compiles its trusted keys in --
 * `slice-desktop`, `slice-android` and the web page all read `DogwoodTrust.DEVELOPMENT_KEYS`, which
 * holds both -- so no sample can be either of those two clients, and a rotation drill run against a
 * client that trusts both keys would pass every step without testing the step that matters.
 *
 * So this is the delivery path's decision, with the key set as an argument:
 *
 *   1. fetch the manifest from a server over Hypertext Transfer Protocol (HTTP), exactly as the
 *      host does;
 *   2. hand the bytes to `app.cash.zipline.loader.ManifestVerifier` -- **the same class
 *      `DogwoodDelivery` constructs**, from the same pinned Zipline version, not a reimplementation
 *      of its rules;
 *   3. fetch each module the manifest names and check its SHA-256 digest, so "the client updated"
 *      means bytes arrived and matched rather than a header was read.
 *
 * What it deliberately does NOT do is run the guest: that needs QuickJS, a Compose host and a
 * window, and none of it participates in the decision under test. `tools/reference-server/check.sh`
 * and `quarantine-drill.sh` run real hosts against the same server for the claims where running
 * the payload IS the claim.
 *
 * Usage:
 *
 *   java -cp <zipline classpath>:. RotationClient \
 *       --url http://127.0.0.1:8473/manifest.zipline.json \
 *       --key dogwood-development=f9037012... [--key ...] [--cohort 7]
 *
 * Prints one `CLIENT ...` line per fact and exits 0 when it accepted the release, 2 when it
 * refused it, 1 when it could not reach the server at all -- three outcomes, because "refused" and
 * "could not fetch" are the two things a rotation drill must never confuse.
 */
import app.cash.zipline.ZiplineManifest;
import app.cash.zipline.loader.ManifestVerifier;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import okio.ByteString;

public final class RotationClient {

  public static void main(String[] args) {
    String url = null;
    Integer cohort = null;
    Map<String, String> keys = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--url": url = args[++i]; break;
        case "--cohort": cohort = Integer.parseInt(args[++i]); break;
        case "--key": {
          String pair = args[++i];
          int eq = pair.indexOf('=');
          keys.put(pair.substring(0, eq), pair.substring(eq + 1));
          break;
        }
        default: throw new IllegalArgumentException("unknown argument " + args[i]);
      }
    }
    if (url == null || keys.isEmpty()) {
      System.err.println("usage: RotationClient --url <manifest> --key name=hex [--key name=hex]");
      System.exit(64);
    }

    // The cohort rides as a query parameter, which is what `DogwoodDelivery` now appends. A static
    // server ignores it; a cohort-aware one routes on it.
    String requested = cohort == null ? url : url + (url.contains("?") ? "&" : "?") + "cohort=" + cohort;

    byte[] manifestBytes;
    String releaseHeader;
    try {
      HttpURLConnection connection = open(requested);
      releaseHeader = connection.getHeaderField("X-Dogwood-Release");
      manifestBytes = read(connection);
    } catch (Exception e) {
      System.out.println("CLIENT unreachable " + requested + " -- " + e);
      System.exit(1);
      return;
    }
    System.out.println("CLIENT fetched " + requested + " release-header=" + releaseHeader
        + " bytes=" + manifestBytes.length);

    ZiplineManifest manifest;
    try {
      manifest = ZiplineManifest.Companion.decodeJson(new String(manifestBytes, StandardCharsets.UTF_8));
    } catch (Exception e) {
      System.out.println("CLIENT refused -- the manifest did not parse: " + e.getMessage());
      System.exit(2);
      return;
    }
    System.out.println("CLIENT manifest version=" + manifest.getVersion()
        + " signatures=" + manifest.getSignatures().keySet()
        + " trusted=" + keys.keySet());

    ManifestVerifier.Builder builder = new ManifestVerifier.Builder();
    for (Map.Entry<String, String> entry : keys.entrySet()) {
      builder.addEd25519(entry.getKey(), ByteString.decodeHex(entry.getValue()));
    }
    String verifiedBy;
    try {
      verifiedBy = builder.build().verify(ByteString.of(manifestBytes), manifest);
    } catch (Exception e) {
      // Zipline's own refusal text, verbatim. A drill that paraphrased it would stop noticing the
      // day the reason changed.
      System.out.println("CLIENT refused -- " + e.getMessage());
      System.exit(2);
      return;
    }
    System.out.println("CLIENT verified version=" + manifest.getVersion() + " key=" + verifiedBy);

    // And the modules, because a verified manifest naming bytes nobody can fetch is not an update.
    for (Map.Entry<String, ZiplineManifest.Module> module : manifest.getModules().entrySet()) {
      String moduleUrl = resolve(requested, module.getValue().getUrl());
      try {
        byte[] body = read(open(moduleUrl));
        String actual = sha256Hex(body);
        String expected = module.getValue().getSha256().hex();
        if (!actual.equals(expected)) {
          System.out.println("CLIENT refused -- " + module.getKey() + " digest " + actual
              + " is not the " + expected + " the signed manifest names");
          System.exit(2);
          return;
        }
        System.out.println("CLIENT module " + module.getKey() + " ok " + body.length + " bytes");
      } catch (Exception e) {
        System.out.println("CLIENT unreachable " + moduleUrl + " -- " + e);
        System.exit(1);
        return;
      }
    }
    System.out.println("CLIENT updated version=" + manifest.getVersion() + " key=" + verifiedBy);
    System.exit(0);
  }

  private static HttpURLConnection open(String url) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    // Identity, because this client is not the place to test content negotiation -- `check.sh`
    // asserts the brotli behaviour against the same server, on the bytes, where it belongs.
    connection.setRequestProperty("Accept-Encoding", "identity");
    connection.setConnectTimeout(10_000);
    connection.setReadTimeout(10_000);
    if (connection.getResponseCode() != 200) {
      throw new IllegalStateException("HTTP " + connection.getResponseCode() + " for " + url);
    }
    return connection;
  }

  private static byte[] read(HttpURLConnection connection) throws Exception {
    try (InputStream in = connection.getInputStream()) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return out.toByteArray();
    }
  }

  /** Module addresses are relative to the manifest, which is how the loader resolves them. */
  private static String resolve(String manifestUrl, String moduleUrl) {
    return URI.create(manifestUrl).resolve(moduleUrl).toString();
  }

  private static String sha256Hex(byte[] body) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) hex.append(String.format("%02x", b));
    return hex.toString();
  }
}
