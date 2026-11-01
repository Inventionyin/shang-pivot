PYTHON ?= python3

.PHONY: help api ui smoke full report clean api-parallel api-parallel-publish api-fast

help:
	@echo "make api      只跑接口用例"
	@echo "make ui       只跑浏览器用例（需要 ADMIN_PASSWORD 才能跑后台用例）"
	@echo "make smoke    只跑冒烟用例"
	@echo "make full     全量（API + UI + E2E）"
	@echo "make api-parallel          两阶段并行（分片+serial lane，结果与串行一致）"
	@echo "make api-parallel-publish  两阶段并行并发布到报告（保留 UI 结果）"
	@echo "make api-fast              朴素并行 -n 4（最快，有共享状态竞争）"
	@echo "make report   用 allure 生成 HTML 报告"
	@echo "make clean    清理 pytest 缓存与运行产物"

api:
	$(PYTHON) -m pytest -m api -q --alluredir=artifacts/allure --clean-alluredir

ui:
	$(PYTHON) -m pytest -m ui -q --alluredir=artifacts/allure --clean-alluredir

smoke:
	$(PYTHON) -m pytest -m smoke -q --alluredir=artifacts/allure --clean-alluredir

full:
	$(PYTHON) -m pytest -q --alluredir=artifacts/allure --clean-alluredir

api-parallel:
	bash scripts/run_parallel.sh --workers 4

api-parallel-publish:
	bash scripts/run_parallel.sh --workers 4 --publish

api-fast:
	DISABLE_RECORDER=1 DISABLE_DB_GUARD=1 \
	$(PYTHON) -m pytest -m api -q -n 4 \
	--alluredir=artifacts/allure --clean-alluredir

report:
	bash scripts/allure_report.sh

clean:
	rm -rf .pytest_cache .pytest_tmp artifacts
