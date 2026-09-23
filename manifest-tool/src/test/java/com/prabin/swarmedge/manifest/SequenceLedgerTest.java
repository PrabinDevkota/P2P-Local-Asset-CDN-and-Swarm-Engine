package com.prabin.swarmedge.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SequenceLedgerTest {

    @TempDir
    Path tempDir;

    @Test
    void aRestartKeepsTheHigherSequence() throws Exception {
        Path file = tempDir.resolve("seen.txt");
        SequenceLedger first = SequenceLedger.open(file);
        first.raise("game-x", 17);
        first.raise("game-y", 3);

        SequenceLedger restarted = SequenceLedger.open(file);

        assertThat(restarted.get("game-x")).hasValue(17);
        assertThat(restarted.get("game-y")).hasValue(3);
    }

    @Test
    void aLowerSequenceDoesNotReplaceTheStoredOne() throws Exception {
        Path file = tempDir.resolve("seen.txt");
        SequenceLedger ledger = SequenceLedger.open(file);
        ledger.raise("game-x", 17);

        ledger.raise("game-x", 4);

        assertThat(SequenceLedger.open(file).get("game-x")).hasValue(17);
    }

    @Test
    void aMalformedLineIsRejected() throws Exception {
        Path file = tempDir.resolve("bad.txt");
        java.nio.file.Files.writeString(file, "not-a-sequence\n");

        assertThatThrownBy(() -> SequenceLedger.open(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed");
    }
}
