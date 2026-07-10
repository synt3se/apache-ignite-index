package ru.nsu.fit.vector.node.index;

/// Этот класс мне нужен чисто для теста, в проекте он не нужен
/// Если вы его увидели, то я косячник, надо удалить///



public class LocalTest {
    public static void main(String[] args){
        PartitionVectorIndex index = new JVectorPartitionIndex(3);

        index.add(1L, new float[]{1f, 0f, 0f});
        index.add(2L, new float[]{0f, 1f, 0f});
        index.add(3L, new float[]{0.9f, 0.1f, 0f});

        System.out.println("Start");
        var result = index.search(new float[]{1f, 0f, 0f}, 3);

        for (var r : result){
            System.out.println(r.id() + " " + r.distance());
        }
    }
}
