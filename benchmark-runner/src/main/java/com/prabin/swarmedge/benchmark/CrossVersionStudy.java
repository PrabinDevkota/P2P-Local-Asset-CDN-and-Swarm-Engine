package com.prabin.swarmedge.benchmark;

import com.prabin.swarmedge.manifest.CanonicalManifest;
import com.prabin.swarmedge.manifest.ChunkEntry;
import com.prabin.swarmedge.manifest.Chunker;
import com.prabin.swarmedge.manifest.FastCdcChunker;
import com.prabin.swarmedge.manifest.FileChunker;
import com.prabin.swarmedge.manifest.ReleaseManifest;
import com.prabin.swarmedge.manifest.ReleaseManifestFactory;
import com.prabin.swarmedge.manifest.VersionMutator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * B4 (fixed chunks) against B5 (FastCDC) on one controlled edit (blueprint P11-03).
 *
 * <p>Each repetition records shared hash bytes, canonical manifest size, chunking
 * time, and elapsed time, plus a 95% bootstrap interval. The table does not rank
 * the two baselines and does not state a dedup ratio.
 */
public final class CrossVersionStudy {

    private static final String CREATED = "2026-09-25T00:00:00Z";
    private static final String EXPIRES = "2026-10-25T00:00:00Z";

    private CrossVersionStudy() {
    }

    public record Sample(String baseline, long sharedBytes, int manifestBytes, long chunkNanos, long elapsedNanos) {
    }

    public record Interval(long low, long median, long high) {
    }

    public record Report(
            String baseline,
            List<Sample> samples,
            Interval sharedBytes,
            Interval manifestBytes,
            Interval chunkNanos,
            Interval elapsedNanos) {
    }

    public static List<Report> run(Path work, byte[] base, VersionMutator.Edit edit, byte[] payload,
                                    int fixedSize, int min, int avg, int max, int repetitions) throws IOException {
        if (repetitions < 2) {
            throw new IllegalArgumentException("repetitions must be at least 2 so an interval exists");
        }
        byte[] next = apply(base, edit, payload);
        Path v1 = work.resolve("v1.bin");
        Path v2 = work.resolve("v2.bin");
        Files.write(v1, base);
        Files.write(v2, next);
        Chunker fixed = new FileChunker(fixedSize);
        Chunker cdc = new FastCdcChunker(min, avg, max);
        return List.of(
                measure("B4", fixed, v1, v2, repetitions),
                measure("B5", cdc, v1, v2, repetitions));
    }

    public static String table(List<Report> reports) {
        StringBuilder out = new StringBuilder();
        out.append("# B4 vs B5\n\n");
        out.append("Shared bytes are SHA-256 matches between the two versions. ");
        out.append("Intervals are a 95% bootstrap of the sample median. ");
        out.append("This table does not rank the baselines.\n\n");
        out.append("| baseline | shared bytes | manifest bytes | chunking ns | elapsed ns |\n");
        out.append("| --- | --- | --- | --- | --- |\n");
        for (Report report : reports) {
            out.append("| ").append(report.baseline())
                    .append(" | ").append(cell(report.sharedBytes()))
                    .append(" | ").append(cell(report.manifestBytes()))
                    .append(" | ").append(cell(report.chunkNanos()))
                    .append(" | ").append(cell(report.elapsedNanos()))
                    .append(" |\n");
        }
        return out.toString();
    }

    static byte[] apply(byte[] base, VersionMutator.Edit edit, byte[] payload) {
        return switch (edit.kind()) {
            case "insert" -> VersionMutator.insert(base, edit.at(), payload);
            case "delete" -> VersionMutator.delete(base, edit.at(), edit.length());
            case "replace" -> VersionMutator.replace(base, edit.at(), payload);
            default -> throw new IllegalArgumentException("unknown edit " + edit.kind());
        };
    }

    private static Report measure(String baseline, Chunker chunker, Path v1, Path v2, int repetitions) throws IOException {
        List<Sample> samples = new ArrayList<>();
        long[] shared = new long[repetitions];
        long[] manifest = new long[repetitions];
        long[] chunking = new long[repetitions];
        long[] elapsed = new long[repetitions];
        for (int i = 0; i < repetitions; i++) {
            long started = System.nanoTime();
            long chunkStarted = System.nanoTime();
            List<ChunkEntry> left = chunker.chunk(v1);
            List<ChunkEntry> right = chunker.chunk(v2);
            long chunkNanos = System.nanoTime() - chunkStarted;
            long sharedBytes = sharedBytes(left, right);
            int manifestBytes = manifestBytes(chunker, v1, v2, left, right);
            long elapsedNanos = System.nanoTime() - started;
            samples.add(new Sample(baseline, sharedBytes, manifestBytes, chunkNanos, elapsedNanos));
            shared[i] = sharedBytes;
            manifest[i] = manifestBytes;
            chunking[i] = chunkNanos;
            elapsed[i] = elapsedNanos;
        }
        return new Report(baseline, List.copyOf(samples),
                interval(shared), interval(manifest), interval(chunking), interval(elapsed));
    }

    private static int manifestBytes(Chunker chunker, Path v1, Path v2,
                                      List<ChunkEntry> left, List<ChunkEntry> right) throws IOException {
        ReleaseManifest first = ReleaseManifestFactory.unsigned(
                "game-x", "1.4.0", v1, chunker.spec(), left,
                CREATED, EXPIRES, 1, "release-key");
        ReleaseManifest second = ReleaseManifestFactory.unsigned(
                "game-x", "1.4.1", v2, chunker.spec(), right,
                CREATED, EXPIRES, 2, "release-key");
        return utf8(CanonicalManifest.unsignedJson(first)) + utf8(CanonicalManifest.unsignedJson(second));
    }

    private static int utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8).length;
    }

    static long sharedBytes(List<ChunkEntry> left, List<ChunkEntry> right) {
        Map<String, Long> hashes = new HashMap<>();
        for (ChunkEntry chunk : left) {
            hashes.merge(chunk.sha256(), chunk.length(), Long::sum);
        }
        long shared = 0;
        for (ChunkEntry chunk : right) {
            Long have = hashes.get(chunk.sha256());
            if (have != null && have > 0) {
                long used = Math.min(have, chunk.length());
                shared += used;
                hashes.put(chunk.sha256(), have - used);
            }
        }
        return shared;
    }

    /** 95% percentile bootstrap of the sample median. */
    static Interval interval(long[] samples) {
        long[] ordered = samples.clone();
        Arrays.sort(ordered);
        long median = ordered[ordered.length / 2];
        Random random = new Random(20260925L);
        int draws = 1_000;
        long[] stats = new long[draws];
        for (int draw = 0; draw < draws; draw++) {
            long[] resample = new long[samples.length];
            for (int i = 0; i < samples.length; i++) {
                resample[i] = samples[random.nextInt(samples.length)];
            }
            Arrays.sort(resample);
            stats[draw] = resample[resample.length / 2];
        }
        Arrays.sort(stats);
        int low = (int) Math.floor(0.025 * (draws - 1));
        int high = (int) Math.ceil(0.975 * (draws - 1));
        return new Interval(stats[low], median, stats[high]);
    }

    private static String cell(Interval interval) {
        return interval.median() + " [" + interval.low() + ", " + interval.high() + "]";
    }
}
