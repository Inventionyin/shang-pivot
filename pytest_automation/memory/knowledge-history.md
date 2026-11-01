# 知识库与历史

运行归档：`scripts/archive_run.py` → `artifacts/history/<run_id>/`（manifest/results/mapping/coverage/registry）。
跨轮 diff：`scripts/case_history.py` 输出新增/删除/修改/结果迁移/覆盖变化；报告「用例历史」页消费 `ext/data/history.json`。
知识库：`scripts/build_knowledge_index.py`（SQLite FTS5）+ `scripts/kb_search.py`，记录带 git commit/baseline。
