package ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark;

import org.springframework.http.ResponseEntity;
import ru.nsu.fit.sberlab.vectorindex.common.dto.ClusterStats;
import ru.nsu.fit.sberlab.vectorindex.common.dto.LoadRequest;
import ru.nsu.fit.sberlab.vectorindex.common.dto.NodeStats;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.VectorService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public final class DatabaseLoader {
    private static final long INDEX_READY_TIMEOUT_MS = 30 * 60_000L;
    private static final int READY_STABLE_POLLS = 3;

    private final VectorService service;

    public DatabaseLoader(VectorService service) {
        if (service == null) throw new IllegalArgumentException("service is required");
        this.service = service;
    }

    public void load(String databasePath) {
        File databaseFile = validateDatabaseFile(databasePath);
        long expectedVectorCount = countDatabaseRows(databaseFile);

        System.out.println("=== Database loading STARTED ===");
        System.out.println("Database: " + databaseFile.getAbsolutePath());
        System.out.println("Expected vectors: " + expectedVectorCount);

        service.clear();

        ResponseEntity<String> response = service.load(
                new LoadRequest(databaseFile.getAbsolutePath())
        );

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Database load failed: " + response);
        }

        System.out.println("Database loaded: " + response.getBody());

        waitUntilIndexReady(expectedVectorCount);

        System.out.println("=== Database loading FINISHED ===");
    }

    private File validateDatabaseFile(String databasePath) {
        if (databasePath == null || databasePath.isBlank()) {
            throw new IllegalArgumentException("Database path is required");
        }

        File file = new File(databasePath);

        if (!file.exists() || !file.isFile() || !file.canRead()) {
            throw new IllegalArgumentException(
                    "Database file cannot be read: " + databasePath
            );
        }

        return file;
    }

    private long countDatabaseRows(File file) {
        long count = 0L;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            reader.readLine();

            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) count++;
            }
        } catch (IOException e) {
            throw new RuntimeException(
                    "Database row count error: " + file.getAbsolutePath(),
                    e
            );
        }

        if (count == 0L) {
            throw new IllegalStateException("Database file contains no vectors");
        }

        return count;
    }

    private void waitUntilIndexReady(long expectedVectorCount) {
        long deadline = System.currentTimeMillis() + INDEX_READY_TIMEOUT_MS;
        int stablePolls = 0;

        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<ClusterStats> response = service.stats();
            ClusterStats stats = response.getBody();

            if (!response.getStatusCode().is2xxSuccessful() || stats == null) {
                throw new IllegalStateException("Stats response is invalid");
            }

            long indexed = stats.totalLiveVectors;
            long backlog = 0L;
            long enginePending = 0L;
            int dirty = 0;
            int owned = 0;
            int active = 0;

            for (NodeStats node : stats.nodes) {
                backlog += node.applierBacklog;
                dirty += node.dirtyPartitions;
                enginePending += node.enginePendingVectors;
                owned += node.ownedPartitions;
                active += node.activePartitions;
            }

            boolean ready = indexed == expectedVectorCount
                    && backlog == 0
                    && dirty == 0
                    && enginePending == 0
                    && active == owned
                    && owned > 0;

            System.out.println(
                    "Index state: indexed=" + indexed + "/" + expectedVectorCount
                            + ", backlog=" + backlog
                            + ", dirty=" + dirty
                            + ", enginePending=" + enginePending
                            + ", parts=" + active + "/" + owned
                            + (ready
                            ? " [ok " + (stablePolls + 1) + "/" + READY_STABLE_POLLS + "]"
                            : "")
            );

            if (ready) {
                if (++stablePolls >= READY_STABLE_POLLS) {
                    System.out.println("Index is ready");
                    return;
                }
            } else {
                stablePolls = 0;
            }

            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Interrupted while waiting for index",
                        e
                );
            }
        }

        throw new IllegalStateException(
                "Index was not ready before timeout - check node logs for 'rebuild FAILED'"
        );
    }
}