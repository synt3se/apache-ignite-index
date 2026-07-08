package ru.nsu.fit.vector.node.compute;

import org.apache.ignite.lang.IgniteRunnable;
import ru.nsu.fit.vector.node.index.NodeLocalVectorIndexHolder;


public class ClearLocalIndexJob implements IgniteRunnable {
    @Override
    public void run() {
        NodeLocalVectorIndexHolder.clear();
    }
}