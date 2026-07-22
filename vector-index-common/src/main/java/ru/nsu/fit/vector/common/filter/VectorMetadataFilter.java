package ru.nsu.fit.vector.common.filter;

import java.io.Serializable;
import ru.nsu.fit.vector.common.VectorObject;

@FunctionalInterface
public interface VectorMetadataFilter extends Serializable {
    boolean test(long id, VectorObject object);
}