package ru.nsu.fit.vector.node.service;

import org.apache.ignite.services.Service;
import ru.nsu.fit.vector.common.dto.SearchResponse;

public interface SearchAggregationService extends Service {
    String NAME = "SearchAggregationService";

    SearchResponse search(float[] vector, int count);
}