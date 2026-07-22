package ru.nsu.fit.sberlab.vectorindex.vectorserver;

import org.apache.ignite.client.ClientCache;
import org.apache.ignite.client.IgniteClient;
import org.springframework.stereotype.Component;

/**
 * Генерация id — задача сервисного слоя, а не индекса.
 *
 * Кластерно-безопасный счётчик через CAS (compare-and-set) по кэшу Ignite:
 * replace(key, old, new) атомарен на уровне кластера, поэтому параллельные запросы
 * (и даже несколько инстансов сервиса) не выдадут одинаковый id.
 *
 * Механизм спрятан здесь: захотите батчинг — поменяете тело nextId() на
 * IgniteAtomicSequence, не трогая ни сервис, ни индекс.
 */
@Component
public class IdGenerator {

    private static final String ID_KEY = "imageId";

    private final ClientCache<String, Long> counters;

    public IdGenerator(IgniteClient igniteClient) {
        this.counters = igniteClient.getOrCreateCache("counters");
        this.counters.putIfAbsent(ID_KEY, 0L);   // seed один раз, идемпотентно
    }

    public long nextId() {
        while (true) {
            Long cur = counters.get(ID_KEY);
            if (cur == null) {                       // если кэш очистили
                if (counters.putIfAbsent(ID_KEY, 1L)) {
                    return 1L;
                }
                continue;                            // кто-то опередил — перечитаем
            }
            if (counters.replace(ID_KEY, cur, cur + 1)) {
                return cur + 1;                      // CAS удался — id наш
            }
            // конкурент обновил счётчик — повторяем цикл
        }
    }

    public void setCounter(long value) {
        this.counters.put(ID_KEY, value);
    }
}
