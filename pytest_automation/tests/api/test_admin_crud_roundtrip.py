"""管理端 CRUD 正向往返：创建 → 列表可见 → 更新 → 删除 → 列表不可见。

补齐「写接口缺少正向用例」的空白：巡检只能证明拒绝行为正确，这里证明功能链路可用。
覆盖资源：品牌、类目、字典、部门、仓库（请求体最小字段来自控制器源码校验）。
"""
from __future__ import annotations

import pytest

from core.assertions import ok
from helpers import db
from helpers.db_contract import verify_deleted, verify_write

pytestmark = [pytest.mark.api, pytest.mark.regression]


@pytest.fixture(scope="module")
def category_id():
    if not db.available():
        pytest.skip("数据库不可达：品牌需要引用一个已有类目")
    row = db.query_one(
        "SELECT id FROM commerce.category WHERE deleted = FALSE ORDER BY id LIMIT 1"
    )
    assert row, "演示数据缺少类目"
    return str(row["id"])


def _list_records(admin, path: str) -> list[dict]:
    data = ok(admin.get(path, params={"page": 1, "size": 100}))
    if isinstance(data, list):  # 类目/部门/仓库返回数组而非分页对象
        return data
    return (data or {}).get("records") or []


@pytest.mark.P0
def test_brand_crud_roundtrip(admin, category_id):
    """品牌：创建 → 列表可见 → 更新 → 删除。"""
    name = "自动化品牌-往返-01"
    created = ok(
        admin.post("/admin/brands", json={"name": name, "categoryId": category_id})
    )
    brand_id = str(created)

    records = _list_records(admin, "/admin/brands")
    assert brand_id in {str(row["id"]) for row in records}, "新建品牌未出现在列表"

    update_payload = {"name": name + "-改", "categoryId": category_id, "status": "ENABLED"}
    ok(admin.put(f"/admin/brands/{brand_id}", json=update_payload))
    verify_write("PUT /admin/brands/{id}", update_payload, path={"id": brand_id})
    records = _list_records(admin, "/admin/brands")
    updated = next(row for row in records if str(row["id"]) == brand_id)
    assert updated["name"].endswith("-改"), "品牌更新未生效"

    ok(admin.delete(f"/admin/brands/{brand_id}"))
    verify_deleted("DELETE /admin/brands/{id}", path={"id": brand_id})
    records = _list_records(admin, "/admin/brands")
    assert brand_id not in {str(row["id"]) for row in records}, "品牌删除未生效"


@pytest.mark.P0
def test_category_crud_roundtrip(admin):
    """类目：创建 → 列表可见 → 更新 → 删除。"""
    name = "自动化类目-往返-01"
    created = ok(admin.post("/admin/categories", json={"name": name, "parentId": "0"}))
    category = str(created)

    records = _list_records(admin, "/admin/categories")
    assert category in {str(row["id"]) for row in records}, "新建类目未出现在列表"

    update_payload = {"name": name + "-改", "parentId": "0"}
    ok(admin.put(f"/admin/categories/{category}", json=update_payload))
    verify_write("PUT /admin/categories/{id}", update_payload, path={"id": category})
    records = _list_records(admin, "/admin/categories")
    updated = next(row for row in records if str(row["id"]) == category)
    assert updated["name"].endswith("-改"), "类目更新未生效"

    ok(admin.delete(f"/admin/categories/{category}"))
    verify_deleted("DELETE /admin/categories/{id}", path={"id": category})


@pytest.mark.P0
def test_dict_crud_roundtrip(admin):
    """字典：创建 → 列表可见 → 更新 → 删除。"""
    code = "auto_dict_roundtrip"
    created = ok(
        admin.post("/admin/dicts", json={"code": code, "name": "自动化字典-往返"})
    )
    dict_id = str(created)

    records = _list_records(admin, "/admin/dicts")
    assert dict_id in {str(row["id"]) for row in records}, "新建字典未出现在列表"

    update_payload = {"code": code, "name": "自动化字典-改"}
    ok(admin.put(f"/admin/dicts/{dict_id}", json=update_payload))
    verify_write("PUT /admin/dicts/{id}", update_payload, path={"id": dict_id})
    records = _list_records(admin, "/admin/dicts")
    updated = next(row for row in records if str(row["id"]) == dict_id)
    assert updated["name"].endswith("-改"), "字典更新未生效"

    ok(admin.delete(f"/admin/dicts/{dict_id}"))
    verify_deleted("DELETE /admin/dicts/{id}", path={"id": dict_id})


@pytest.mark.P1
def test_dept_crud_roundtrip(admin):
    """部门：创建 → 列表可见 → 更新 → 删除。"""
    name = "自动化部门-往返-01"
    created = ok(admin.post("/admin/depts", json={"name": name, "parentId": "0"}))
    dept_id = str(created)

    records = _list_records(admin, "/admin/depts")
    assert dept_id in {str(row["id"]) for row in records}, "新建部门未出现在列表"

    update_payload = {"name": name + "-改", "parentId": "0"}
    ok(admin.put(f"/admin/depts/{dept_id}", json=update_payload))
    verify_write("PUT /admin/depts/{id}", update_payload, path={"id": dept_id})
    records = _list_records(admin, "/admin/depts")
    updated = next(row for row in records if str(row["id"]) == dept_id)
    assert updated["name"].endswith("-改"), "部门更新未生效"

    ok(admin.delete(f"/admin/depts/{dept_id}"))
    verify_deleted("DELETE /admin/depts/{id}", path={"id": dept_id})


@pytest.mark.P1
def test_warehouse_crud_roundtrip(admin):
    """仓库：创建 → 列表可见 → 更新 → 删除。"""
    name = "自动化仓库-往返-01"
    created = ok(admin.post("/admin/warehouses", json={"name": name}))
    warehouse_id = str(created)

    records = _list_records(admin, "/admin/warehouses")
    assert warehouse_id in {str(row["id"]) for row in records}, "新建仓库未出现在列表"

    update_payload = {"name": name + "-改"}
    ok(admin.put(f"/admin/warehouses/{warehouse_id}", json=update_payload))
    verify_write("PUT /admin/warehouses/{id}", update_payload, path={"id": warehouse_id})
    records = _list_records(admin, "/admin/warehouses")
    updated = next(row for row in records if str(row["id"]) == warehouse_id)
    assert updated["name"].endswith("-改"), "仓库更新未生效"
    # 仓库无删除接口（DELETE 返回 500，见 D-22）：数据由基线恢复清理
