package ru.nsu.fit.sberlab.vectorindex.node.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.binary.BinaryType;
import org.apache.ignite.cache.CachePeekMode;
import org.apache.ignite.cache.affinity.Affinity;
import org.junit.jupiter.api.Test;

/**
 * Чтение текущего значения из Ignite и извлечение поля вектора: моки интерфейсов Ignite, без
 * reflection. Настоящие {@code BinaryObject} без класса значения — в {@code UniversalIndexIT}.
 */
class IgniteVectorSourceTest {

    @Test
    @SuppressWarnings("unchecked")
    void localPeekMissFallsBackToCacheGet() {
        IgniteCache<Object, Object> cache = mock(IgniteCache.class);
        BinaryObject remote = object("Photo", "vector", new float[]{0, 1});
        BinaryObject withoutVector = object("Photo", "vector", null);
        when(cache.localPeek(10L, CachePeekMode.PRIMARY, CachePeekMode.BACKUP)).thenReturn(null);
        when(cache.get(10L)).thenReturn(remote);
        when(cache.localPeek(11L, CachePeekMode.PRIMARY, CachePeekMode.BACKUP)).thenReturn(withoutVector);

        IgniteVectorSource source = new IgniteVectorSource(mock(Ignite.class), cache, mock(Affinity.class), "vector");

        assertThat(source.read(10L)).isInstanceOfSatisfying(SourceValue.Vector.class,
                present -> assertThat(present.vector()).containsExactly(0, 1));
        assertThat(source.read(11L)).as("record without a vector is not indexed").isEqualTo(SourceValue.NO_VECTOR);
        assertThat(source.read(12L)).isEqualTo(SourceValue.ABSENT);
    }

    @Test
    void fieldIsExtractedWithoutDeserializingTheValue() {
        assertThat(VectorExtractor.extract(object("Photo", "embedding", new float[]{1, 2}), "embedding"))
                .isInstanceOfSatisfying(SourceValue.Vector.class,
                        present -> assertThat(present.vector()).containsExactly(1, 2));
        assertThat(VectorExtractor.extract(object("Photo", "other", new float[]{1, 2}), "embedding"))
                .isEqualTo(SourceValue.NO_VECTOR);
        assertThat(VectorExtractor.extract(object("Photo", "embedding", "not a vector"), "embedding"))
                .isEqualTo(new SourceValue.WrongType("java.lang.String"));
        assertThat(VectorExtractor.extract(object("Photo", "embedding", new double[]{1, 2}), "embedding"))
                .as("only FP32 vectors are accepted").isEqualTo(new SourceValue.WrongType("[D"));
        assertThat(VectorExtractor.extract(new float[]{1, 2}, "embedding"))
                .as("a plain value has no fields").isEqualTo(new SourceValue.WrongType("[F"));
        assertThat(VectorExtractor.extract(null, "embedding")).isEqualTo(SourceValue.ABSENT);
    }

    @Test
    void withoutAFieldTheValueItselfMustBeAVector() {
        assertThat(VectorExtractor.extract(new float[]{3, 4}, null))
                .isInstanceOfSatisfying(SourceValue.Vector.class,
                        present -> assertThat(present.vector()).containsExactly(3, 4));
        assertThat(VectorExtractor.extract(object("Photo", "vector", new float[]{1}), null))
                .isEqualTo(new SourceValue.WrongType("Photo"));
    }

    private static BinaryObject object(String typeName, String field, Object fieldValue) {
        BinaryObject object = mock(BinaryObject.class);
        BinaryType type = mock(BinaryType.class);
        when(type.typeName()).thenReturn(typeName);
        when(object.type()).thenReturn(type);
        when(object.hasField(field)).thenReturn(true);
        when(object.field(field)).thenReturn(fieldValue);
        return object;
    }
}
