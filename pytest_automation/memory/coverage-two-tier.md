# 覆盖率两段式

静态：端点→Controller/Service/跨上下文/Mapper 表（call-graph）+ 用例映射 + 写接口 DB 断言 + 方法校验，PR 用。
动态：JaCoCo 0.8.12，`scripts/start_backend_jacoco.sh` 以 `output=tcpserver,port=6300` 启动后端，`jacoco_dump.sh --reset` 落盘，`jacoco_report.py` 生成 CSV/XML/HTML 并把 summary 注入 `coverage.json#runtime`。
限制：collector 是所有测试的总和，接口级行覆盖需要逐接口 reset（未默认开启）；必须用同一次构建的 jar。
