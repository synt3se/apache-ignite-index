"""Клиент VectorDBBench для производного векторного индекса на Apache Ignite.

Контракт стенда (VectorDBBench v2.0.0):
- insert_embeddings — пакетная запись Long → float[] через REST (весь батч проверяется сервером
  до записи; повтор того же батча безопасен);
- optimize — барьер загрузки: вызывается стендом после возврата всех insert_embeddings, то есть
  после подтверждения всех записей. Сервер дожидается завершения принятых применений, выдаёт
  тикеты сверки, ждёт скан после тикетов и (optimize) принудительную сборку. RESTART — новый
  барьер, FAILED — ошибка. Дополнительно проверяется liveVectors == data_size;
- search_embedding — строгий поиск (allowPartial=false): неполный ответ — исключение, а не
  частичный результат. Каждая ошибка поиска записывается в журнал клиента (VINDEX_ERROR_LOG),
  потому что конкурентный runner стенда лишь логирует исключения.
"""

import json
import logging
import os
import threading
import time
from contextlib import contextmanager
from typing import Any

import requests

from ..api import MetricType, NonRetryableInsertError, VectorDB
from .config import IgniteVindexIndexConfig

log = logging.getLogger(__name__)

METRICS = {MetricType.COSINE: "COSINE", MetricType.L2: "SQUARED_L2"}
ERROR_LOG = os.environ.get("VINDEX_ERROR_LOG", "vindex-client-errors.jsonl")
_error_lock = threading.Lock()


class SearchIncompleteError(RuntimeError):
    """Ответ не COMPLETE или ошибка HTTP: результат не считается."""


def record_client_error(kind: str, detail: str) -> None:
    """Строка в журнал ошибок клиента: виден и запрос, который не дошёл до сервера."""
    line = json.dumps({"ts": time.time(), "pid": os.getpid(), "kind": kind, "detail": detail[:500]})
    with _error_lock, open(ERROR_LOG, "a", encoding="utf-8") as out:
        out.write(line + "\n")


class IgniteVindex(VectorDB):
    name = "IgniteVindex"
    thread_safe = True  # у каждого потока своя HTTP-сессия

    def __init__(
        self,
        dim: int,
        db_config: dict,
        db_case_config: IgniteVindexIndexConfig,
        collection_name: str = "IgniteVindex",
        drop_old: bool = False,
        **kwargs: Any,
    ):
        self.url = db_config["url"]
        self.index = db_config["index"]
        self.timeout = db_config["request_timeout_s"]
        self.case_config = db_case_config
        self._local = None

        info = self._index_info()
        expected_metric = METRICS.get(db_case_config.metric_type)
        if info["dimension"] != dim or (expected_metric and info["metric"] != expected_metric):
            msg = (f"index {self.index} is {info['dimension']}/{info['metric']}, dataset is {dim}/"
                   f"{db_case_config.metric_type}: configure a matching index in the node XML")
            raise ValueError(msg)
        if info.get("field"):
            msg = f"index {self.index} reads field '{info['field']}': batch upload needs an index without a field"
            raise ValueError(msg)
        if drop_old:
            response = requests.post(f"{self.url}/indexes/{self.index}/truncate", timeout=self.timeout)
            body = response.json()
            if response.status_code != 200 or not body.get("confirmed"):
                msg = f"truncate of {self.index} not confirmed ({response.status_code}): {body}"
                raise RuntimeError(msg)
            log.info(f"truncated index {self.index}: {body}")

    def _index_info(self) -> dict:
        response = requests.get(f"{self.url}/indexes", timeout=self.timeout)
        response.raise_for_status()
        for info in response.json():
            if info["indexId"] == self.index:
                return info
        msg = f"index {self.index} is not configured on the cluster"
        raise ValueError(msg)

    def _session(self) -> requests.Session:
        local = self._local
        if local is None:
            local = self._local = threading.local()
        if not hasattr(local, "session"):
            local.session = requests.Session()
        return local.session

    @contextmanager
    def init(self):
        self._local = threading.local()
        try:
            yield
        finally:
            self._local = None

    def need_normalize_cosine(self) -> bool:
        return False  # COSINE нормализуется в индексе при приёме

    def insert_embeddings(
        self,
        embeddings: list[list[float]],
        metadata: list[int],
        **kwargs: Any,
    ) -> tuple[int, Exception | None]:
        body = {"ids": [int(i) for i in metadata],
                "vectors": [list(map(float, e)) for e in embeddings]}
        try:
            response = self._session().put(f"{self.url}/indexes/{self.index}/vectors", json=body,
                                           timeout=self.timeout)
        except requests.RequestException as e:
            return 0, e  # стенд повторит батч целиком: запись идемпотентна
        if response.status_code == 200:
            return response.json()["written"], None
        if 400 <= response.status_code < 500:
            return 0, NonRetryableInsertError(f"upload rejected ({response.status_code}): {response.text[:300]}")
        return 0, RuntimeError(f"upload failed ({response.status_code}): {response.text[:300]}")

    def optimize(self, data_size: int | None = None):
        deadline = time.monotonic() + self.case_config.barrier_timeout_s
        optimize = "true" if self.case_config.optimize else "false"
        restarts = 0
        while time.monotonic() < deadline:
            barrier = self._post(f"/indexes/{self.index}/barriers?optimize={optimize}")
            while barrier["state"] in ("STARTED", "IN_PROGRESS") and time.monotonic() < deadline:
                time.sleep(self.case_config.barrier_poll_s)
                barrier = self._post(f"/indexes/{self.index}/barriers/{barrier['barrierId']}/progress")
            state = barrier["state"]
            if state == "COMPLETE":
                live = barrier["liveVectors"]
                log.info(f"barrier {barrier['barrierId']} complete: live={live}, "
                         f"optimized={barrier['optimizedPartitions']}/{barrier['expectedPartitions']}, "
                         f"restarts={restarts}")
                if data_size is not None and live != data_size:
                    msg = f"barrier complete but index has {live} vectors, dataset declares {data_size}"
                    raise RuntimeError(msg)
                return
            if state == "RESTART":
                restarts += 1
                log.info(f"barrier restart #{restarts}: {barrier.get('message')}")
                time.sleep(self.case_config.barrier_poll_s)
                continue
            if state == "FAILED":
                msg = f"barrier failed: {barrier.get('message')}"
                raise RuntimeError(msg)
        msg = f"barrier did not complete within {self.case_config.barrier_timeout_s} s"
        raise TimeoutError(msg)

    def _post(self, path: str) -> dict:
        response = requests.post(f"{self.url}{path}", timeout=self.timeout)
        response.raise_for_status()
        return response.json()

    def search_embedding(self, query: list[float], k: int = 100, **kwargs: Any) -> list[int]:
        body = {"vector": list(map(float, query)), "k": k, "allowPartial": False,
                **self.case_config.search_param()}
        try:
            response = self._session().post(f"{self.url}/indexes/{self.index}/search", json=body,
                                            timeout=self.timeout)
        except requests.RequestException as e:
            record_client_error("transport", repr(e))
            raise
        if response.status_code != 200:
            detail = f"HTTP {response.status_code}: {response.text[:300]}"
            record_client_error("http", detail)
            raise SearchIncompleteError(detail)
        result = response.json()
        if result["status"] != "COMPLETE":
            detail = f"{result['status']} {result.get('incomplete')} timedOut={result.get('timedOut')}"
            record_client_error("incomplete", detail)
            raise SearchIncompleteError(detail)
        return [int(hit["key"]) for hit in result["hits"]]
