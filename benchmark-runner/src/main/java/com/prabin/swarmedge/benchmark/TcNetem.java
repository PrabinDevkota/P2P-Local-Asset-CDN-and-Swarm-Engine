package com.prabin.swarmedge.benchmark;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Linux {@code tc} qdisc for a named device (blueprint §3, P10-02).
 *
 * <p>When {@code tc} is not on PATH the session records that it did not apply and
 * {@link #remove()} is still safe to call. A missing tool is not a shaped network.
 */
public final class TcNetem implements ImpairmentSession {

    private final NetworkImpairment impairment;
    private final String device;
    private final Command command;
    private boolean applied;
    private String note = "not applied";

    public TcNetem(NetworkImpairment impairment, String device) {
        this(impairment, device, TcNetem::exec);
    }

    TcNetem(NetworkImpairment impairment, String device, Command command) {
        this.impairment = Objects.requireNonNull(impairment, "impairment");
        this.device = Objects.requireNonNull(device, "device");
        this.command = Objects.requireNonNull(command, "command");
        if (device.isBlank() || device.contains(" ") || device.contains(";")) {
            throw new IllegalArgumentException("device must be a single interface name");
        }
    }

    @Override
    public void apply() {
        if (impairment.isLoopback()) {
            note = "loopback profile; tc not used";
            applied = false;
            return;
        }
        List<String> add = new ArrayList<>();
        add.add("tc");
        add.add("qdisc");
        add.add("replace");
        add.add("dev");
        add.add(device);
        add.add("root");
        add.add("netem");
        add.add("delay");
        add.add(impairment.delayMillis() + "ms");
        add.add(impairment.jitterMillis() + "ms");
        if (impairment.lossPercent() > 0) {
            add.add("loss");
            add.add(impairment.lossPercent() + "%");
        }
        try {
            int code = command.run(add);
            applied = code == 0;
            note = applied ? "tc netem applied" : "tc exited " + code;
        } catch (IOException e) {
            applied = false;
            note = "tc not available: " + e.getMessage();
        }
    }

    @Override
    public void remove() {
        if (!applied) {
            return;
        }
        try {
            command.run(List.of("tc", "qdisc", "del", "dev", device, "root"));
        } catch (IOException ignored) {
            // The tool disappeared between apply and remove. Nothing is left to shape.
        } finally {
            applied = false;
            note = "removed";
        }
    }

    @Override
    public boolean applied() {
        return applied;
    }

    public String note() {
        return note;
    }

    @FunctionalInterface
    interface Command {
        int run(List<String> args) throws IOException;
    }

    private static int exec(List<String> args) throws IOException {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return 124;
            }
            process.getInputStream().readAllBytes();
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted", e);
        }
    }

}
