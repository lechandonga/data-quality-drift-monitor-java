# Data Quality & Drift Monitor (Java / Spring Boot)

一套**纯本地**运行的批量数据质量校验与漂移监控服务：不依赖任何外部服务或数据库，
所有状态保存在内存中，单进程即可跑通全部能力。

## 快速开始

```bash
mvn spring-boot:run        # 启动服务（默认 8080 端口）
mvn test                   # 运行全部单元测试
```

## 本地接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/batches?format=csv\|jsonl` | 提交批量数据，请求头 `X-Batch-Id` 为幂等键 |
| GET  | `/api/batches/{batchId}` | 查询某批次的历史报告（不可变） |
| GET/POST | `/api/rules` | 查看 / 热更新质量规则（产生新的规则版本） |
| GET  | `/api/drift/baselines/{column}` | 查看某列基线样本量与基线更新策略 |

示例：

```bash
curl -X POST 'http://localhost:8080/api/batches?format=csv' \
  -H 'X-Batch-Id: batch-001' -H 'Content-Type: text/csv' \
  --data-binary $'id,amount,category,event_time\nid-0,99.5,promo,2026-10-06T10:00:00Z'
```

## 结构校验（契约）

提交的数据按 `dq.contract.*` 声明的字段契约逐条校验，以下问题**都不会被静默放过**，
且都能定位到具体记录（`recordIndex` 从 1 开始，CSV 不含表头）：

| 拒绝原因 | 含义 |
| --- | --- |
| `ENCODING_ERROR` | 载荷不是合法 UTF-8 |
| `MALFORMED_RECORD` | CSV 列数不符 / JSON 行无法解析 |
| `MISSING_FIELD` | 必填字段缺失或为空 |
| `TYPE_MISMATCH` | 值无法解析为声明类型（STRING/INTEGER/DOUBLE/BOOLEAN/TIMESTAMP） |
| `UNEXPECTED_FIELD` | 出现契约未声明的字段（CSV 表头或 JSON key） |

存在任何拒绝记录时整批判定为 `REJECTED`（HTTP 422），响应体携带全部 `rejections`。

## 质量检查

内置四类检查，每项都可**独立启停**并配置阈值（`dq.checks[i]` 或 `POST /api/rules`）：

- **COMPLETENESS** 完整性：`maxMissingRatio` 允许的空值比例（可按字段或全记录）。
- **UNIQUENESS** 唯一性：`field` + `maxDuplicateRatio` 允许的重复键比例。
- **RANGE** 取值范围/异常值：`field` + `[min, max]` + `maxOutlierRatio` 允许的越界比例。
- **FRESHNESS** 时效性：`field`（TIMESTAMP 类型）+ `maxAgeSeconds` 允许的最大数据年龄。

最终结论 `PASS` / `FAIL` 附带每项检查的 `CheckResult`，`detail` 中给出实测值与阈值
（如 `duplicateRatio=0.5000 (duplicates=1/2) threshold=0.0000`），可明确解释是哪项检查导致不通过。

### 幂等与历史复现

- 同一 `X-Batch-Id` 重复提交（包括并发重复提交）返回**完全相同**的结论
  （`duplicate=true`），且画像统计不会重复累计。
- 每份报告记录提交时的 `ruleVersion`（规则内容的哈希）。规则或阈值调整后，
  历史报告**不会被改写**，`GET /api/batches/{batchId}` 永远返回当时的判定与当时的规则版本。

## 漂移监控

- 对每列维护基线画像：数值列用 Welford 运行统计（均值/方差），类别列用取值计数。
- 判定方式：数值列比较**标准化均值偏移**（|批次均值 − 基线均值| / 基线标准差），
  类别列计算 **PSI**（总体稳定性指数）。
- **样本不足不可判定**：基线样本数 < `dq.drift.min-samples` 时，结果为 `INDETERMINATE`，
  不会给出确定结论。
- **基线更新策略**（`dq.drift.baseline-strategy`）：
  - `NONE`：基线永不自动更新（完全人工控制）；
  - `ON_PASS`（默认）：仅吸收质量判定为 PASS 的批次；
  - `ALWAYS`：吸收所有结构合法的批次。
- 并发安全：批次评估基于基线快照，"评估 + 基线更新"整体串行化，
  并发提交多批数据时统计不会互相覆盖或丢失。

## 配置项（application.properties）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `dq.contract.version` / `dq.contract.fields[i].{name,type,required}` | 见配置文件 | 字段契约 |
| `dq.checks[i].{type,enabled,field,...}` | 见配置文件 | 质量检查及阈值 |
| `dq.drift.min-samples` | 20 | 可判定所需的最小基线样本数 |
| `dq.drift.numeric-shift-threshold` | 3.0 | 数值漂移阈值（标准差倍数） |
| `dq.drift.psi-threshold` | 0.25 | 类别漂移 PSI 阈值 |
| `dq.drift.baseline-strategy` | ON_PASS | 基线更新策略 |

## 验证方法

`mvn test` 运行 20 个用例，日志按业务分组（`[结构校验]` `[质量检查]` `[幂等]`
`[历史复现]` `[漂移监控]` `[本地接口]`），可直接看出各业务覆盖了哪些用例：

- `ContractValidationTests`：畸形 CSV/JSONL、字段缺失、类型不符、多余字段、编码异常、合法批次。
- `QualityCheckTests`：唯一性/范围/时效失败的可解释结论、检查独立启停与阈值生效。
- `IdempotencyTests`：顺序与并发重复提交结论一致、不重复计数。
- `RuleChangeHistoryTests`：阈值收紧后历史批次结论与规则版本保持不变。
- `DriftMonitorTests`：样本不足 INDETERMINATE、基线策略可控、漂移检出、并发提交统计不丢失。
- `ApiIntegrationTests`：REST 提交/查询/规则热更新、422 拒绝响应。
