# VectorDBBench для индекса на Apache Ignite

Клиент `IgniteVindex` для [VectorDBBench](https://github.com/zilliztech/VectorDBBench).
Используется **только тег v2.0.0** (коммит `f50544230470770e711126f48d9a077b9cb62ff1`, тот же
код, что `vectordb-bench 2.0.0` на PyPI). Плагинов у стенда нет: клиент ставится в клон скриптом
`install_client.py`, который проверяет коммит. Идентификатор версии записывается в сводку прогона.

## Как клиент работает со стендом

| Вызов стенда | Что делает клиент |
|---|---|
| `__init__(drop_old)` | сверяет размерность и метрику с индексом (`GET /indexes`); `drop_old` → `POST /indexes/{id}/truncate`, ждёт подтверждения всех серверов |
| `insert_embeddings` | `PUT /indexes/{id}/vectors {ids, vectors}`: внешние ID `Long`, значение — `float[]`; весь батч проверяется до записи; 4xx — неповторяемая ошибка, иначе стенд повторит батч (запись идемпотентна) |
| `optimize(data_size)` | **барьер загрузки**, см. ниже; дополнительно `liveVectors == data_size` |
| `search_embedding` | `POST /indexes/{id}/search` со строгим ответом (`allowPartial=false`); не `COMPLETE` или ошибка HTTP — исключение и строка в журнале клиента |
| `need_normalize_cosine` | `False`: COSINE нормализуется в индексе при приёме |

Барьер загрузки. Стенд вызывает `optimize` после возврата всех `insert_embeddings`, то есть когда
все записи подтверждены. Дальше на каждом сервере:
1. дожидаются завершения уже принятых применений событий. Без этого обработчик, прочитавший
   старое значение до последней записи, применил бы его после финального скана и перекрыл бы
   результат;
2. каждому runtime выдаётся новый тикет сверки;
3. ждут публикации скана, начатого после тикета; скан, начатый раньше, тикет не закрывает;
4. по умолчанию принудительно строится структура текущего состояния на каждой партиции, включая
   маленькую дельту и пустые партиции; active и frozen должны быть пусты, сборок и сканов нет;
5. проверяется готовность.

Барьер связан с отпечатком определения, версией топологии и экземплярами runtime. `RESTART`
(смена топологии, замена runtime, только что стартовавший кластер) — клиент начинает новый
барьер, `FAILED` — ошибка кейса. Без оптимизации (`--no-optimize`) получается отдельный профиль
измерений: поиск идёт по дельте точным перебором.

## Подготовка

Нужны JDK 21, Maven, Docker и Python ≥ 3.11 (проверено на 3.13).

```bash
# 1. Комплект jar для узлов — в target/, docker/ignite-libs не меняется.
JAVA_HOME="C:\Program Files\Java\jdk-21.0.2" bash docker/build-ignite-libs.sh --out target/bench-ignite-libs --compare docker/ignite-libs
JAVA_HOME="C:\Program Files\Java\jdk-21.0.2" mvn -pl index-vector-server -am package -DskipTests

# 2. Два узла с индексами стенда (docker/config-bench): smoke-flat, smoke-jvector, clip512.
docker compose -f docker/docker-compose.bench.yml up -d --build

# 3. REST-сервер на 8091 (флаги --add-opens нужны тонкому клиенту Ignite на JDK 17+).
java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
     --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
     --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED \
     --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
     -jar index-vector-server/target/vector-server-1.0-SNAPSHOT.jar \
     --server.port=8091 --ignite.address=127.0.0.1:10810,127.0.0.1:10811

# 4. VectorDBBench v2.0.0 в отдельном окружении и клиент.
git clone --branch v2.0.0 https://github.com/zilliztech/VectorDBBench.git vdbbench
python -m venv vdbbench-venv && vdbbench-venv/Scripts/python -m pip install -e "./vdbbench[qdrant]" requests
vdbbench-venv/Scripts/python bench/vectordbbench/install_client.py vdbbench
vdbbench-venv/Scripts/python -m pip freeze > vdbbench-requirements.lock   # закрепить зависимости прогона
```

На Windows при клонировании нужен `git -c core.longpaths=true clone ...`, если путь длинный.

## Smoke-прогон

```bash
python bench/vectordbbench/make_dataset.py synthetic data-smoke --size 10000 --dim 128 --queries 200 --metric COSINE

export VINDEX_ERROR_LOG=$PWD/vindex-client-errors.jsonl
vdbbench-venv/Scripts/vectordbbench IgniteVindex --url http://127.0.0.1:8091 --index smoke-flat \
  --case-type PerformanceCustomDataset --custom-case-name smoke-flat \
  --custom-dataset-name synthetic-10k-128 --custom-dataset-dir "$PWD/data-smoke" \
  --custom-dataset-size 10000 --custom-dataset-dim 128 --custom-dataset-metric-type COSINE \
  --custom-dataset-file-count 1 --custom-dataset-with-gt --k 100 \
  --num-concurrency 1,4 --concurrency-duration 10 --db-label smoke
# то же для --index smoke-jvector --ef 128

python bench/vectordbbench/summarize_run.py --errors vindex-client-errors.jsonl \
  --metrics http://127.0.0.1:8091/actuator/prometheus --vdbbench vdbbench
```

Ожидания smoke: у `smoke-flat` recall = 1.0 (Flat воспроизводит точных соседей того же набора с
исходными ID и той же метрикой), `clean: true` в сводке (нет ошибок клиента и неполных ответов).

## Полный прогон: 608k CLIP-512 и сравнение с Qdrant

Многочасовой, запускается вручную на стенде.

1. Набор: экспорт демо-кэша в CSV (`POST /vectors/save`) или имеющийся CSV формата
   `id;url;embedding;metadata`, затем
   `python bench/vectordbbench/make_dataset.py csv data-clip --csv vectors.csv --queries 1000 --metric COSINE`
   (1000 запросов исключаются из train, ground truth — точный перебор, 100 соседей).
2. Наш индекс: `--index clip512 --ef <64|128|256|512>`, тот же `PerformanceCustomDataset` с
   `--custom-dataset-size <число строк train> --custom-dataset-dim 512`, для 608k —
   `--custom-case-load-timeout` и `--custom-case-optimize-timeout` с запасом; память узлов —
   `VINDEX_BENCH_HEAP=8g docker compose -f docker/docker-compose.bench.yml up -d`.
3. Qdrant с закреплённой версией образа (явный тег релиза, не `latest`; digest записывается в
   результаты: `docker image inspect --format '{{index .RepoDigests 0}}' qdrant/qdrant:<тег>`):
   `docker run -d --name qdrant-bench -p 6333:6333 qdrant/qdrant:<тег>` и
   `vectordbbench QdrantLocal --url http://127.0.0.1:6333 --m 16 --ef-construct 200 --hnsw-ef <то же ef> ...`.
   Клиент QdrantLocal в v2.0.0 передаёт `m` и `ef_construct` при создании коллекции (QdrantCloud —
   только `m`); фактическая конфигурация сохраняется в сводку:
   `summarize_run.py --qdrant http://127.0.0.1:6333 QdrantLocalCollection`.
4. Сопоставимость: одинаковые набор, метрика, k и ef; одинаковые ограничения CPU/RAM контейнеров;
   M = 16 и efConstruction = 200 у обоих. Время загрузки у обоих включает построение индекса
   (у Qdrant — ожидание статуса GREEN, у нас — барьер с оптимизацией).
5. Достоверность: для первого сравнительного baseline нужны нулевые ошибки клиента, таймауты и
   неполные ответы (`clean: true`); иначе результат помечается. Конкурентный runner стенда только
   логирует исключения поиска, поэтому ошибки считаются на клиенте (`VINDEX_ERROR_LOG`) и
   сверяются с серверным счётчиком `vindex_search_partial_total` (сервер перезапускать перед
   прогоном или брать разницу).
