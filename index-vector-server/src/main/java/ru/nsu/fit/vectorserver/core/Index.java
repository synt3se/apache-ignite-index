package ru.nsu.fit.vectorserver.core;

import ru.nsu.fit.vectorserver.VectorObject;
import ru.nsu.fit.vectorserver.dto.AddRequest;
import ru.nsu.fit.vectorserver.dto.Neighbor;
import ru.nsu.fit.vectorserver.dto.VectorResponse;

import java.util.List;

public interface Index {

    void add(AddRequest request);
    boolean delete(long id);
    void clear();
    VectorObject get(long id);
    List<Neighbor> search(float[] vector, int count);
}
