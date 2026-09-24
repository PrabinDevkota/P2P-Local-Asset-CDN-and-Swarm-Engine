package com.prabin.swarmedge.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Applies a network impairment, runs the scenario, and removes the impairment
 * whether the run succeeded or not (blueprint P10-02, P10-03).
 */
public final class BenchmarkHarness {

    private final Path rawRoot;
    private final ImpairmentSession impairment;

    public BenchmarkHarness(Path rawRoot, ImpairmentSession impairment) {
        this.rawRoot = Objects.requireNonNull(rawRoot, "rawRoot");
        this.impairment = Objects.requireNonNull(impairment, "impairment");
    }

    public Path execute(Path config, String runId, Callable<String> body) throws Exception {
        PaperScenario paper = PaperScenario.load(config);
        Files.createDirectories(rawRoot);
        impairment.apply();
        String summary = "{\"error\":\"not started\"}";
        boolean passed = false;
        String detail = "failed";
        boolean wasApplied = impairment.applied();
        try {
            summary = body.call();
            passed = true;
            detail = "completed";
        } catch (Exception e) {
            summary = "{\"error\":" + json(e.toString()) + "}";
            detail = e.toString();
            throw e;
        } finally {
            impairment.remove();
            RunFolder folder = RunFolder.create(rawRoot, runId);
            folder.write(config, gitCommit(), environment(paper), paper.seed(),
                    List.of("event,outcome", passed ? "run,completed" : "run,failed"),
                    List.of("peerId,blocks"),
                    summary,
                    passed,
                    detail,
                    "impairmentApplied=" + wasApplied);
        }
        return rawRoot.resolve(runId);
    }

    private static String environment(PaperScenario paper) {
        return "{\"os\":" + json(System.getProperty("os.name"))
                + ",\"osVersion\":" + json(System.getProperty("os.version"))
                + ",\"java\":" + json(System.getProperty("java.version"))
                + ",\"processors\":" + Runtime.getRuntime().availableProcessors()
                + ",\"networkProfile\":" + json(paper.networkProfile())
                + ",\"clients\":" + paper.clients()
                + ",\"peers\":" + paper.peers()
                + "}\n";
    }

    private static String gitCommit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.waitFor() == 0 && !text.isBlank()) {
                return text;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // git is absent. The run record says unknown rather than inventing a hash.
        }
        return "unknown";
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\"";
    }

    public static void requireCleanImpairment(ImpairmentSession session) throws IOException {
        if (session.applied()) {
            throw new IOException("impairment still applied after the run");
        }
    }
}
