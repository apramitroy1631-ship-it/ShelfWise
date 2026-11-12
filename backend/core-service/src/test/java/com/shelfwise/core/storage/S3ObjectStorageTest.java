package com.shelfwise.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** The real S3 code path against the same SeaweedFS image docker-compose runs: bucket created on first use, put, get, delete. */
class S3ObjectStorageTest {

    private static GenericContainer<?> seaweed;

    @BeforeAll
    static void start() {
        seaweed = new GenericContainer<>("chrislusf/seaweedfs:latest")
                .withCommand("server", "-dir=/data", "-s3")
                .withExposedPorts(8333)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(90)));
        seaweed.start();
    }

    @AfterAll
    static void stop() {
        seaweed.stop();
    }

    @Test
    void storesReadsAndDeletesFiles_creatingTheBucketOnFirstUse() throws Exception {
        var storage = new S3ObjectStorage(new StorageProperties(
                "http://" + seaweed.getHost() + ":" + seaweed.getMappedPort(8333), "us-east-1", "test-invoices",
                "test-access", "test-secret", true));
        byte[] content = new byte[3 * 1024 * 1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31);
        }

        // the S3 server may need a moment after its port opens
        Exception last = null;
        for (int attempt = 0; attempt < 15; attempt++) {
            try {
                storage.put("invoices/PO-1/a", content, "application/pdf").block(Duration.ofSeconds(20));
                last = null;
                break;
            } catch (Exception e) {
                last = e;
                Thread.sleep(2000);
            }
        }
        if (last != null) {
            throw last;
        }

        assertThat(storage.get("invoices/PO-1/a").block(Duration.ofSeconds(20))).isEqualTo(content);
        storage.delete("invoices/PO-1/a").block(Duration.ofSeconds(20));
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> storage.get("invoices/PO-1/a").block(Duration.ofSeconds(20)));
    }
}
