package ru.nsu.fit.sberlab.vectorindex.common.filter;

import java.io.Serializable;
import ru.nsu.fit.sberlab.vectorindex.common.VectorObject;

@FunctionalInterface
public interface VectorMetadataFilter extends Serializable {
    boolean test(long id, VectorObject object);
}