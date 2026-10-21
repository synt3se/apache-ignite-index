from typing import Annotated, Unpack

import click

from ....cli.cli import (
    CommonTypedDict,
    cli,
    click_parameter_decorators_from_typed_dict,
    run,
)
from .. import DB


class IgniteVindexTypedDict(CommonTypedDict):
    url: Annotated[str, click.option("--url", type=str, default="http://127.0.0.1:8081",
                                     help="index-vector-server REST url")]
    index: Annotated[str, click.option("--index", type=str, required=True,
                                       help="indexId configured statically in the node XML")]
    ef: Annotated[int, click.option("--ef", type=int, default=None,
                                    help="JVector search width ef (>= k); omit for Flat")]
    exact: Annotated[bool, click.option("--exact/--no-exact", default=False, help="exact search")]
    optimize: Annotated[bool, click.option("--optimize/--no-optimize", default=True,
                                           help="barrier waits for built structures on every partition")]
    search_timeout_ms: Annotated[int, click.option("--search-timeout-ms", type=int, default=10_000,
                                                   help="server-side budget of one search")]
    barrier_timeout_s: Annotated[float, click.option("--barrier-timeout-s", type=float, default=3_600.0,
                                                     help="how long optimize() waits for the load barrier")]


@cli.command()
@click_parameter_decorators_from_typed_dict(IgniteVindexTypedDict)
def IgniteVindex(**parameters: Unpack[IgniteVindexTypedDict]):
    from .config import IgniteVindexConfig, IgniteVindexIndexConfig

    run(
        db=DB.IgniteVindex,
        db_config=IgniteVindexConfig(db_label=parameters["db_label"], url=parameters["url"],
                                     index=parameters["index"]),
        db_case_config=IgniteVindexIndexConfig(
            ef=parameters["ef"],
            exact=parameters["exact"],
            optimize=parameters["optimize"],
            search_timeout_ms=parameters["search_timeout_ms"],
            barrier_timeout_s=parameters["barrier_timeout_s"],
        ),
        **parameters,
    )
