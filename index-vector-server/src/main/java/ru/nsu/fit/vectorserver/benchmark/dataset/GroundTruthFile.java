package ru.nsu.fit.vectorserver.benchmark.dataset;

import ru.nsu.fit.vector.common.dto.Neighbor;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GroundTruthFile {

    private static final String HEADER =
            "query_id;position;neighbor_id;distance";

    public void write(
            String path,
            Map<Long, List<Neighbor>> results
    ) {
        File file = new File(path);
        File parent = file.getParentFile();

        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException(
                    "Cannot create directory: " + parent.getAbsolutePath()
            );
        }

        try (BufferedWriter writer = new BufferedWriter(
                new FileWriter(file, false)
        )) {
            writer.write(HEADER);
            writer.newLine();

            for (Map.Entry<Long, List<Neighbor>> entry : results.entrySet()) {
                long queryId = entry.getKey();
                List<Neighbor> neighbors = entry.getValue();

                for (int i = 0; i < neighbors.size(); i++) {
                    Neighbor neighbor = neighbors.get(i);

                    writer.write(Long.toString(queryId));
                    writer.write(';');

                    writer.write(Integer.toString(i + 1));
                    writer.write(';');

                    writer.write(Long.toString(neighbor.id()));
                    writer.write(';');

                    writer.write(Double.toString(neighbor.score()));
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(
                    "Ground truth write error: " + path,
                    e
            );
        }
    }

    public Map<Long, List<ExpectedNeighbor>> read(String path) {
        File file = new File(path);

        if (!file.exists()) {
            throw new IllegalArgumentException(
                    "Ground truth file does not exist: " + path
            );
        }

        Map<Long, List<ExpectedNeighbor>> result =
                new LinkedHashMap<>();

        try (BufferedReader reader = new BufferedReader(
                new FileReader(file)
        )) {
            String header = reader.readLine();

            if (!HEADER.equals(header)) {
                throw new IllegalStateException(
                        "Incorrect ground truth header. Expected: "
                                + HEADER
                                + ", actual: "
                                + header
                );
            }

            String line;
            int lineNumber = 1;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                if (line.isBlank()) continue;

                String[] parts = line.split(";", -1);

                if (parts.length != 4) {
                    throw new IllegalStateException(
                            "Incorrect ground truth line "
                                    + lineNumber
                                    + ": "
                                    + line
                    );
                }

                long queryId;
                int position;
                long neighborId;
                double distance;

                try {
                    queryId = Long.parseLong(parts[0].trim());
                    position = Integer.parseInt(parts[1].trim());
                    neighborId = Long.parseLong(parts[2].trim());
                    distance = Double.parseDouble(parts[3].trim());
                } catch (NumberFormatException e) {
                    throw new IllegalStateException(
                            "Incorrect number at ground truth line "
                                    + lineNumber,
                            e
                    );
                }

                if (position <= 0) {
                    throw new IllegalStateException(
                            "Position must be positive at line "
                                    + lineNumber
                    );
                }

                if (!Double.isFinite(distance)) {
                    throw new IllegalStateException(
                            "Distance must be finite at line "
                                    + lineNumber
                    );
                }

                result.computeIfAbsent(
                        queryId,
                        ignored -> new ArrayList<>()
                ).add(
                        new ExpectedNeighbor(
                                position,
                                neighborId,
                                distance
                        )
                );
            }
        } catch (IOException e) {
            throw new RuntimeException(
                    "Ground truth read error: " + path,
                    e
            );
        }

        for (Map.Entry<Long, List<ExpectedNeighbor>> entry
                : result.entrySet()) {

            List<ExpectedNeighbor> neighbors = entry.getValue();

            neighbors.sort(
                    Comparator.comparingInt(
                            ExpectedNeighbor::position
                    )
            );

            validatePositions(
                    entry.getKey(),
                    neighbors
            );
        }

        return result;
    }

    private void validatePositions(
            long queryId,
            List<ExpectedNeighbor> neighbors
    ) {
        for (int i = 0; i < neighbors.size(); i++) {
            int expectedPosition = i + 1;
            int actualPosition = neighbors.get(i).position();

            if (actualPosition != expectedPosition) {
                throw new IllegalStateException(
                        "Incorrect position for query "
                                + queryId
                                + ": expected "
                                + expectedPosition
                                + ", actual "
                                + actualPosition
                );
            }
        }
    }

    public record ExpectedNeighbor(
            int position,
            long id,
            double distance
    ) {}
}