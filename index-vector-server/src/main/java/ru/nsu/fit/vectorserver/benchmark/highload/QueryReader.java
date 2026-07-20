package ru.nsu.fit.vectorserver.benchmark.highload;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class QueryReader {

    public List<QueryVector> read(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Queries path is required");
        }

        return read(new File(path));
    }

    public List<QueryVector> read(File file) {
        validateFile(file);

        List<QueryVector> queries = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        int expectedDimension = -1;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String header = reader.readLine();
            if (header == null) {
                throw new IllegalStateException("Queries file is empty: " + file.getAbsolutePath());
            }

            String line;
            int lineNumber = 1;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                if (line.isBlank()) continue;

                QueryVector query = parseLine(line, lineNumber);

                if (!ids.add(query.id())) {
                    throw new IllegalStateException("Duplicate query ID " + query.id() + " at line " + lineNumber);
                }

                if (expectedDimension == -1) {
                    expectedDimension = query.vector().length;
                } else if (query.vector().length != expectedDimension) {
                    throw new IllegalStateException("Incorrect vector dimension at line " + lineNumber
                            + ": " + query.vector().length + ", expected " + expectedDimension);
                }

                queries.add(query);
            }
        } catch (IOException e) {
            throw new RuntimeException("Queries read error: " + file.getAbsolutePath(), e);
        }

        if (queries.isEmpty()) {
            throw new IllegalStateException("Queries file contains no vectors: " + file.getAbsolutePath());
        }

        return queries;
    }

    private QueryVector parseLine(String line, int lineNumber) {
        int firstSemicolon = line.indexOf(';');
        int secondSemicolon = line.indexOf(';', firstSemicolon + 1);

        if (firstSemicolon < 0 || secondSemicolon < 0) {
            throw new IllegalArgumentException("Incorrect query format at line " + lineNumber
                    + ". Expected: id;url;[vector]");
        }

        try {
            long id = Long.parseLong(line.substring(0, firstSemicolon).trim());
            String url = line.substring(firstSemicolon + 1, secondSemicolon).trim();
            float[] vector = parseVector(line.substring(secondSemicolon + 1), lineNumber);

            return new QueryVector(id, url, vector);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Incorrect number at line " + lineNumber, e);
        }
    }

    private float[] parseVector(String value, int lineNumber) {
        String normalized = value.trim();

        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }

        if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Empty vector at line " + lineNumber);
        }

        int valueCount = 1;
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == ',') valueCount++;
        }

        float[] vector = new float[valueCount];
        int start = 0;

        for (int i = 0; i < valueCount; i++) {
            int comma = normalized.indexOf(',', start);
            int end = comma >= 0 ? comma : normalized.length();

            String token = normalized.substring(start, end).trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("Empty vector value at line " + lineNumber
                        + ", position " + i);
            }

            float number = Float.parseFloat(token);
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException("Non-finite vector value at line " + lineNumber
                        + ", position " + i);
            }

            vector[i] = number;
            start = end + 1;
        }

        return vector;
    }

    private void validateFile(File file) {
        if (file == null) {
            throw new IllegalArgumentException("Queries file is required");
        }

        if (!file.exists() || !file.isFile() || !file.canRead()) {
            throw new IllegalArgumentException("Queries file cannot be read: " + file.getAbsolutePath());
        }
    }

    public record QueryVector(long id, String url, float[] vector) {}
}