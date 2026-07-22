package ru.nsu.fit.sberlab.vectorindex.node.service;

import org.apache.ignite.services.Service;
import ru.nsu.fit.sberlab.vectorindex.common.dto.SearchResponse;
import ru.nsu.fit.sberlab.vectorindex.common.filter.VectorMetadataFilter;
import java.util.function.LongPredicate;

public interface SearchAggregationService extends Service {
    String NAME = "SearchAggregationService";

    SearchResponse search(float[] vector, int count, String filter);
}