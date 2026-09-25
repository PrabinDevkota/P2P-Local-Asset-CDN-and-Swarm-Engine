package com.prabin.swarmedge.manifest;

import com.prabin.swarmedge.common.Defaults;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 1 CLI: generate Ed25519 keys, ingest+sign a file, verify a signed manifest.
 */
public final class ManifestCli {

    static final String USAGE = """
            Usage:
              gen-key --out-dir <dir>
              sign --file <path> --product <id> --version <ver> --signing-key-id <id> \\
                   --private-key <pem> --store <dir> --out <manifest.json> \\
                   [--chunk-size <bytes>] [--chunking FIXED|FASTCDC] \\
                   [--min-size <bytes>] [--max-size <bytes>] \\
                   [--sequence <n>] [--expires-days <n>]
              verify --manifest <json> --public-key <pem> [--seen <sequence-ledger>]
            """;

    private ManifestCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0) {
                err.print(USAGE);
                return 2;
            }
            return switch (args[0]) {
                case "gen-key" -> genKey(flags(args, 1), out);
                case "sign" -> sign(flags(args, 1), out);
                case "verify" -> verify(flags(args, 1), out);
                default -> {
                    err.print(USAGE);
                    yield 2;
                }
            };
        } catch (Exception e) {
            err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return 1;
        }
    }

    private static int genKey(Map<String, String> flags, PrintStream out) throws Exception {
        Path dir = Path.of(require(flags, "out-dir"));
        Files.createDirectories(dir);
        KeyPair keys = Ed25519Keys.generate();
        Path privatePem = dir.resolve("private.pem");
        Path publicPem = dir.resolve("public.pem");
        Ed25519Keys.writePrivateKey(privatePem, keys.getPrivate());
        Ed25519Keys.writePublicKey(publicPem, keys.getPublic());
        out.println(privatePem);
        out.println(publicPem);
        return 0;
    }

    private static int sign(Map<String, String> flags, PrintStream out) throws Exception {
        Path file = Path.of(require(flags, "file"));
        long chunkSize = longFlag(flags, "chunk-size", Defaults.CHUNK_SIZE_BYTES);
        long sequence = longFlag(flags, "sequence", 0);
        long expiresDays = longFlag(flags, "expires-days", 30);
        Path storeRoot = Path.of(require(flags, "store"));
        Path outFile = Path.of(require(flags, "out"));
        if (outFile.getParent() != null) {
            Files.createDirectories(outFile.getParent());
        }

        Chunker chunker = chunker(flags, chunkSize);
        ChunkStore store = new ChunkStore(storeRoot);
        List<ChunkEntry> chunks = new AssetIngestor(chunker, store).ingest(file);
        Instant created = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ReleaseManifest unsigned = ReleaseManifestFactory.unsigned(
                require(flags, "product"),
                require(flags, "version"),
                file,
                chunker.spec(),
                chunks,
                created.toString(),
                created.plus(expiresDays, ChronoUnit.DAYS).toString(),
                sequence,
                require(flags, "signing-key-id"));
        ReleaseManifest signed = ManifestSigner.sign(
                unsigned, Ed25519Keys.readPrivateKey(Path.of(require(flags, "private-key"))));
        Files.writeString(outFile, ManifestJson.toJson(signed));
        out.println(CanonicalManifest.assetId(signed).toHex());
        out.println(outFile);
        return 0;
    }

    private static int verify(Map<String, String> flags, PrintStream out) throws Exception {
        ReleaseManifest manifest = ManifestJson.parse(Files.readString(Path.of(require(flags, "manifest"))));
        ManifestVerifier.verify(manifest, Ed25519Keys.readPublicKey(Path.of(require(flags, "public-key"))));
        SequenceLedger ledger = flags.containsKey("seen")
                ? SequenceLedger.open(Path.of(flags.get("seen")))
                : null;
        new ReleaseFreshness(Clock.systemUTC(), ReleaseFreshness.RollbackPolicy.REJECT, ledger).accept(manifest);
        out.println(CanonicalManifest.assetId(manifest).toHex());
        return 0;
    }

    private static Chunker chunker(Map<String, String> flags, long chunkSize) {
        String mode = flags.getOrDefault("chunking", ManifestValidator.MODE_FIXED);
        if (ManifestValidator.MODE_FIXED.equals(mode)) {
            if (flags.containsKey("min-size") || flags.containsKey("max-size")) {
                throw new IllegalArgumentException("FIXED chunking does not take --min-size or --max-size");
            }
            return new FileChunker(chunkSize);
        }
        if (!ManifestValidator.MODE_FASTCDC.equals(mode)) {
            throw new IllegalArgumentException("chunking must be FIXED or FASTCDC");
        }
        long min = longFlag(flags, "min-size", 0);
        long max = longFlag(flags, "max-size", 0);
        if (min > Integer.MAX_VALUE || chunkSize > Integer.MAX_VALUE || max > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("FastCDC sizes must fit in a byte array");
        }
        return new FastCdcChunker((int) min, (int) chunkSize, (int) max);
    }

    private static Map<String, String> flags(String[] args, int from) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = from; i < args.length; i++) {
            String name = args[i];
            if (!name.startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("expected --flag value, got " + name);
            }
            map.put(name.substring(2), args[++i]);
        }
        return map;
    }

    private static String require(Map<String, String> flags, String name) {
        String value = flags.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing --" + name);
        }
        return value;
    }

    private static long longFlag(Map<String, String> flags, String name, long fallback) {
        String value = flags.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Long.parseLong(value);
    }
}
