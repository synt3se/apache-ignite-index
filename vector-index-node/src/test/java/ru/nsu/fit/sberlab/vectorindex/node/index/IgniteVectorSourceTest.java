package ru.nsu.fit.sberlab.vectorindex.node.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CachePeekMode;
import org.apache.ignite.cache.affinity.Affinity;
import org.junit.jupiter.api.Test;

import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;

/** Чтение текущего значения из Ignite: моки интерфейсов Ignite, без reflection. */
class IgniteVectorSourceTest {

    @Test
    @SuppressWarnings("unchecked")
    void localPeekMissFallsBackToCacheGet() {
        IgniteCache<Object, VectorObject> cache = mock(IgniteCache.class);
        Affinity<Object> affinity = mock(Affinity.class);
        when(cache.localPeek(10L, CachePeekMode.PRIMARY, CachePeekMode.BACKUP)).thenReturn(null);
        when(cache.get(10L)).thenReturn(new VectorObject(new float[]{0, 1}, "url", ""));
        when(cache.localPeek(11L, CachePeekMode.PRIMARY, CachePeekMode.BACKUP))
                .thenReturn(new VectorObject(null, "no-vector", ""));

        IgniteVectorSource source = new IgniteVectorSource(mock(Ignite.class), cache, affinity);

        assertThat(source.read(10L)).containsExactly(0, 1);
        assertThat(source.read(11L)).as("record without a vector is absent from the projection").isNull();
        assertThat(source.read(12L)).isNull();
    }
}
