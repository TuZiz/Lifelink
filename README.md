# LifeLink

LifeLink 是一个 Kotlin 编写的 Spigot / Paper / Folia 兼容插件，用于托管树木从合法砍伐、自动补种、系统树苗保护到再次成长为托管树木的完整生命周期。当前版本在原有能力上新增了“老服保守型荒野修复系统”：先扫描玩家资产与保护遮罩，再恢复低风险荒野。

LifeLink 不是盲目区块重置插件，也不会把老服直接自动覆盖成新世界。它的默认策略是：宁可少恢复，也不要误伤玩家建筑。

## 核心能力

- 树木生命周期托管：自然树从根部合法砍伐后自动补种。
- 系统树苗保护：只保护 `SYSTEM_PLANTED_SAPLING`，普通自然树和玩家树苗不会被全局锁死。
- 托管树闭环：系统树苗成长为 `SYSTEM_MANAGED_TREE` 后，再次合法砍伐会继续补种。
- 管理员树苗模式：`/lifelink saplingmode on` 后，管理员手动放置树苗会进入系统托管生命周期。
- 甘蔗留苗：砍底部甘蔗时保留最下方一格，掉落上方甘蔗；潜行可按配置绕过。
- 保守型荒野修复：扫描、预览、保护、低风险恢复、确认、快照、回滚。
- Paper API 优先，Folia 兼容：所有世界方块读写通过 `SchedulerAdapter` 进入目标 region/global/entity 调度。
- 全 IO 异步：配置、语言、树木状态、荒野状态、快照和审计日志均通过专用 IO executor 处理。
- MiniMessage 语言系统：用户可见文本走 `lang/*.yml`，支持根级 `prefix` 与旧 `messages.prefix` 兼容。
- 声音配置：成功、错误、警告、补种、荒野预览、荒野恢复声音均在语言文件中配置。

## 老服保守型荒野修复系统

新增模块围绕 `ConservativeWildernessRestore` 思路实现：

1. 先扫描玩家资产。
2. 先生成保护遮罩。
3. 先生成风险等级。
4. 只自动恢复低风险荒野。
5. 中风险区域必须管理员确认。
6. 高风险区域默认拒绝恢复。
7. 恢复前写快照。
8. 恢复后可回滚。
9. 不接入 CoreProtect / COI。
10. 默认不覆盖容器、TileState、InventoryHolder、红石、床、告示牌、展示框、盔甲架和手动保护区。

### 为什么不是自动恢复系统

老服里可能存在无领地房屋、纪念建筑、地下仓库、红石机器、刷怪塔、道路、桥梁、临时基地和装饰树。由于 LifeLink 不接入 CoreProtect / COI，无法 100% 判断历史方块是谁放的，所以默认采用保守策略：

- 一上线不自动恢复全世界。
- 不只按破坏分数直接恢复。
- 不只按自然母本世界覆盖。
- 不只判断有没有领地。
- 遇到疑似玩家资产就保护或要求确认。

## 自然母本世界

荒野恢复使用双世界模型：

- 真实世界：例如 `world`
- 自然母本世界：默认 `world_origin`

`world_origin` 应该由服主用同版本、同种子、同生成器、同数据包生成，并保持干净自然状态。LifeLink 默认不会自动创建母本世界，也不会写入母本世界。

建议流程：

1. 停服或在测试环境确认主世界种子、版本、生成器和数据包。
2. 生成一个干净世界目录，例如 `world_origin`。
3. 确保玩家不能进入 `world_origin`。
4. 在 `config.yml` 中配置：

```yaml
wilderness:
  origin-world:
    name: "world_origin"
    auto-create: false
    readonly: true
```

如果母本世界不存在，荒野 restore 会失败并提示 `origin-world-missing`，scan/preview 仍可用于风险检查。

## 荒野命令

```text
/lifelink wilderness help
/lifelink wilderness scan chunk
/lifelink wilderness scan radius <radius>
/lifelink wilderness scan selection
/lifelink wilderness preview chunk
/lifelink wilderness preview radius <radius>
/lifelink wilderness preview selection
/lifelink wilderness preview clear
/lifelink wilderness restore chunk
/lifelink wilderness restore radius <radius>
/lifelink wilderness restore surface <radius>
/lifelink wilderness restore selection
/lifelink wilderness confirm <jobId>
/lifelink wilderness cancel <jobId>
/lifelink wilderness rollback <jobId>
/lifelink wilderness jobs
/lifelink wilderness job <jobId>
/lifelink wilderness protect chunk
/lifelink wilderness protect radius <radius>
/lifelink wilderness protect pos1
/lifelink wilderness protect pos2
/lifelink wilderness protect create <name>
/lifelink wilderness protect list
/lifelink wilderness protect remove <id>
/lifelink wilderness pos1
/lifelink wilderness pos2
```

`/lifelink wild` 可作为 `wilderness` 的短别名。`/lifelink restore ...` 会路由到荒野恢复子命令。

## 风险判断

扫描时会结合以下来源：

- 插件安装后的轻量玩家资产索引。
- 当前区块内的容器、床、告示牌、红石、工作站、建筑方块评分。
- 手动保护区。
- TileState / InventoryHolder 检测。

默认分级：

- `LOW`：建筑分低，没有关键资产，可自动恢复。
- `MEDIUM`：存在资产索引、告示牌、手动保护或中等建筑评分，需要确认。
- `HIGH`：存在容器、床、红石、TileState 或高建筑评分，默认拒绝恢复。

恢复执行时每个方块仍会二次检查保护遮罩和当前方块安全状态。如果目标方块在任务执行期间变成容器或 TileState，LifeLink 会跳过，不会覆盖。

## 快照与回滚

每次 restore 在应用方块前会：

1. 生成恢复计划 hash。
2. 在目标 world 的对应 region 线程读取当前方块状态。
3. 跳过保护遮罩、容器、TileState、InventoryHolder。
4. 将可恢复方块写入 `data/wilderness/backups/<jobId>.snapshot.gz`。
5. 写入快照 SHA-256。
6. 再开始按 region 分批应用自然母本世界方块数据。

回滚使用：

```text
/lifelink wilderness rollback <jobId>
```

回滚同样通过 region 调度应用方块数据，并跳过运行时发现的高价值资产方块，因此重复 rollback 不会通过掉落物制造复制。

## 树木生命周期安全

树木补种仍使用原有状态机：

- `RESERVED`
- `CUTTING_CONFIRMED`
- `REPLANT_PENDING`
- `REPLANT_SCHEDULED`
- `REPLANTED`
- `FAILED`
- `ROLLED_BACK`

本次增强保留原有生命周期模型，并加强 `DROP_SAPLING`：在掉落树苗前先持久化 `drop-sapling-delivered` 标记，避免重试或 recover 重复掉落。

补种仍遵守：

- 目标位置不是空气或对应树苗时不覆盖。
- 目标土壤无效时不补种。
- Folia 下跨 region 的 2x2 补种会保守降级或跳过。
- 托管树必须从树根使用允许工具合法砍伐。

## 配置

核心新增配置在 `config.yml` 的 `wilderness:` 下：

```yaml
wilderness:
  enabled: true
  origin-world:
    name: "world_origin"
    auto-create: false
    readonly: true
  safety:
    default-mode: "SAFE"
    deny-container-overwrite: true
    deny-inventory-holder-overwrite: true
    deny-tile-state-overwrite: true
    deny-entity-item-overwrite: true
    require-confirm-for-medium-risk: true
    require-confirm-for-force: true
    fail-closed-on-unknown-state: true
  scanner:
    max-chunks-per-scan: 256
    surface-restore-min-offset: -8
    surface-restore-max-offset: 24
  performance:
    max-blocks-apply-per-region-tick: 2048
    pause-when-player-nearby: true
    player-safe-distance: 96
  backup:
    compression: "gzip"
    atomic-write: true
    verify-hash: true
```

旧配置仍兼容；没有 `wilderness:` 时会使用内置默认值。

## 语言与声音

语言文件支持新格式：

```yaml
prefix: "<gradient:#22c55e:#38bdf8><bold>LifeLink</bold></gradient> <dark_gray>»</dark_gray>"
messages:
  no-permission:
    type: chat
    text: "<prefix> <#ef4444>你没有权限执行此命令。"
sounds:
  success:
    enabled: true
    name: "minecraft:block.note_block.pling"
    volume: 1.0
    pitch: 1.2
```

兼容旧格式：

```yaml
messages:
  prefix: "..."
```

优先级：根级 `prefix` > `messages.prefix` > `config.yml` 的默认 prefix。

## 权限

```text
lifelink.admin
lifelink.reload
lifelink.recover
lifelink.stats
lifelink.inspect
lifelink.admin.sapling-mode
lifelink.bypass.sapling-protection
lifelink.bypass.managed-tree
lifelink.wilderness.scan
lifelink.wilderness.preview
lifelink.wilderness.restore
lifelink.wilderness.restore.force
lifelink.wilderness.confirm
lifelink.wilderness.rollback
lifelink.wilderness.protect
lifelink.wilderness.debug
```

## Paper / Folia 线程模型

- 世界方块扫描：通过 `SchedulerAdapter.runAt(world, chunkX, chunkZ)` 进入目标 region。
- 世界方块恢复：通过 `SchedulerAdapter.runAt(...)` 分 chunk 应用。
- 玩家消息：通过 `SchedulerAdapter.runEntity(player)` 回到玩家 entity scheduler。
- 文件 IO：通过 TreeRepository / WildernessRepository 专用 IO executor。
- 不新增直接 `Bukkit.getScheduler()` 调用。
- Folia 下调度反射失败会 fail-fast，不会偷偷退回 BukkitScheduler。

## 安全边界

LifeLink 使用异步仓储、原子写入、状态机、幂等任务、区域锁、快照、回滚和 Folia 安全调度来降低风险，但不会虚假承诺以下场景绝对安全：

- 硬件损坏。
- 操作系统级强杀或断电。
- 第三方插件在同一 tick 内强行改世界。
- 使用错误种子或错误生成器创建的 `world_origin`。
- 管理员 force 模式下主动要求覆盖高风险区域。

默认 SAFE 模式会 fail closed：不确定就跳过。

## 老服上线建议

```text
第 1 周：只开启 scan / preview，不恢复。
第 2 周：只允许管理员手动 selection / surface 恢复。
第 3 周：开放低风险荒野 chunk 恢复。
第 4 周以后：定期低风险荒野自愈。
```

建议先在备份世界或测试服验证 `world_origin` 与真实世界坐标一致，再开放任何 restore。

## 人工验收清单

### 原有 LifeLink

1. Paper 可启动。
2. Folia 可启动。
3. 合法砍自然树会自动补种。
4. 玩家手动种树默认不托管。
5. `saplingmode on` 后玩家手动树苗纳入托管。
6. 系统树苗不能被普通玩家破坏。
7. 系统树苗成长后升级为托管树。
8. 托管树再次砍伐能补种闭环。
9. 甘蔗底部砍伐留苗。
10. `DROP_SAPLING` 不重复掉落。
11. `recover` 不重复补种。

### 荒野修复

1. `/lifelink wilderness scan chunk` 只扫描不修改世界。
2. 有箱子的 chunk 被标记为高风险。
3. 有床的 chunk 被标记为高风险。
4. 有红石机器的 chunk 被标记为高风险。
5. 有告示牌 / 展示框的区域被加入保护遮罩或至少中风险。
6. 手动保护区不会被恢复。
7. 低风险荒野可以恢复。
8. 中风险区域必须 `confirm`。
9. 高风险区域默认拒绝恢复。
10. `restore surface <radius>` 只做表层自然化计划。
11. 整 chunk 恢复只允许低风险。
12. 恢复前生成快照。
13. 恢复后可以 rollback。
14. 重复 rollback 不产生掉落物复制。
15. 恢复过程中目标方块变成容器会跳过。
16. 服务器重启后未完成任务进入可恢复失败状态。
17. Folia 下不使用新增 BukkitScheduler 直接调用。
18. 所有荒野 IO 不在 region 线程执行。
19. 预览不会修改真实方块。
20. 玩家靠近时恢复会跳过该 chunk。

## 构建

```powershell
.\gradlew.bat build --no-daemon --console=plain
```

构建产物：

```text
build/libs/Lifelink-1.0.0.jar
```
