"""数据库只读访问层：集中管理 SQL，避免用例散落实现细节。

定位（测试方案 §6 Oracle L5）：只用于「API 看不到的内部一致性」断言——
库存守恒、流水、金额拆解、基线指纹。业务语义断言优先走接口。

连接默认对齐 backend/docker-compose.yml；可用 DB_* 环境变量覆盖。
"""
from __future__ import annotations

import os

import psycopg2
import psycopg2.extras

from core.logger import get_logger

log = get_logger("db")


def _conn_params() -> dict:
    return {
        "host": os.getenv("DB_HOST", "127.0.0.1"),
        "port": int(os.getenv("DB_PORT", "5432")),
        "dbname": os.getenv("DB_NAME", "shang"),
        "user": os.getenv("DB_USER", "shang"),
        "password": os.getenv("DB_PASSWORD", "shang"),
        "connect_timeout": 5,
    }


def query(sql: str, params: tuple = None) -> list[dict]:
    """执行只读 SQL，返回 dict 列表。"""
    with psycopg2.connect(**_conn_params()) as conn:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(sql, params)
            return [dict(row) for row in cur.fetchall()]


def query_one(sql: str, params: tuple = None) -> dict | None:
    rows = query(sql, params)
    return rows[0] if rows else None


def available() -> bool:
    """数据库是否可达；不可达时相关用例由夹具 skip 并说明原因。"""
    try:
        query("SELECT 1")
        return True
    except Exception as exc:  # noqa: BLE001
        log.warning("数据库不可达：%s", exc)
        return False


# ---------- 业务查询 ----------

def stock_of(sku_id: str) -> dict | None:
    """库存三态：physical / locked / available(=physical-locked)。"""
    return query_one(
        """
        SELECT sku_id, physical_stock, locked_stock,
               physical_stock - locked_stock AS available_stock, version
          FROM commerce.stock
         WHERE sku_id = %s AND deleted = FALSE
        """,
        (str(sku_id),),
    )


def order_row(order_id: str) -> dict | None:
    return query_one(
        """
        SELECT id, order_no, customer_id, status, total_amount, pay_amount,
               freight, idempotent_key
          FROM commerce."order"
         WHERE id = %s AND deleted = FALSE
        """,
        (str(order_id),),
    )


def order_items(order_id: str) -> list[dict]:
    return query(
        """
        SELECT sku_id, price, quantity, subtotal
          FROM commerce.order_item
         WHERE order_id = %s AND deleted = FALSE
        """,
        (str(order_id),),
    )


def stock_log_count(sku_id: str) -> int:
    row = query_one(
        "SELECT count(*) AS n FROM commerce.stock_log WHERE sku_id = %s",
        (str(sku_id),),
    )
    return int(row["n"]) if row else 0


def pick_on_sale_sku(min_available: int = 2, shard: bool | None = None) -> dict | None:
    """从库中挑一个在售、可售库存充足的 SKU（DB 驱动的用例数据源）。

    并行执行时按 worker 分片（sku_id % worker_count == worker_index），
    保证不同 worker 操作互不重叠的库存行；串行或 PARALLEL_SHARDING=0 时行为不变。
    """
    from helpers import parallel

    use_shard = parallel.should_shard() if shard is None else shard
    shard_sql = ""
    params: list = [min_available]
    if use_shard:
        shard_sql = " AND mod(s.sku_id, %s) = %s"
        params += [parallel.worker_count(), parallel.worker_index()]
    return query_one(
        f"""
        SELECT s.sku_id, s.physical_stock, s.locked_stock,
               s.physical_stock - s.locked_stock AS available_stock,
               k.price, k.spu_id
          FROM commerce.stock s
          JOIN commerce.sku k ON k.id = s.sku_id AND k.deleted = FALSE
          JOIN commerce.spu p ON p.id = k.spu_id AND p.deleted = FALSE
         WHERE s.deleted = FALSE
           AND p.status = 'ON' AND k.status = 'ENABLED'
           AND s.physical_stock - s.locked_stock >= %s
           {shard_sql}
         ORDER BY s.sku_id
         LIMIT 1
        """,
        tuple(params),
    )


def sku_detail(sku_id: str) -> dict | None:
    """按 SKU 返回接口用例需要的字段（价格/SPU/可售库存）。"""
    return query_one(
        """
        SELECT k.id AS sku_id, k.spu_id, k.price, k.title,
               s.physical_stock - s.locked_stock AS available_stock
          FROM commerce.sku k
          JOIN commerce.stock s ON s.sku_id = k.id AND s.deleted = FALSE
         WHERE k.id = %s AND k.deleted = FALSE
        """,
        (str(sku_id),),
    )


# ---------- 基线指纹 ----------

_FINGERPRINT_TABLES = [
    ("commerce", "spu"),
    ("commerce", "sku"),
    ("commerce", "stock"),
    ("commerce", "brand"),
    ("commerce", "category"),
    ("identity", "customer"),
    ("identity", "admin_user"),
]


def fingerprint() -> dict:
    """关键表行数 + 库存总量：用于执行前后污染对比，不作为绝对断言。"""
    result: dict = {}
    for schema, table in _FINGERPRINT_TABLES:
        row = query_one(
            f'SELECT count(*) AS n FROM {schema}."{table}" WHERE deleted = FALSE'
        )
        result[f"{schema}.{table}"] = int(row["n"]) if row else -1
    stock = query_one(
        """
        SELECT coalesce(sum(physical_stock), 0) AS physical,
               coalesce(sum(locked_stock), 0)   AS locked
          FROM commerce.stock WHERE deleted = FALSE
        """
    )
    result["commerce.stock.physical_sum"] = int(stock["physical"])
    result["commerce.stock.locked_sum"] = int(stock["locked"])
    return result


def diff(before: dict, after: dict) -> dict:
    changed = {
        key: {"before": before.get(key), "after": after.get(key)}
        for key in sorted(set(before) | set(after))
        if before.get(key) != after.get(key)
    }
    return changed
