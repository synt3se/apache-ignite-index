"""Конфигурация клиента индекса на Apache Ignite (REST сервера index-vector-server)."""

from pydantic import BaseModel

from ..api import DBCaseConfig, DBConfig, MetricType


class IgniteVindexConfig(DBConfig):
    """Подключение: REST сервера и индекс, статически настроенный в XML узлов (DDL нет)."""

    url: str = "http://127.0.0.1:8081"
    index: str
    request_timeout_s: float = 60.0

    def to_dict(self) -> dict:
        return {"url": self.url.rstrip("/"), "index": self.index, "request_timeout_s": self.request_timeout_s}


class IgniteVindexIndexConfig(BaseModel, DBCaseConfig):
    """Параметры кейса.

    ef — ширина поиска JVector (передаётся явно; у Flat параметров нет); exact — точный перебор;
    optimize — барьер ждёт построения структур всех партиций (False — отдельный профиль
    измерений без оптимизации).
    """

    metric_type: MetricType | None = None
    ef: int | None = None
    exact: bool = False
    optimize: bool = True
    search_timeout_ms: int = 10_000
    barrier_timeout_s: float = 3_600.0
    barrier_poll_s: float = 0.5

    def index_param(self) -> dict:
        return {"metric_type": self.metric_type, "optimize": self.optimize}

    def search_param(self) -> dict:
        params = {"ef": self.ef} if self.ef else {}
        return {"exact": self.exact, "params": params, "timeoutMs": self.search_timeout_ms}
