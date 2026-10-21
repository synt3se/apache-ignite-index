"""Устанавливает клиент IgniteVindex в закреплённый VectorDBBench.

    python bench/vectordbbench/install_client.py <путь к клону VectorDBBench>

Клон должен быть на теге v2.0.0 (коммит ниже): клиент проверен на нём, смешивать с движущимся
main нельзя. Скрипт копирует пакет ignite_vindex в vectordb_bench/backend/clients/ и регистрирует
его в enum DB, фабриках конфигураций и CLI. Повторный запуск ничего не дублирует.
"""

import shutil
import subprocess
import sys
from pathlib import Path

PINNED_COMMIT = "f50544230470770e711126f48d9a077b9cb62ff1"  # zilliztech/VectorDBBench v2.0.0
MARK = "IgniteVindex"


def patch(path: Path, anchor: str, insert: str, before: bool = True) -> None:
    text = path.read_text(encoding="utf-8")
    if insert in text:
        return
    if text.count(anchor) != 1:
        sys.exit(f"{path}: anchor not found exactly once: {anchor!r}")
    text = text.replace(anchor, insert + anchor if before else anchor + insert)
    path.write_text(text, encoding="utf-8", newline="\n")


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    root = Path(sys.argv[1]).resolve()
    commit = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True, text=True,
                            check=True).stdout.strip()
    if commit != PINNED_COMMIT:
        sys.exit(f"VectorDBBench at {commit}, expected v2.0.0 {PINNED_COMMIT}")

    clients = root / "vectordb_bench" / "backend" / "clients"
    target = clients / "ignite_vindex"
    shutil.copytree(Path(__file__).parent / "ignite_vindex", target, dirs_exist_ok=True)

    init = clients / "__init__.py"
    patch(init, '    Adbpg = "AnalyticDB for PostgreSQL"\n', f'    {MARK} = "{MARK}"\n', before=False)
    patch(init, "        if self == DB.Test:\n            from .test.test import Test\n",
          "        if self == DB.IgniteVindex:\n"
          "            from .ignite_vindex.ignite_vindex import IgniteVindex\n\n"
          "            return IgniteVindex\n\n")
    patch(init, "        if self == DB.Test:\n            from .test.config import TestConfig\n",
          "        if self == DB.IgniteVindex:\n"
          "            from .ignite_vindex.config import IgniteVindexConfig\n\n"
          "            return IgniteVindexConfig\n\n")
    patch(init, "        return EmptyDBCaseConfig\n",
          "        if self == DB.IgniteVindex:\n"
          "            from .ignite_vindex.config import IgniteVindexIndexConfig\n\n"
          "            return IgniteVindexIndexConfig\n\n")

    cli = root / "vectordb_bench" / "cli" / "vectordbbench.py"
    patch(cli, "from .batch_cli import BatchCli\n",
          "from ..backend.clients.ignite_vindex.cli import IgniteVindex\n")
    patch(cli, "cli.add_command(VolcMySQLHNSW)\n", "cli.add_command(IgniteVindex)\n", before=False)
    print(f"IgniteVindex client installed into {root} (VectorDBBench {commit[:7]})")


if __name__ == "__main__":
    main()
