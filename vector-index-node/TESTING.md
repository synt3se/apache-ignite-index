# Проверки векторного индекса

Проверки разложены по модулям ядра (`docs/ANALYSIS-AND-ROADMAP.md`, §7):

| Модуль | Что проверяется | Ignite |
|---|---|---|
| `vector-index-core` | метрики и приём векторов, слои дельты, протокол `PartitionRuntime` (скан, ревизия базы, `liveCount`, токены сборки, виды) | нет |
| `vector-index-testkit` | контрактный набор runtime для Flat, `BuildService` | нет |
| `vector-index-jvector` | тот же контрактный набор для JVector, параметры поиска, recall | нет |
| `vector-index-node` | адаптер на событиях Ignite, локальный поиск и покрытие, координатор и гидрация, интеграция на настоящем Ignite | да |

REST, Telegram, gateway, embedding-сервис и нагрузочные раннеры в этот набор не входят.

## Запуск

Проверенный вариант — JDK 21 и Maven. Maven использует `JAVA_HOME`, который может отличаться
от `java` в PATH; текущие Mockito/Byte Buddy не поддерживают JDK 26.

Из корня репозитория:

```powershell
# Все проверки ядра, алгоритмов, адаптера и интеграционные (модули подтягиваются через -am).
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.2"
mvn -pl vector-index-node -am clean test

# Только интеграционные проверки на настоящем Ignite.
mvn -pl vector-index-node -am test "-Dtest=DerivativeIndexIT" "-Dsurefire.failIfNoSpecifiedTests=false"

# Только ядро и алгоритмы, без Ignite.
mvn -pl vector-index-core,vector-index-testkit,vector-index-jvector -am test
```

`vector-index-testkit` — обычный jar (не test-jar), поэтому соседние модули получают его из
реактора уже на фазе `test`, без `install`. Surefire в `vector-index-node` явно включает `*IT`:
интеграционные проверки выполняются на фазе `test`. Отчёты — `*/target/surefire-reports/`.

Прогон 20.10.2026 на JDK 21 / Ignite 2.18.0, `mvn -pl vector-index-node -am clean test`:
**160 проверок, 0 падений** (core 35, testkit 43, jvector 38, node 44).

## Известные дефекты прежней реализации — закрыты

Пять сценариев с тегом `known-issue` перенесены на новую структуру без ослабления утверждений,
стали зелёными на новой реализации, после чего тег снят. Исходный прогон на старом коде
(62 проверки, падали ровно эти 5) зафиксирован в истории ветки `feature/vector-index-core`.

| Проблема | Было | Стало |
|---|---|---|
| К1: удаление теряется при публикации графа | `JVectorPartitionIndexTest.deletionAfterUpdatedVectorWasCapturedMustSurvivePublication` | `PartitionRuntimeContract` — то же имя; Flat и JVector |
| К2: запрос восстановления теряется при overflow во время скана | `PartitionIndexManagerTest.overflowDuringRebuildIsNotForgottenByConcurrentReconcile` | `IgniteIndexAdapterTest` — то же имя |
| К4: coverage не соответствует фактическому поиску | `NodeSearchJobCoverageTest.coverageDescribesSearchedPartitionsRatherThanLaterActivation` | `LocalSearchTest` — то же имя |
| К4: гидрация после исчерпания бюджета | `SearchAggregatorServiceImplTest.doesNotStartHydrationAfterDeadlineIsExhausted` | `SearchCoordinatorTest` — то же имя |
| Владение входным массивом | `BruteForcePartitionIndexTest.addCopiesCallerOwnedArray` | `PartitionRuntimeContract` — то же имя; Flat и JVector |

## Что покрыто

- `PartitionRuntimeContract` (Flat — `FlatRuntimeContractTest`, JVector — `JVectorRuntimeContractTest`):
  сценарии прежних `PartitionVectorIndexContractTest`, `BruteForcePartitionIndexTest`,
  `JVectorPartitionIndexTest` и обязательные расписания §7.10 — capture-upsert → delete → publish,
  capture-delete → reinsert → publish, изменение после прохода курсора скана, потерянное
  изменение и следующая сверка, поздняя сборка после close и смены определения, вид удерживает
  старую базу до `close()`, ошибки скана и сборки, повторные изменения, независимость двух
  runtime; seeded-модельная проверка случайных расписаний с потерянными событиями и сверкой;
  алгоритм не меняет векторы ядра. Логическое содержимое и значения сверяются с независимой
  `ExactModel` (свои формулы расстояния), ANN — по допустимости выдачи.
- `PartitionRuntimeTest`: протокол на уровне ядра — ревизия базы и дедупликация, отсутствие
  коротких путей при открытом скане, точный `liveCount` при публикации скана (в том числе под
  параллельной записью и в окне «снимок → замок»), одна действующая сессия скана, оба порядка
  завершения скана и сборки, освобождение ресурсов токена и вида, `REJECTED` с tombstone, бюджет
  на границах этапов поиска (истёк в дельте или во frozen — база не начинается; подготовка frozen
  прерывается по бюджету и не кэшируется частично), добор при недоборе ANN.
- `BuildServiceTest`: склейка запросов, повтор с backoff, отказ executor без захвата, отмена без
  счёта ошибки, `Error` не подавляется, перепроверка порога после публикации.
- `JVectorAlgorithmTest`: проверка параметров (`ef ≥ k`, чужие параметры) и recall@10 ≥ 0.9 против
  точной модели для COSINE и SQUARED_L2, в том числе с 30% удалений и 10% обновлений в дельте.
- `IgniteIndexAdapterTest`: перечитывание текущего значения по событию, повторы, удаления и
  записи без вектора, top-k по партициям, смена владельца (эпизод закрывается), overflow в
  покое и во время скана, replay во время скана, bulk pause/resume (К5), ошибка чтения →
  сверка, фаза загрузки, первоначальный скан нового эпизода при задании прежнего в очереди
  (партиция ушла и вернулась), отказ пула на первоначальном скане, неподдерживаемые ключи,
  сборки в общем пуле.
- `LocalSearchTest`: покрытие узла — партиции, вид которых открыт в READY и поиск завершён в
  бюджете; причины отказа по партициям, известный пробел, переполнение пула, бюджет.
- `SearchCoordinatorTest`: глобальный top-k, повторы ключей (учитываются как нарушение), гидрация
  и её батчи, отказы и таймауты узлов, неполное покрытие, назначение партиций и смена владельца
  во время запроса (без двойного учёта), известный пробел до клиента, остаток бюджета узлам.
- `StripedApplierTest`: порядок применения, независимость полос, переполнение.
- `DerivativeIndexIT`: настоящий двухузловой Ignite — события put/update/remove, потеря и возврат
  узла без потерь и дубликатов, распределённый поиск через compute job (полное покрытие,
  затем `NO_INDEX` и `partial` при остановленном индексе узла).

## Управление конкурентностью и изоляция

Reflection и обращения к закрытым полям не используются. Executor сканов, сборок и поиска,
часы, отложенные повторы, источник данных и транспорт внедряются конструктором; в тестах —
`ManualExecutor`, `ManualClock`, `ManualDelayedExecutor` из `vector-index-testkit`, источник в
памяти и подмены транспорта. Сборки выполняются явными `capture/publish` или ручным executor,
поэтому расписания детерминированы без задержек. Интерфейсы Ignite мокаются Mockito там, где
проверяется именно Ignite-реализация (`IgniteVectorSourceTest`).

Интеграционные тесты используют loopback, discovery-порты 37500–37509, communication-порты
37200–37209 и временные рабочие каталоги; Docker и внешний кластер не нужны. Число доступных
процессоров тестовой JVM ограничено четырьмя, data region узла — 64 МиБ.

## Границы покрытия

Набор не проверяет синхронную видимость (`IndexingSpi` — отдельный спайк), транзакционные
гарантии, persistence, совпадение версии документа с версией вектора при гидрации, несколько
определений индексов на узле (этап C) и путь REST/бенчмарков. Видимость v1 — eventual на
событиях кэша: проверяется сходимость, а не ограниченное отставание.
