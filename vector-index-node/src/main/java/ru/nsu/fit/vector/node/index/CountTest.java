//package ru.nsu.fit.vector.node.index;
//
//import ru.nsu.fit.vector.common.ScoredVector;
//
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.HashSet;
//import java.util.List;
//import java.util.Random;
//import java.util.Set;
//
//public class CountTest {
//
//    /*
//     * 200 000 векторов / 32 партиции
//     * ≈ 6 250 векторов на одну партицию.
//     */
//    private static final int VECTOR_COUNT = 6_250;
//
//    private static final int DIMENSION = 128;
//    private static final int TOP_K = 10;
//
//    /*
//     * Чем больше запросов, тем стабильнее измерения.
//     */
//    private static final int QUERY_COUNT = 1_000;
//    private static final int WARMUP_QUERY_COUNT = 200;
//
//    /*
//     * Каждый набор запросов выполняется несколько раз.
//     * Первый прогрев отдельно не входит в измерения.
//     */
//    private static final int MEASUREMENT_ROUNDS = 5;
//
//    private static final long RANDOM_SEED = 42L;
//
//    public static void main(String[] args) {
//        printHeader();
//
//        Random random = new Random(RANDOM_SEED);
//
//        System.out.println("1. Генерация индексируемых векторов...");
//
//        long vectorGenerationStart = System.nanoTime();
//
//        List<float[]> vectors = generateVectors(
//                VECTOR_COUNT,
//                random
//        );
//
//        long vectorGenerationNanos =
//                System.nanoTime() - vectorGenerationStart;
//
//        System.out.printf(
//                "   Создано %,d векторов за %.3f мс%n",
//                vectors.size(),
//                nanosToMillis(vectorGenerationNanos)
//        );
//
//        System.out.println();
//        System.out.println("2. Генерация независимых запросов...");
//
//        List<float[]> queries = generateVectors(
//                QUERY_COUNT,
//                random
//        );
//
//        System.out.printf(
//                "   Создано %,d запросов.%n",
//                queries.size()
//        );
//
//        PartitionVectorIndex bruteForceIndex =
//                new BruteForcePartitionIndex();
//
//        /*
//         * Второй параметр пока оставлен, чтобы класс собирался
//         * с твоим текущим конструктором.
//         *
//         * В текущем JVectorPartitionIndex searchExpansion не используется.
//         */
//        //PartitionVectorIndex jVectorIndex = new JVectorPartitionIndex(DIMENSION);
//
//        System.out.println();
//        System.out.println("3. Загрузка brute force индекса...");
//
//        long bruteForceLoadStart = System.nanoTime();
//
//        for (int i = 0; i < vectors.size(); i++) {
//            bruteForceIndex.add(
//                    i,
//                    vectors.get(i)
//            );
//        }
//
//        long bruteForceLoadNanos =
//                System.nanoTime() - bruteForceLoadStart;
//
//        System.out.printf(
//                "   Brute force загружен за %.3f мс%n",
//                nanosToMillis(bruteForceLoadNanos)
//        );
//
//        System.out.println();
//        System.out.println(
//                "4. Загрузка JVector и выполнение всех rebuild..."
//        );
//
//        long jVectorLoadStart = System.nanoTime();
//
//        for (int i = 0; i < vectors.size(); i++) {
//            //jVectorIndex.add(
//            //        i,
//                    //vectors.get(i)
//            //);
//        }
//
//        long jVectorLoadNanos =
//                System.nanoTime() - jVectorLoadStart;
//
//        System.out.printf(
//                "   JVector загружен за %.3f мс%n",
//                nanosToMillis(jVectorLoadNanos)
//        );
//
//        System.out.println();
//        System.out.println("5. Проверка размеров индексов...");
//
//        System.out.printf(
//                "   Brute force: %,d%n",
//                bruteForceIndex.size()
//        );
//
//        System.out.printf(
//                "   JVector:     %,d%n",
//                jVectorIndex.size()
//        );
//
//        if (bruteForceIndex.size() != VECTOR_COUNT) {
//            throw new IllegalStateException(
//                    "Некорректный размер brute force: "
//                            + bruteForceIndex.size()
//            );
//        }
//
//        if (jVectorIndex.size() != VECTOR_COUNT) {
//            throw new IllegalStateException(
//                    "Некорректный размер JVector: "
//                            + jVectorIndex.size()
//            );
//        }
//
//        System.out.println();
//        System.out.println("6. Прогрев JVM и индексов...");
//
//        warmUp(
//                bruteForceIndex,
//                jVectorIndex,
//                queries
//        );
//
//        System.out.println("   Прогрев завершён.");
//
//        System.out.println();
//        System.out.println(
//                "7. Вычисление точных результатов brute force..."
//        );
//
//        /*
//         * Точные результаты сохраняются один раз.
//         * Их затем используем как эталон для recall.
//         */
//        List<List<ScoredVector>> exactResults =
//                calculateExactResults(
//                        bruteForceIndex,
//                        queries
//                );
//
//        System.out.println(
//                "   Точные результаты рассчитаны."
//        );
//
//        System.out.println();
//        System.out.println(
//                "8. Измерение времени поиска..."
//        );
//
//        SearchMeasurement bruteForceMeasurement =
//                measureSearch(
//                        bruteForceIndex,
//                        queries,
//                        MEASUREMENT_ROUNDS
//                );
//
//        SearchMeasurement jVectorMeasurement =
//                measureSearch(
//                        jVectorIndex,
//                        queries,
//                        MEASUREMENT_ROUNDS
//                );
//
//        QualityMeasurement quality =
//                measureQuality(
//                        jVectorIndex,
//                        queries,
//                        exactResults
//                );
//
//        printResults(
//                vectorGenerationNanos,
//                bruteForceLoadNanos,
//                jVectorLoadNanos,
//                bruteForceMeasurement,
//                jVectorMeasurement,
//                quality
//        );
//
//        closeIndex(jVectorIndex);
//        closeIndex(bruteForceIndex);
//    }
//
//    private static List<List<ScoredVector>> calculateExactResults(
//            PartitionVectorIndex bruteForceIndex,
//            List<float[]> queries
//    ) {
//        List<List<ScoredVector>> exactResults =
//                new ArrayList<>(queries.size());
//
//        for (float[] query : queries) {
//            exactResults.add(
//                    bruteForceIndex.search(
//                            query,
//                            TOP_K
//                    )
//            );
//        }
//
//        return List.copyOf(exactResults);
//    }
//
//    private static SearchMeasurement measureSearch(
//            PartitionVectorIndex index,
//            List<float[]> queries,
//            int rounds
//    ) {
//        int operationCount =
//                queries.size() * rounds;
//
//        long[] times =
//                new long[operationCount];
//
//        int position = 0;
//
//        for (int round = 0; round < rounds; round++) {
//            for (float[] query : queries) {
//                long start = System.nanoTime();
//
//                List<ScoredVector> result =
//                        index.search(
//                                query,
//                                TOP_K
//                        );
//
//                long elapsed =
//                        System.nanoTime() - start;
//
//                if (result.size() > TOP_K) {
//                    throw new IllegalStateException(
//                            "Индекс вернул больше topK результатов: "
//                                    + result.size()
//                    );
//                }
//
//                times[position++] = elapsed;
//            }
//        }
//
//        return calculateMeasurement(times);
//    }
//
//    private static QualityMeasurement measureQuality(
//            PartitionVectorIndex jVectorIndex,
//            List<float[]> queries,
//            List<List<ScoredVector>> exactResults
//    ) {
//        double totalRecall = 0.0;
//        int sameFirstResultCount = 0;
//        int incompleteResultCount = 0;
//
//        for (int i = 0; i < queries.size(); i++) {
//            List<ScoredVector> expected =
//                    exactResults.get(i);
//
//            List<ScoredVector> actual =
//                    jVectorIndex.search(
//                            queries.get(i),
//                            TOP_K
//                    );
//
//            totalRecall +=
//                    recallAtK(
//                            expected,
//                            actual
//                    );
//
//            if (hasSameFirstResult(
//                    expected,
//                    actual
//            )) {
//                sameFirstResultCount++;
//            }
//
//            if (actual.size() < Math.min(
//                    TOP_K,
//                    VECTOR_COUNT
//            )) {
//                incompleteResultCount++;
//            }
//        }
//
//        return new QualityMeasurement(
//                totalRecall / queries.size(),
//                sameFirstResultCount,
//                incompleteResultCount
//        );
//    }
//
//    private static void warmUp(
//            PartitionVectorIndex bruteForceIndex,
//            PartitionVectorIndex jVectorIndex,
//            List<float[]> queries
//    ) {
//        int count = Math.min(
//                WARMUP_QUERY_COUNT,
//                queries.size()
//        );
//
//        for (int i = 0; i < count; i++) {
//            float[] query = queries.get(i);
//
//            bruteForceIndex.search(
//                    query,
//                    TOP_K
//            );
//
//            jVectorIndex.search(
//                    query,
//                    TOP_K
//            );
//        }
//    }
//
//    private static SearchMeasurement calculateMeasurement(
//            long[] times
//    ) {
//        long[] sorted =
//                times.clone();
//
//        Arrays.sort(sorted);
//
//        long total = 0L;
//
//        for (long time : times) {
//            total += time;
//        }
//
//        double averageMicros =
//                nanosToMicros(total)
//                        / times.length;
//
//        double p50Micros =
//                percentileMicros(
//                        sorted,
//                        0.50
//                );
//
//        double p95Micros =
//                percentileMicros(
//                        sorted,
//                        0.95
//                );
//
//        double p99Micros =
//                percentileMicros(
//                        sorted,
//                        0.99
//                );
//
//        double minimumMicros =
//                nanosToMicros(sorted[0]);
//
//        double maximumMicros =
//                nanosToMicros(
//                        sorted[sorted.length - 1]
//                );
//
//        double qps =
//                1_000_000.0 / averageMicros;
//
//        return new SearchMeasurement(
//                averageMicros,
//                p50Micros,
//                p95Micros,
//                p99Micros,
//                minimumMicros,
//                maximumMicros,
//                qps
//        );
//    }
//
//    private static double percentileMicros(
//            long[] sortedTimes,
//            double percentile
//    ) {
//        int index =
//                (int) Math.ceil(
//                        percentile
//                                * sortedTimes.length
//                ) - 1;
//
//        index = Math.max(
//                0,
//                Math.min(
//                        index,
//                        sortedTimes.length - 1
//                )
//        );
//
//        return nanosToMicros(
//                sortedTimes[index]
//        );
//    }
//
//    private static double recallAtK(
//            List<ScoredVector> expected,
//            List<ScoredVector> actual
//    ) {
//        if (expected.isEmpty()) {
//            return actual.isEmpty()
//                    ? 1.0
//                    : 0.0;
//        }
//
//        Set<Long> expectedIds =
//                new HashSet<>();
//
//        for (ScoredVector vector : expected) {
//            expectedIds.add(
//                    vector.id()
//            );
//        }
//
//        int matches = 0;
//
//        for (ScoredVector vector : actual) {
//            if (expectedIds.contains(vector.id())) {
//                matches++;
//            }
//        }
//
//        return (double) matches
//                / expected.size();
//    }
//
//    private static boolean hasSameFirstResult(
//            List<ScoredVector> expected,
//            List<ScoredVector> actual
//    ) {
//        return !expected.isEmpty()
//                && !actual.isEmpty()
//                && expected.get(0).id()
//                == actual.get(0).id();
//    }
//
//    private static List<float[]> generateVectors(
//            int count,
//            Random random
//    ) {
//        List<float[]> vectors =
//                new ArrayList<>(count);
//
//        for (int i = 0; i < count; i++) {
//            vectors.add(
//                    randomNormalizedVector(
//                            random,
//                            DIMENSION
//                    )
//            );
//        }
//
//        return vectors;
//    }
//
//    private static float[] randomNormalizedVector(
//            Random random,
//            int dimension
//    ) {
//        float[] vector =
//                new float[dimension];
//
//        double normSquared = 0.0;
//
//        for (int i = 0; i < dimension; i++) {
//            float value =
//                    (float) random.nextGaussian();
//
//            vector[i] = value;
//
//            normSquared +=
//                    value * value;
//        }
//
//        if (normSquared == 0.0) {
//            throw new IllegalStateException(
//                    "Сгенерирован нулевой вектор"
//            );
//        }
//
//        double norm =
//                Math.sqrt(normSquared);
//
//        for (int i = 0; i < dimension; i++) {
//            vector[i] /= (float) norm;
//        }
//
//        return vector;
//    }
//
//    private static void printHeader() {
//        System.out.println(
//                "=============================================================================================================="
//        );
//
//        System.out.println(
//                "ПРОВЕРКА ТЕКУЩЕЙ ЭФФЕКТИВНОСТИ JVECTOR"
//        );
//
//        System.out.println(
//                "=============================================================================================================="
//        );
//
//        System.out.printf(
//                "Векторов в партиции:          %,d%n",
//                VECTOR_COUNT
//        );
//
//        System.out.printf(
//                "Размерность:                  %d%n",
//                DIMENSION
//        );
//
//        System.out.printf(
//                "Top-K:                       %d%n",
//                TOP_K
//        );
//
//        System.out.printf(
//                "Уникальных запросов:          %,d%n",
//                QUERY_COUNT
//        );
//
//        System.out.printf(
//                "Раундов измерения:            %d%n",
//                MEASUREMENT_ROUNDS
//        );
//
//        System.out.printf(
//                "Всего измеряемых запросов:    %,d%n",
//                QUERY_COUNT * MEASUREMENT_ROUNDS
//        );
//
//        System.out.println();
//    }
//
//    private static void printResults(
//            long vectorGenerationNanos,
//            long bruteForceLoadNanos,
//            long jVectorLoadNanos,
//            SearchMeasurement bruteForce,
//            SearchMeasurement jVector,
//            QualityMeasurement quality
//    ) {
//        double speedup =
//                bruteForce.averageMicros()
//                        / jVector.averageMicros();
//
//        System.out.println();
//        System.out.println(
//                "=============================================================================================================="
//        );
//
//        System.out.println(
//                "РЕЗУЛЬТАТЫ"
//        );
//
//        System.out.println(
//                "=============================================================================================================="
//        );
//
//        System.out.printf(
//                "%-31s | %17s | %17s%n",
//                "Метрика",
//                "Brute force",
//                "JVector"
//        );
//
//        System.out.println(
//                "--------------------------------+-------------------+-------------------"
//        );
//
//        System.out.printf(
//                "%-31s | %14.3f мс | %14.3f мс%n",
//                "Загрузка индекса",
//                nanosToMillis(bruteForceLoadNanos),
//                nanosToMillis(jVectorLoadNanos)
//        );
//
//        System.out.printf(
//                "%-31s | %13.3f мкс | %13.3f мкс%n",
//                "Среднее время поиска",
//                bruteForce.averageMicros(),
//                jVector.averageMicros()
//        );
//
//        System.out.printf(
//                "%-31s | %13.3f мкс | %13.3f мкс%n",
//                "P50 времени поиска",
//                bruteForce.p50Micros(),
//                jVector.p50Micros()
//        );
//
//        System.out.printf(
//                "%-31s | %13.3f мкс | %13.3f мкс%n",
//                "P95 времени поиска",
//                bruteForce.p95Micros(),
//                jVector.p95Micros()
//        );
//
//        System.out.printf(
//                "%-31s | %13.3f мкс | %13.3f мкс%n",
//                "P99 времени поиска",
//                bruteForce.p99Micros(),
//                jVector.p99Micros()
//        );
//
//        System.out.printf(
//                "%-31s | %13.3f мкс | %13.3f мкс%n",
//                "Минимальное время",
//                bruteForce.minimumMicros(),
//                jVector.minimumMicros()
//        );
//
//        System.out.printf(
//                "%-31s | %13.3f мкс | %13.3f мкс%n",
//                "Максимальное время",
//                bruteForce.maximumMicros(),
//                jVector.maximumMicros()
//        );
//
//        System.out.printf(
//                "%-31s | %13.2f QPS | %13.2f QPS%n",
//                "Пропускная способность",
//                bruteForce.qps(),
//                jVector.qps()
//        );
//
//        System.out.println();
//        System.out.println("КАЧЕСТВО JVECTOR");
//
//        System.out.printf(
//                "Recall@%d:                    %.4f%n",
//                TOP_K,
//                quality.averageRecall()
//        );
//
//        System.out.printf(
//                "Совпадение первого результата: %d/%d%n",
//                quality.sameFirstResultCount(),
//                QUERY_COUNT
//        );
//
//        System.out.printf(
//                "Неполных результатов:          %d/%d%n",
//                quality.incompleteResultCount(),
//                QUERY_COUNT
//        );
//
//        System.out.println();
//        System.out.println("ИТОГОВОЕ СРАВНЕНИЕ");
//
//        System.out.printf(
//                "Ускорение JVector:             %.2fx%n",
//                speedup
//        );
//
//        System.out.printf(
//                "Разница времени загрузки:      %.2fx%n",
//                (double) jVectorLoadNanos
//                        / bruteForceLoadNanos
//        );
//
//        System.out.printf(
//                "Время генерации данных:        %.3f мс%n",
//                nanosToMillis(vectorGenerationNanos)
//        );
//
//        System.out.println();
//
//        if (speedup > 1.0) {
//            System.out.printf(
//                    "JVector быстрее brute force в %.2f раза.%n",
//                    speedup
//            );
//        } else {
//            System.out.printf(
//                    "JVector медленнее brute force в %.2f раза.%n",
//                    1.0 / speedup
//            );
//        }
//
//        if (quality.averageRecall() >= 0.95) {
//            System.out.println(
//                    "Recall высокий: текущая ширина поиска выглядит приемлемо."
//            );
//        } else if (quality.averageRecall() >= 0.90) {
//            System.out.println(
//                    "Recall приемлемый, но есть смысл проверить немного больший rerankK."
//            );
//        } else {
//            System.out.println(
//                    "Recall низкий: текущую конфигурацию поиска нужно улучшать."
//            );
//        }
//
//        System.out.println(
//                "=============================================================================================================="
//        );
//    }
//
//    private static double nanosToMillis(
//            long nanos
//    ) {
//        return nanos / 1_000_000.0;
//    }
//
//    private static double nanosToMicros(
//            long nanos
//    ) {
//        return nanos / 1_000.0;
//    }
//
//    private static void closeIndex(
//            PartitionVectorIndex index
//    ) {
//        if (!(index instanceof AutoCloseable closeable)) {
//            return;
//        }
//
//        try {
//            closeable.close();
//        } catch (Exception e) {
//            throw new RuntimeException(
//                    "Не удалось закрыть индекс",
//                    e
//            );
//        }
//    }
//
//    private record SearchMeasurement(
//            double averageMicros,
//            double p50Micros,
//            double p95Micros,
//            double p99Micros,
//            double minimumMicros,
//            double maximumMicros,
//            double qps
//    ) {
//    }
//
//    private record QualityMeasurement(
//            double averageRecall,
//            int sameFirstResultCount,
//            int incompleteResultCount
//    ) {
//    }
//}