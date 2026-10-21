package ru.nsu.fit.sberlab.vectorindex.vectorserver.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ru.nsu.fit.sberlab.vectorindex.common.dto.IndexInfo;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchError;

/** Пакетная загрузка: весь батч проверяется до записи, повтор безопасен, индекс по полю отклоняется. */
class VectorUploaderTest {

    private final List<Map<Long, float[]>> written = new ArrayList<>();
    private final Map<String, IndexInfo> indexes = new LinkedHashMap<>();
    private final VectorUploader uploader = new VectorUploader(indexes::get,
            (cache, batch) -> {
                assertThat(cache).isEqualTo("bench-cache");
                written.add(batch);
            });

    VectorUploaderTest() {
        indexes.put("bench", info("bench", "bench-cache", null, 2));
        indexes.put("photos", info("photos", "vectors", "vector", 2));
    }

    @Test
    void validBatchIsWrittenAsLongToFloatArrayInOneCall() {
        assertThat(uploader.upload("bench", new long[]{7, 3}, new float[][]{{1, 0}, {0, 1}})).isEqualTo(2);

        assertThat(written).hasSize(1);
        assertThat(written.get(0)).containsOnlyKeys(7L, 3L);
        assertThat(written.get(0).get(3L)).containsExactly(0, 1);
        assertThat(uploader.upload("bench", new long[0], new float[0][])).as("empty batch is a no-op").isZero();
        assertThat(written).hasSize(1);
    }

    @Test
    void repeatingABatchWithTheSameIdsAndVectorsIsSafe() {
        long[] ids = {1, 2};
        float[][] vectors = {{1, 0}, {0, 1}};
        uploader.upload("bench", ids, vectors);
        uploader.upload("bench", ids, vectors);

        assertThat(written).hasSize(2);
        assertThat(written.get(1)).containsOnlyKeys(1L, 2L);
        assertThat(written.get(1).get(1L)).containsExactly(written.get(0).get(1L));
    }

    @Test
    void anyInvalidVectorRejectsTheWholeBatchBeforeWriting() {
        assertInvalid(new long[]{1, 2}, new float[][]{{1, 0}, {1, 0, 0}}, "dimension 3");
        assertInvalid(new long[]{1, 2}, new float[][]{{1, 0}, {Float.NaN, 0}}, "NaN");
        assertInvalid(new long[]{1, 2}, new float[][]{{1, 0}, {Float.POSITIVE_INFINITY, 0}}, "Infinity");
        assertInvalid(new long[]{1, 2}, new float[][]{{1, 0}, null}, "dimension null");
        assertInvalid(new long[]{1, 1}, new float[][]{{1, 0}, {0, 1}}, "duplicate id 1");
        assertInvalid(new long[]{1}, new float[][]{{1, 0}, {0, 1}}, "same length");
        assertInvalid(null, new float[][]{{1, 0}}, "same length");
        assertInvalid(new long[VectorUploader.MAX_BATCH + 1], new float[VectorUploader.MAX_BATCH + 1][], "exceeds");
        assertThat(written).as("nothing is written for an invalid batch").isEmpty();
    }

    @Test
    void indexOverABinaryObjectFieldAndUnknownIndexAreRefused() {
        assertThatThrownBy(() -> uploader.upload("photos", new long[]{1}, new float[][]{{1, 0}}))
                .isInstanceOfSatisfying(IndexRequestException.class, e -> {
                    assertThat(e.error()).isEqualTo(SearchError.INVALID_REQUEST);
                    assertThat(e.getMessage()).contains("field 'vector'");
                });
        assertThatThrownBy(() -> uploader.upload("nope", new long[]{1}, new float[][]{{1, 0}}))
                .isInstanceOfSatisfying(IndexRequestException.class,
                        e -> assertThat(e.error()).isEqualTo(SearchError.UNKNOWN_INDEX));
        assertThat(written).isEmpty();
    }

    private void assertInvalid(long[] ids, float[][] vectors, String message) {
        assertThatThrownBy(() -> uploader.upload("bench", ids, vectors))
                .isInstanceOfSatisfying(IndexRequestException.class, e -> {
                    assertThat(e.error()).isEqualTo(SearchError.INVALID_REQUEST);
                    assertThat(e.getMessage()).contains(message);
                });
    }

    private static IndexInfo info(String id, String cache, String field, int dimension) {
        IndexInfo info = new IndexInfo();
        info.indexId = id;
        info.cacheName = cache;
        info.field = field;
        info.dimension = dimension;
        return info;
    }
}
