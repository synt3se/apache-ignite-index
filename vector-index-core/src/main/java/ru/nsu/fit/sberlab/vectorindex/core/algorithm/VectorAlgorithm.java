package ru.nsu.fit.sberlab.vectorindex.core.algorithm;

/**
 * Алгоритм — фабрика поисковых структур, без состояния и без знания об источнике данных,
 * очереди изменений и правилах удаления. Изменения, ревизии, маскирование, публикацию и
 * время жизни ресурсов обеспечивает ядро.
 */
public interface VectorAlgorithm {

    String name();

    AlgorithmSearchParams defaultSearchParams(int k);

    /** @throws IllegalArgumentException параметры чужого алгоритма или недопустимые для {@code k} */
    void validate(AlgorithmSearchParams params, int k);

    /**
     * Более широкий поиск для добора, когда маска отсекла часть кандидатов.
     *
     * @return {@code null}, если расширять нечего — ядро перейдёт к точному перебору базы
     */
    AlgorithmSearchParams widen(AlgorithmSearchParams params, int k);

    /** Строит структуру над стабильным входом; может быть долгой, вызывается вне замков. */
    SearchStructure build(FrozenVectorView input, BuildContext context) throws Exception;
}
