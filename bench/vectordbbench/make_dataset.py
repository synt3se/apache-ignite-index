"""Пользовательский набор для VectorDBBench (PerformanceCustomDataset) с точным ground truth.

    python bench/vectordbbench/make_dataset.py synthetic <dir> --size 10000 --dim 128 --queries 200 --metric COSINE
    python bench/vectordbbench/make_dataset.py csv <dir> --csv vectors.csv --queries 1000 --metric COSINE

Файлы: train.parquet (id:int64, emb:list<float32>), test.parquet (id, emb),
neighbors.parquet (id, neighbors_id:list<int64>, 100 ближайших по исходным ID и той же метрике).
synthetic — случайные векторы с фиксированным seed; csv — формат проекта «id;url;embedding;metadata»
(экспорт /vectors/save), запросы отбираются из набора и исключаются из train.
Точный перебор — numpy, блоками; COSINE считается по нормализованным копиям, L2 — квадрат
евклидова расстояния (порядок тот же).
"""

import argparse
import json
from pathlib import Path

import numpy as np
import polars as pl

GT_WIDTH = 100


def exact_neighbors(train: np.ndarray, queries: np.ndarray, metric: str, width: int) -> np.ndarray:
    if metric == "COSINE":
        def unit(x: np.ndarray) -> np.ndarray:
            norms = np.linalg.norm(x, axis=1, keepdims=True)
            return x / np.where(norms == 0, 1, norms)
        train, queries = unit(train), unit(queries)
    out = np.empty((len(queries), width), dtype=np.int64)
    for start in range(0, len(queries), 64):
        q = queries[start:start + 64]
        if metric == "COSINE":
            distance = 1.0 - q @ train.T
        else:
            distance = (q * q).sum(1, keepdims=True) - 2.0 * (q @ train.T) + (train * train).sum(1)
        part = np.argpartition(distance, width, axis=1)[:, :width]
        order = np.take_along_axis(distance, part, axis=1).argsort(axis=1, kind="stable")
        out[start:start + len(q)] = np.take_along_axis(part, order, axis=1)
    return out


def write(out_dir: Path, ids: np.ndarray, train: np.ndarray, query_ids: np.ndarray, queries: np.ndarray,
          metric: str) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    gt_positions = exact_neighbors(train.astype(np.float64), queries.astype(np.float64), metric, GT_WIDTH)
    pl.DataFrame({"id": ids, "emb": [row for row in train.astype(np.float32)]}).write_parquet(out_dir / "train.parquet")
    pl.DataFrame({"id": query_ids, "emb": [row for row in queries.astype(np.float32)]}).write_parquet(
        out_dir / "test.parquet")
    pl.DataFrame({"id": query_ids, "neighbors_id": [ids[row] for row in gt_positions]}).write_parquet(
        out_dir / "neighbors.parquet")
    meta = {"size": int(len(ids)), "dim": int(train.shape[1]), "metric": metric, "queries": int(len(queries)),
            "gt_width": GT_WIDTH}
    (out_dir / "dataset.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(json.dumps(meta))


def synthetic(args: argparse.Namespace) -> None:
    rng = np.random.default_rng(args.seed)
    train = rng.standard_normal((args.size, args.dim), dtype=np.float32)
    queries = rng.standard_normal((args.queries, args.dim), dtype=np.float32)
    ids = np.arange(1, args.size + 1, dtype=np.int64) * 7  # внешние ID, не совпадающие с позициями
    write(Path(args.dir), ids, train, np.arange(args.queries, dtype=np.int64), queries, args.metric)


def from_csv(args: argparse.Namespace) -> None:
    ids, vectors = [], []
    with open(args.csv, encoding="utf-8") as source:
        next(source)  # заголовок id;url;embedding;metadata
        for line in source:
            if not line.strip():
                continue
            first = line.index(";")
            second = line.index(";", first + 1)
            third = line.index(";", second + 1)
            ids.append(int(line[:first]))
            vectors.append(np.array(json.loads(line[second + 1:third]), dtype=np.float32))
    ids = np.array(ids, dtype=np.int64)
    data = np.stack(vectors)
    rng = np.random.default_rng(args.seed)
    held_out = rng.choice(len(ids), size=args.queries, replace=False)
    keep = np.ones(len(ids), dtype=bool)
    keep[held_out] = False
    write(Path(args.dir), ids[keep], data[keep], ids[held_out], data[held_out], args.metric)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=["synthetic", "csv"])
    parser.add_argument("dir")
    parser.add_argument("--size", type=int, default=10_000)
    parser.add_argument("--dim", type=int, default=128)
    parser.add_argument("--queries", type=int, default=200)
    parser.add_argument("--metric", choices=["COSINE", "L2"], default="COSINE")
    parser.add_argument("--csv")
    parser.add_argument("--seed", type=int, default=20261021)
    args = parser.parse_args()
    synthetic(args) if args.mode == "synthetic" else from_csv(args)


if __name__ == "__main__":
    main()
