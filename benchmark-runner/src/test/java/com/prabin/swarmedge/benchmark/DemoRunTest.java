package com.prabin.swarmedge.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DemoRunTest {

    @TempDir
    Path tempDir;

    @Test
    void coldPassThenWarmPassRecordsPeersAndCacheWithoutAnOffloadClaim() throws Exception {
        Path here = Path.of("").toAbsolutePath();
        Path config = here.resolve("research/configs/demo-cold-warm.yaml");
        if (!Files.isRegularFile(config) && here.getParent() != null) {
            config = here.getParent().resolve("research/configs/demo-cold-warm.yaml");
        }
        DemoRun.Report report = DemoRun.run(tempDir, config);

        assertThat(report.cold().cacheBytes()).isZero();
        assertThat(report.warm().cacheBytes()).isEqualTo(report.cold().assetBytes());
        assertThat(report.cold().peersDialled()).isPositive();
        assertThat(report.cold().assetSha256()).isEqualTo(report.warm().assetSha256());
        assertThat(report.prometheus()).contains(
                "swarmedge_demo_origin_bytes",
                "swarmedge_demo_peer_bytes",
                "swarmedge_demo_completion_millis",
                "swarmedge_demo_active_peers",
                "swarmedge_demo_cache_hits");
        assertThat(report.prometheus()).doesNotContain("offload");
        assertThat(DemoRun.table(report)).doesNotContain("ratio");
    }
}
