# 本地数据质量校验与漂移监控

纯本地运行的批量数据质量校验与列级漂移监控服务（Spring Boot 3 / Java 21）。
不依赖任何外部数据库或第三方服务：契约、规则版本、批次结论、列画像与基线全部保存在本地 JSON 文件中
（原子写入，进程重启后仍可复现历史判定）。

## 1. 能力总览

- 批量数据提交：支持 **CSV** 与 **JSON Lines（jsonl）** 两种形态，支持显式声明文本编码。
- 结构校验：字段缺失、类型不符、空值约束、枚举越界、契约外多余字段、编码异常、CSV/JSON 语法错误，
  均给出**可区分的错误码**并**定位到具体记录行号/字段**，不静默放过。
- 质量检查：完整性、唯一性、取值范围（异常值）、时效性，四项可**独立启停并配置阈值**，
  最终结论逐项说明是否通过、实测指标与失败原因。
- 幂等：以批次内容 SHA-256 为幂等键，**重复提交（含并发重复）结论一致、只计数一次**。
- 历史复现：批次提交时快照所使用的契约与规则版本；规则/阈值调整只影响后续批次，
  历史结论永不被改写；旧规则版本永久保留，可显式指定复现当时判定。
- 漂移监控：基于历史批次的列级画像（数值列均值/方差/极值，类别列频次分布），
  数值列用均值 z-score、类别列用总变差距离 TVD 判定；**样本不足一律 `UNDECIDABLE`，不给确定结论**。
- 基线更新策略可控：`EXPANDING`（自动扩张）、`ROLLING_WINDOW`（自动滚动窗口）、`MANUAL`（仅手动 rebuild）。
- 并发安全：按数据集分段锁串行化“查重→校验→画像→漂移→基线更新→落盘”，
  并发多批统计结果不会互相覆盖或丢失。

## 2. 结论状态与错误码

批次结论 `status`：

| 状态 | 含义 |
| --- | --- |
| `ACCEPTED` | 结构校验通过且所有已启用质量检查通过 |
| `REJECTED_STRUCTURAL` | 编码/语法/记录级结构校验未通过，不进入质量检查与画像 |
| `REJECTED_QUALITY` | 结构通过但至少一项已启用的质量检查未通过 |

结构级问题 `structuralIssues[].code`：

| 错误码 | 含义 |
| --- | --- |
| `ENCODING_ERROR` | 字节内容不符合声明的编码（严格解码，不替换成 U+FFFD） |
| `MALFORMED_CSV` | CSV 列数不一致、未闭合引号、表头为空/重复列 |
| `MALFORMED_JSON` | JSONL 某行不是合法 JSON，或不是 JSON 对象 |
| `EMPTY_DATA` | 内容为空或没有任何数据记录 |

记录级问题 `recordIssues[].code`（带 `recordIndex`/`line`/`field`）：

| 错误码 | 含义 |
| --- | --- |
| `MISSING_FIELD` | 缺少必填字段，或必填字段为 null / 空字符串 |
| `TYPE_MISMATCH` | 值与契约声明类型不符（jsonl 采用严格类型，`30.5` 不可写入 integer） |
| `NULL_NOT_ALLOWED` | 非必填但声明不可为空的字段为空 |
| `VALUE_NOT_ALLOWED` | 取值不在契约 `allowedValues` 枚举集合内 |
| `UNKNOWN_FIELD` | 出现契约外的多余字段 |

字段类型：`string`、`integer`、`number`、`boolean`、`date`（`yyyy-MM-dd`）、
`datetime`（ISO-8601，如 `2026-10-06T09:00:00Z`）。

## 3. 质量检查规则

规则集 `QualityRules` 字段：

| 配置项 | 检查 | 判定方式 |
| --- | --- | --- |
| `completenessEnabled` + `maxMissingRate` | 完整性 | 空值单元格占全部契约单元格比例 `<= 阈值` 通过；按列给出缺失明细 |
| `uniquenessColumns` + `maxDuplicateRate` | 唯一性 | 组合键重复行（含键值为 null 的行不参与）占比 `<= 阈值` 通过；列表为空表示停用 |
| `rangeRules[]`（`column/min/max/maxOutOfRangeRate`） | 取值范围/异常值 | 数值落在闭区间 `[min,max]` 外的占比 `<= 阈值` 通过；可逐列配置 |
| `freshness`（`timestampColumn/maxAge/maxStaleRate`） | 时效性 | 批次最大时间戳不得早于 `校验时刻 - maxAge`（`maxAge` 为 Duration，如 `PT2H`/`P1D`），且过期行占比不超阈值 |

每项检查的返回都包含 `enabled`、`passed`、`metrics`（实测值）、`threshold` 与 `reasons`，
因此 `REJECTED_QUALITY` 结论可以明确回答“是哪些检查、因为什么指标不通过”。

## 4. 漂移判定

- 数值列：以基线均值/标准差计算批次均值的 `|z|`，`z > numericMeanZThreshold`（默认 3）判 `DRIFT`。
- 类别列：批次与基线类别频次分布的总变差距离
  `TVD = 1/2 Σ |p_base(k) - p_batch(k)|`，`TVD > categoricalTvdThreshold`（默认 0.2）判 `DRIFT`。
- 批次结论 `drift.decision`：
  - `UNDECIDABLE`：基线批次数 `< minBaselineSamples`（默认 2）、基线/批次无有效行、
    批次行数 `< minBatchRows`、或所有列均不具备可判定样本；**此时不给确定结论**。
  - `NO_DRIFT`：所有可判定列均未超阈值。
  - `DRIFT`：任一可判定列超阈值，`reasons` 与 `columns[]` 指明列、指标值与阈值。
- 只有 `ACCEPTED` 批次才参与漂移评估与基线更新；被拒批次不污染基线。
- 漂移判定在提交时计算并随批次结论一起固化，之后不会因基线继续变化而追溯改变历史结论。

## 5. 基线更新策略

通过 `PUT /api/admin/datasets/{dataset}/baseline` 修改基线配置：

| `updateStrategy` | 行为 |
| --- | --- |
| `EXPANDING`（默认） | 每个 ACCEPTED 批次自动合入基线，基线持续扩张 |
| `ROLLING_WINDOW` | 仅保留最近 `windowSize` 个 ACCEPTED 批次，更旧批次画像自动剔除 |
| `MANUAL` | 提交永不改变基线（漂移仍对当前基线评估）；调用 `.../baseline/rebuild` 才从全部历史 ACCEPTED 批次重建 |

其它配置：`minBaselineSamples`、`minBatchRows`、`numericMeanZThreshold`、
`numericOutOfRangeRateThreshold`（保留配置）、`categoricalTvdThreshold`。

## 6. 规则版本与历史复现

- 注册规则必须带 `version`；同版本号**不可覆盖**（接口返回 400），调整阈值请升版本（如 `v1 → v2`）。
- 提交时可传 `rulesVersion` 显式指定版本；缺省使用该数据集最新版本。
- 批次结论内嵌 `contractSnapshot` 与 `rulesSnapshot`，`GET /api/batches/{dataset}/{batchId}`
  永远返回提交当时的结论与规则，不受后续变更影响。

## 7. HTTP 接口

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/admin/contracts` | 注册/更新契约（按名称） |
| `GET  /api/admin/contracts` | 查看全部契约 |
| `POST /api/admin/datasets/{dataset}/rules` | 注册新规则版本（版本不可覆盖） |
| `GET  /api/admin/datasets/{dataset}/rules` | 查看某数据集全部规则版本 |
| `GET  /api/admin/datasets/{dataset}/baseline` | 查看基线（画像、构成批次、配置） |
| `PUT  /api/admin/datasets/{dataset}/baseline` | 修改基线配置 |
| `POST /api/admin/datasets/{dataset}/baseline/rebuild` | 手动重建基线 |
| `POST /api/batches/{dataset}/submit` | 提交批次（见下） |
| `GET  /api/batches/{dataset}` | 列出某数据集全部历史批次结论 |
| `GET  /api/batches/{dataset}/{batchId}` | 查询单个历史批次结论 |

提交请求体：

```json
{
  "format": "jsonl",
  "encoding": "UTF-8",
  "content": "{\"id\":1,...}\n{\"id\":2,...}",
  "contentBase64": null,
  "rulesVersion": null,
  "inlineContract": null,
  "inlineRules": null
}
```

- `content` 与 `contentBase64` 二选一；需要携带畸形字节验证编码异常时用 Base64。
- 数据集可预先注册契约/规则，也可通过 `inlineContract`/`inlineRules` 随请求声明（规则版本同样会登记）。
- 响应为批次结论加 `duplicate` 字段；重复提交 `duplicate=true`，其余字段与首次完全一致。

## 8. 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `dq.store.dir` | `./.dq-data` | 本地状态目录，状态文件为 `dq-state.json`（临时文件 + 原子 move） |
| `server.port` | `0`（测试） | 本地运行可改为固定端口，如 `8080` |

运行：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
```

## 9. 验证方法

```bash
mvn test
```

测试共 29 个用例，按业务域组织；运行日志通过 `BusinessTestWatcher` 输出
“【业务覆盖开始】<测试类>#<用例> —— <业务域说明>”与“【业务覆盖结果】… 通过/失败”，
可直接看出覆盖了哪些业务以及每个用例的结果：

| 测试类 | 覆盖业务 |
| --- | --- |
| `MalformedDataTest` | 畸形数据：缺列/缺值、类型不符、JSON 严格整数类型、枚举/空值、非法 UTF-8、CSV 列数/引号、JSONL 语法、空数据 |
| `QualityCheckTest` | 四项质量检查通过/失败、实测指标与阈值、检查独立启停 |
| `IdempotencyConcurrencyTest` | 串行重复提交、16 线程并发重复提交、24 批并发提交的计数与画像一致性 |
| `RuleVersioningTest` | 阈值收紧后历史结论不变、快照固化、指定旧版本复现、版本号不可覆盖 |
| `DriftBoundaryTest` | 样本不足不可判定、数值 z-score 漂移边界、类别 TVD 漂移、MANUAL/ROLLING_WINDOW 策略、拒绝批次不入基线、20 批并发画像不丢失 |
| `HttpApiTest` | 本地 HTTP 接口端到端：注册、提交、重复响应、历史查询、404 |
| `PersistenceReproductionTest` | 重启后从本地 JSON 恢复：历史结论、契约/规则快照、基线画像一致，且重复内容仍幂等 |

快速手工验证（端口 8080）：

```bash
curl -s -X POST localhost:8080/api/admin/contracts -H 'Content-Type: application/json' -d '{
  "name":"orders","version":"v1","fields":[
    {"name":"id","type":"integer","required":true,"nullable":false},
    {"name":"name","type":"string","required":true,"nullable":false},
    {"name":"age","type":"integer","required":false,"nullable":false},
    {"name":"event_time","type":"datetime","required":true,"nullable":false}]}'

curl -s -X POST localhost:8080/api/admin/datasets/orders/rules -H 'Content-Type: application/json' -d '{
  "version":"v1","completenessEnabled":true,"maxMissingRate":0,
  "uniquenessColumns":["id"],"maxDuplicateRate":0,
  "rangeRules":[{"column":"age","min":0,"max":120,"maxOutOfRangeRate":0}],
  "freshness":{"timestampColumn":"event_time","maxAge":"PT2H","maxStaleRate":0}}'

curl -s -X POST localhost:8080/api/batches/orders/submit -H 'Content-Type: application/json' -d '{
  "format":"jsonl",
  "content":"{\"id\":1,\"name\":\"a\",\"age\":30,\"event_time\":\"2026-10-06T09:30:00Z\"}"}'
```
