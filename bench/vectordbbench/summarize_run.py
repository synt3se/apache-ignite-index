"""Сводка прогона: ошибки клиента, серверные счётчики неполных ответов и конфигурация стенда.

    python bench/vectordbbench/summarize_run.py --errors vindex-client-errors.jsonl \
        --metrics http://127.0.0.1:8081/actuator/prometheus --vdbbench <клон VectorDBBench> [--qdrant URL COLLECTION]

Для сравнительного baseline нужны нулевые ошибки, таймауты и неполные ответы; иначе результат
помечается. Конкурентный runner VectorDBBench лишь логирует исключения поиска, поэтому ошибки
считаются и на клиенте (запрос, не дошедший до сервера, серверный счётчик не увидит).
"""

import argparse
import collections
import json
import subprocess
import urllib.request
from pathlib import Path


def client_errors(path: Path) -> dict:
    counts = collections.Counter()
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                counts[json.loads(line)["kind"]] += 1
    return dict(counts)


def server_counters(url: str) -> dict:
    text = urllib.request.urlopen(url, timeout=10).read().decode()
    wanted = ("vindex_search_total", "vindex_search_partial_total")
    out = {}
    for line in text.splitlines():
        for name in wanted:
            if line.startswith(name):
                out[name] = float(line.rsplit(" ", 1)[1])
    return out


def qdrant_collection(url: str, collection: str) -> dict:
    """Фактическая конфигурация созданной коллекции (а не подписи параметров запуска)."""
    with urllib.request.urlopen(f"{url.rstrip('/')}/collections/{collection}", timeout=10) as response:
        return json.loads(response.read())["result"]["config"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--errors", default="vindex-client-errors.jsonl")
    parser.add_argument("--metrics")
    parser.add_argument("--vdbbench")
    parser.add_argument("--qdrant", nargs=2, metavar=("URL", "COLLECTION"))
    args = parser.parse_args()
    summary = {"client_errors": client_errors(Path(args.errors))}
    if args.metrics:
        summary["server"] = server_counters(args.metrics)
    if args.vdbbench:
        summary["vectordbbench_commit"] = subprocess.run(["git", "-C", args.vdbbench, "rev-parse", "HEAD"],
                                                         capture_output=True, text=True, check=True).stdout.strip()
    if args.qdrant:
        summary["qdrant_collection_config"] = qdrant_collection(*args.qdrant)
    errors = sum(summary["client_errors"].values()) + int(summary.get("server", {}).get("vindex_search_partial_total", 0))
    summary["clean"] = errors == 0
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
