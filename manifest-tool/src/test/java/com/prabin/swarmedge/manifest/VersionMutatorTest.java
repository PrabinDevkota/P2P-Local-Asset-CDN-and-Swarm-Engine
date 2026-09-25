package com.prabin.swarmedge.manifest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionMutatorTest {

    @Test
    void insertDeleteAndReplaceAreExact() {
        byte[] source = "abcdefghij".getBytes();

        byte[] inserted = VersionMutator.insert(source, 3, new byte[] {'X', 'Y'});
        assertThat(new String(inserted)).isEqualTo("abcXYdefghij");
        assertThat(VersionMutator.edit("insert", 3, 2)).isEqualTo(new VersionMutator.Edit("insert", 3, 2));

        byte[] deleted = VersionMutator.delete(source, 2, 3);
        assertThat(new String(deleted)).isEqualTo("abfghij");

        byte[] replaced = VersionMutator.replace(source, 0, new byte[] {'Z', 'Z'});
        assertThat(new String(replaced)).isEqualTo("ZZcdefghij");
    }
}
