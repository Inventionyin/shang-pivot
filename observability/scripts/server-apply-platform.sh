#!/usr/bin/env bash
# 在服务器（111.170.33.2）上把尚品汇接入已有的监控平台。以 root 执行，幂等。
#
# 服务器上已有一套原生（非 Docker）+ systemd 的平台，在 /opt/monitoring/：
#   prometheus / alertmanager / grafana / tempo / elasticsearch / kibana / filebeat / node_exporter / alert-notifier
# 本脚本只做「加法」：新增尚品汇的采集任务、告警规则、仪表板、日志输入与解析管道，
# 不动元气购（projectku）的既有配置。
#
# 用法（由 observability/scripts/deploy-platform-to-server.sh 上传后调用）：
#   bash server-apply-platform.sh [--stage /root/deploy-shang]
#
# 幂等：重复执行结果一致；每步改动前备份，校验失败自动恢复。
# 回退：bash server-apply-platform.sh --revert  （撤掉尚品汇的全部接入项，保留元气购）
set -euo pipefail

readonly BASE="/opt/monitoring"
readonly SVC_USER="monitoring"
STAGE="${STAGE:-/root/deploy-shang}"   # 可变：--stage 覆盖
readonly PROM_CFG="$BASE/prometheus/prometheus.yml"
readonly PROM_RULES_DIR="$BASE/prometheus/rules"
readonly DASH_DIR="$BASE/grafana/provisioning/dashboards-shang"
readonly FB_CFG="$BASE/filebeat/filebeat.yml"
readonly AM_CFG="$BASE/alertmanager/alertmanager.yml"
readonly ES="http://127.0.0.1:9200"
readonly KIBANA="http://127.0.0.1:5601"
readonly PROM="http://127.0.0.1:9090"
readonly MARK_BEGIN="# >>> shang-pivot"
readonly MARK_END="# <<< shang-pivot"

REVERT="no"
while [ $# -gt 0 ]; do
  case "$1" in
    --stage) STAGE="$2"; shift 2 ;;
    --revert) REVERT="yes"; shift ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

log()  { printf '  %s\n' "$*"; }
step() { printf '\n=== %s ===\n' "$*"; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

backup() {  # backup <file>
  local f="$1" b="${1}.bak-shang"
  [ -f "$b" ] || cp -a "$f" "$b"
  printf '%s' "$b"
}

# 用标记块替换目标文件里的托管片段：先删旧块，再在指定位置插入新块
# replace_block <文件> <新块文件> <插入点>  插入点：append | before:<正则>
replace_block() {
  local target="$1" block="$2" where="$3" tmp
  tmp="$(mktemp)"
  python3 - "$target" "$block" "$where" > "$tmp" <<'PY'
import re, sys

target, block, where = sys.argv[1], sys.argv[2], sys.argv[3]
begin, end = "# >>> shang-pivot", "# <<< shang-pivot"
lines = open(target, encoding="utf-8").read().splitlines(keepends=True)
try:
    block_lines = open(block, encoding="utf-8").read().splitlines(keepends=True)
except FileNotFoundError:
    block_lines = []

# 1) 去掉旧块
out, skipping = [], False
for line in lines:
    if line.lstrip().startswith(begin):   # 块在 YAML 里是缩进的，必须按 strip 判断
        skipping = True
        continue
    if skipping:
        if line.lstrip().startswith(end):
            skipping = False
        continue
    out.append(line)
if skipping:
    sys.stderr.write("原有标记块不完整（缺少结束标记）\n")
    sys.exit(3)

# 2) 插入新块
if block_lines:
    if where.startswith("before:"):
        pattern = re.compile(where[len("before:"):])
        idx = next((i for i, l in enumerate(out) if pattern.search(l)), None)
        if idx is None:
            sys.stderr.write("找不到插入点：%s\n" % where)
            sys.exit(4)
        out[idx:idx] = block_lines
    else:
        while out and out[-1].strip() == "":
            out.pop()
        out.append("\n")
        out.extend(block_lines)
sys.stdout.write("".join(out))
PY
  cat "$tmp" > "$target"
  rm -f "$tmp"
}

printf '=== 0. 前置检查 ===\n'
[ -d "$BASE" ] || die "$BASE 不存在：服务器上还没有监控平台"
[ -d "$STAGE" ] || die "找不到暂存目录 $STAGE"
for s in prometheus alertmanager grafana tempo elasticsearch kibana filebeat; do
  systemctl is-active --quiet "$s" || log "警告：$s 不是 running 状态"
done
log "平台服务在跑：$(systemctl is-active prometheus grafana tempo elasticsearch kibana filebeat alertmanager | tr '\n' ' ')"

if [ "$REVERT" = "yes" ]; then
  printf '\n=== 回退：撤掉尚品汇接入项 ===\n'
  for f in "$PROM_CFG" "$FB_CFG" "$AM_CFG"; do
    if [ -f "${f}.bak-shang" ]; then
      cp -a "${f}.bak-shang" "$f"
      log "已恢复 $f"
    fi
  done
  rm -f "$PROM_RULES_DIR/shang-pivot-alerts.yml"
  rm -rf "$DASH_DIR"
  python3 - "$BASE/grafana/provisioning/dashboards/dashboards.yml" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
begin, end = "# >>> shang-pivot", "# <<< shang-pivot"
out, skip = [], False
for line in s.splitlines(keepends=True):
    if line.lstrip().startswith(begin):
        skip = True; continue
    if skip:
        if line.lstrip().startswith(end):
            skip = False
        continue
    out.append(line)
open(p, "w", encoding="utf-8").write("".join(out))
PY
  systemctl kill -s HUP prometheus || true
  systemctl kill -s HUP alertmanager || true
  systemctl restart filebeat
  log "已回退；元气购配置未受影响"
  exit 0
fi

printf '\n=== 1. Prometheus：新增采集任务 ===\n'
cp -a "$PROM_CFG" "$(backup "$PROM_CFG")" >/dev/null
replace_block "$PROM_CFG" "$STAGE/prometheus/shang-pivot-scrape.yml" append
if "$BASE/prometheus/promtool" check config "$PROM_CFG" >/tmp/promtool.out 2>&1; then
  log "promtool 校验通过"
else
  cp -a "${PROM_CFG}.bak-shang" "$PROM_CFG"
  sed 's/^/    /' /tmp/promtool.out | head -10
  die "prometheus.yml 校验失败，已恢复备份"
fi

printf '\n=== 2. Prometheus：告警规则 ===\n'
install -o "$SVC_USER" -g "$SVC_USER" -m 0644 "$STAGE/prometheus/shang-pivot-alerts.yml" \
  "$PROM_RULES_DIR/shang-pivot-alerts.yml"
log "已装 $(basename "$STAGE/prometheus/shang-pivot-alerts.yml")（含 $(grep -c '      - alert:' "$PROM_RULES_DIR/shang-pivot-alerts.yml") 条规则）"

systemctl kill -s HUP prometheus
sleep 2
if [ "$(systemctl is-active prometheus)" != "active" ]; then
  cp -a "${PROM_CFG}.bak-shang" "$PROM_CFG"
  die "Prometheus 重载后未存活，已恢复配置（请查 journalctl -u prometheus）"
fi
log "Prometheus 已热加载"

printf '\n=== 3. Grafana：仪表板（独立目录 + 独立目录夹）===\n'
mkdir -p "$DASH_DIR"
install -o "$SVC_USER" -g "$SVC_USER" -m 0644 "$STAGE/grafana/shang-pivot-perf-overview.json" \
  "$DASH_DIR/shang-pivot-perf-overview.json"
chown "$SVC_USER":"$SVC_USER" "$DASH_DIR"
DASH_CFG="$BASE/grafana/provisioning/dashboards/dashboards.yml"
cp -a "$DASH_CFG" "$(backup "$DASH_CFG")" >/dev/null
python3 - "$DASH_CFG" <<'PY'
import sys
path = sys.argv[1]
begin, end = "# >>> shang-pivot", "# <<< shang-pivot"
block = """{begin}
  - name: shang-pivot
    orgId: 1
    folder: 尚品汇可观测
    type: file
    disableDeletion: false
    updateIntervalSeconds: 10
    allowUiUpdates: true
    options:
      path: /opt/monitoring/grafana/provisioning/dashboards-shang
      foldersFromFilesStructure: false
{end}
""".format(begin=begin, end=end)
s = open(path, encoding="utf-8").read()
out, skip = [], False
for line in s.splitlines(keepends=True):
    if line.lstrip().startswith(begin):
        skip = True; continue
    if skip:
        if line.lstrip().startswith(end):
            skip = False
        continue
    out.append(line)
if out and not out[-1].endswith("\n"):
    out[-1] += "\n"
out.append("\n" + block)
open(path, "w", encoding="utf-8").write("".join(out))
PY
log "已加 provider（目录 /opt/monitoring/grafana/provisioning/dashboards-shang → 文件夹「尚品汇可观测」）"
# 新增 provider 属于 provisioning 配置变更：Grafana 只在启动时读它（仪表板 JSON 才热加载），
# 因此这里必须重启；仅在文件真的变了时才重启，避免每次部署都打断 UI。
if ! cmp -s "$DASH_CFG" "${DASH_CFG}.bak-shang"; then
  systemctl restart grafana
  log "Grafana 已重启以加载新 provider"
  for i in $(seq 1 20); do
    sleep 2
    curl -fsS -o /dev/null -u admin:admin "http://127.0.0.1:3000/api/health" 2>/dev/null && break
  done
else
  log "dashboards.yml 无变化，未重启 Grafana"
fi

printf '\n=== 4. Elasticsearch：日志解析管道 ===\n'
curl -fsS -X PUT "$ES/_ingest/pipeline/shang-pivot-logs" \
  -H 'Content-Type: application/json' \
  --data-binary "@$STAGE/elasticsearch/pipeline-shang-pivot-logs.json" >/dev/null \
  && log "管道 shang-pivot-logs 已写入（处理器 $(python3 -c "
import json, sys
d = json.load(open(sys.argv[1]))
body = d.get('shang-pivot-logs', d)
print(len(body['processors']))
" "$STAGE/elasticsearch/pipeline-shang-pivot-logs.json") 个）"

printf '\n=== 5. Filebeat：日志输入 ===\n'
cp -a "$FB_CFG" "$(backup "$FB_CFG")" >/dev/null
FB_BEFORE="$(md5sum "$FB_CFG" | cut -d' ' -f1)"
replace_block "$FB_CFG" "$STAGE/filebeat/shang-pivot-inputs.yml" 'before:^processors:'
if "$BASE/filebeat/filebeat" test config -c "$FB_CFG" >/tmp/fbtest.out 2>&1; then
  log "filebeat 配置校验通过"
else
  cp -a "${FB_CFG}.bak-shang" "$FB_CFG"
  sed 's/^/    /' /tmp/fbtest.out | head -10
  die "filebeat.yml 校验失败，已恢复备份"
fi
FB_AFTER="$(md5sum "$FB_CFG" | cut -d' ' -f1)"
if [ "$FB_BEFORE" != "$FB_AFTER" ]; then
  systemctl restart filebeat
  log "filebeat 已重启（配置有变化）"
else
  log "filebeat 配置无变化，未重启"
fi

printf '\n=== 6. Kibana：日志数据视图 ===\n'
if curl -fsS -o /dev/null "$KIBANA/api/data_views/data_view/shang-pivot-logs" 2>/dev/null; then
  log "数据视图 shang-pivot-logs 已存在"
else
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$KIBANA/api/data_views/data_view" \
    -H 'kbn-xsrf: true' -H 'Content-Type: application/json' \
    -d '{"data_view":{"id":"shang-pivot-logs","title":"shang-pivot-logs-*","name":"尚品汇日志","timeFieldName":"@timestamp"}}')"
  [ "$code" = "200" ] && log "数据视图已创建：shang-pivot-logs" || log "数据视图创建返回 HTTP ${code}（可稍后在 Kibana 界面手动建）"
fi

printf '\n=== 7. Alertmanager：尚品汇的抑制规则 ===\n'
cp -a "$AM_CFG" "$(backup "$AM_CFG")" >/dev/null
python3 - "$AM_CFG" <<'PY'
import sys
path = sys.argv[1]
begin, end = "# >>> shang-pivot", "# <<< shang-pivot"
block = """{begin}
  # 尚品汇指标端点不可达时，压掉它自己派生出来的一堆 warning（与元气购那条同理）
  - source_matchers:
      - alertname="尚品汇-服务不可用"
    target_matchers:
      - severity="warning"
    equal: []
{end}
""".format(begin=begin, end=end)
s = open(path, encoding="utf-8").read()
out, skip = [], False
for line in s.splitlines(keepends=True):
    if line.lstrip().startswith(begin):
        skip = True; continue
    if skip:
        if line.lstrip().startswith(end):
            skip = False
        continue
    out.append(line)
if out and not out[-1].endswith("\n"):
    out[-1] += "\n"
out.append(block)
open(path, "w", encoding="utf-8").write("".join(out))
PY
if systemctl kill -s HUP alertmanager 2>/dev/null; then
  sleep 1
  [ "$(systemctl is-active alertmanager)" = "active" ] && log "Alertmanager 已热加载" || {
    cp -a "${AM_CFG}.bak-shang" "$AM_CFG"; systemctl kill -s HUP alertmanager || true
    die "Alertmanager 重载后未存活，已恢复配置"
  }
fi

printf '\n=== 8. 验收 ===\n'
sleep 8
printf '  采集目标：\n'
curl -fsS "$PROM/api/v1/targets?state=active" | python3 -c '
import json, sys
for t in json.load(sys.stdin)["data"]["activeTargets"]:
    mark = "✓" if t["health"] == "up" else "✗"
    print("    %s %-18s %s" % (mark, t["labels"]["job"], t["scrapeUrl"]))
'
printf '  规则：\n'
curl -fsS "$PROM/api/v1/rules" | python3 -c '
import json, sys
total = 0
for g in json.load(sys.stdin)["data"]["groups"]:
    names = [r["name"] for r in g["rules"]]
    mine = [n for n in names if n.startswith("尚品汇-")]
    total += len(mine)
    if mine:
        print("    [%s] 尚品汇规则 %d 条" % (g["name"], len(mine)))
print("    尚品汇规则合计 %d 条" % total)
'
printf '  日志索引：\n'
curl -fsS "$ES/_cat/indices/shang-pivot-logs-*?h=index,docs.count&s=index" 2>/dev/null | sed 's/^/    /' \
  || echo "    （尚无索引：Filebeat 要等新日志写入后才会创建，几分钟内出现）"
printf '  数据视图：'
curl -fsS "$KIBANA/api/data_views/data_view/shang-pivot-logs" 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["data_view"]["title"])' 2>/dev/null || echo "未创建"

cat <<EOF

完成。回退：bash server-apply-platform.sh --revert
EOF
