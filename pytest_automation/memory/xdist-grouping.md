# xdist 并发口径

API 并行默认 `--dist loadgroup`，conftest 按主体接口动态加 `xdist_group=ep::<接口>`，保证同接口用例同 worker 顺序执行、接口之间并行。
UI 用 `-n 3 --dist loadscope`，每 worker 独立浏览器；会员由 `customer` 夹具随机注册。
验收硬标准：并行与串行的通过/失败/跳过必须完全一致（当前 605/93/3）。
