package ru.nsu.fit.sberlab.vectorindex.node.index;

import java.io.Serializable;

public class JVectorProperties implements Serializable {

    private int m;
    private int efConstruction;

    private int efSearch;
    private float neighborOverflow;
    private float alpha;
    private boolean addHierarchy;
    private boolean refineFinalGraph;
    private double addRebuildRatio;
    private double deleteRebuildRatio;
    private int minAdditionsBeforeRebuild;



    public JVectorProperties() {
    }

    public int m() {
        return m;
    }

    public void setM(int m) {
        this.m = m;
    }

    public int efConstruction() {
        return efConstruction;
    }

    public void setEfConstruction(int efConstruction) {
        this.efConstruction = efConstruction;
    }

    public float neighborOverflow() {
        return neighborOverflow;
    }

    public void setNeighborOverflow(float neighborOverflow) {
        this.neighborOverflow = neighborOverflow;
    }

    public float alpha() {
        return alpha;
    }

    public void setAlpha(float alpha) {
        this.alpha = alpha;
    }

    public boolean addHierarchy() {
        return addHierarchy;
    }

    public void setAddHierarchy(boolean addHierarchy) {
        this.addHierarchy = addHierarchy;
    }

    public boolean refineFinalGraph() {
        return refineFinalGraph;
    }

    public void setRefineFinalGraph(boolean refineFinalGraph) {
        this.refineFinalGraph = refineFinalGraph;
    }

    public double addRebuildRatio() {
        return addRebuildRatio;
    }

    public void setAddRebuildRatio(double addRebuildRatio) {
        this.addRebuildRatio = addRebuildRatio;
    }

    public double deleteRebuildRatio() {
        return deleteRebuildRatio;
    }

    public void setDeleteRebuildRatio(double deleteRebuildRatio) {
        this.deleteRebuildRatio = deleteRebuildRatio;
    }

    public int minAdditionsBeforeRebuild() {
        return minAdditionsBeforeRebuild;
    }

    public void setMinAdditionsBeforeRebuild(int minAdditionsBeforeRebuild) {
        this.minAdditionsBeforeRebuild = minAdditionsBeforeRebuild;
    }

    public void setEfSearch(int efSearch) {
        this.efSearch = efSearch;
    }

    public int getEfSearch() {
        return efSearch;
    }
}