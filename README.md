# LifeLink

LifeLink 是一个 Kotlin 编写的 Spigot / Paper / Folia 兼容插件，用于托管树木从合法砍伐、自动补种、系统树苗保护到再次成长为托管树木的完整生命周期。

## 核心功能

- 玩家使用配置允许的斧头从树根合法砍树后，插件在原位置自动补种对应树苗。
- 只有 `SYSTEM_PLANTED_SAPLING` 会被强保护，普通自然树和玩家种植树苗不会被全局锁死。
- 系统树苗通过 `StructureGrowEvent` 成长后升级为 `SYSTEM_MANAGED_TREE`。
- 玩家手动种植的树苗会记录为 `PLAYER_PLANTED_SAPLING`，成长后升级为内部状态 `PLAYER_PLANTED_TREE`，砍伐时不会触发自动补种。
- 管理员可使用 `/lifelink saplingmode on` 开启个人树苗托管模式；开启后手动放置的树苗会记录为系统树苗，成长后支持自动补种。
- 系统托管树木再次从树根合法砍伐后继续补种，形成闭环。
- 甘蔗从底部砍伐时会保留最底下一格作为苗，并掉落上方甘蔗；潜行砍伐可绕过。
- 支持 `OAK`、`BIRCH`、`SPRUCE`、`JUNGLE`、`ACACIA`、`DARK_OAK`、`MANGROVE`、`CHERRY`、`PALE_OAK`。
- 所有玩家消息使用 MiniMessage，语言文本在 `lang/*.yml`，`prefix` 单独在语言文件的 `messages.prefix` 配置。

## 生命周期模型

LifeLink 显式区分四种业务状态：

- `NATURAL_TREE`：自然树，不写入仓储，只有在玩家合法从根部砍伐并通过检测后才创建补种任务。
- `PLAYER_PLANTED_SAPLING`：玩家树苗，插件不保护、不托管。
- `PLAYER_PLANTED_TREE`：玩家树苗成长后的内部状态，用于排除自动补种，不保护、不拦截。
- `SYSTEM_PLANTED_SAPLING`：系统补种的树苗，写入仓储并建立运行时 sapling 索引，受保护。
- `SYSTEM_MANAGED_TREE`：系统树苗成长后的树木，写入仓储并建立运行时 log 索引。

补种任务状态机为：

- `DETECTING`：保留给检测前状态，当前实现检测在事件线程完成后直接进入预约。
- `RESERVED`：位置已被内存级去重集合预约，防止重复任务。
- `CUTTING_CONFIRMED`：合法砍伐事件已确认，等待下一 tick 补种。
- `REPLANT_PENDING`：补种准备执行或等待恢复。
- `REPLANT_SCHEDULED`：已经通过调度器安排补种尝试。
- `REPLANTED`：树苗已放置，生命周期记录为 `SYSTEM_PLANTED_SAPLING`。
- `FAILED`：失败终态，可按保留时间清理。
- `ROLLED_BACK`：按 `CANCEL` 策略安全回滚。

任务创建在 `BlockBreakEvent` 的根部合法砍伐路径中发生；每次状态推进都会异步写入 `TreeRepository`。恢复时会重新调度未完成任务，并校验已保存的系统树苗和托管树记录。

## 系统树苗保护

`SYSTEM_PLANTED_SAPLING` 会拦截破坏、替换、多方块放置、活塞推动、液体流入、物理更新、燃烧、爆炸和实体改方块等常见绕过路径。管理员可使用 `lifelink.bypass.sapling-protection` 绕过。托管树木保护可通过 `protection.protect-managed-trees` 控制，默认要求从树根使用斧头触发生命周期任务。

## 线程模型

业务代码不直接散落调用 `BukkitScheduler`，而是通过 `SchedulerAdapter`：

- Spigot / Paper 使用 `PaperSchedulerAdapter` 封装 Bukkit 主线程调度。
- Folia 使用 `FoliaAwareSchedulerAdapter` 反射调用 region、entity、global scheduler。
- 所有 `World`、`Block`、`Entity` 访问只发生在事件线程或调度到对应 region/entity/global 的任务中。
- 配置、语言、状态仓储文件全部在异步 IO 线程读取和写入。

## 为什么不会主线程阻塞

插件没有调用 `saveDefaultConfig()` 或同步仓储写入。`ConfigService`、`LangService`、`LocalAsyncRepository` 都通过异步 executor 执行文件 IO。树木检测只在事件线程执行有界扫描，并受 `max-log-nodes`、`max-leaf-scan-blocks`、`max-horizontal-distance` 和 `max-tree-height` 限制。

## 复制漏洞与物品丢失控制

LifeLink 不取消合法砍伐事件并重放掉落，因此不会复制原木掉落，也不会破坏原版掉落链。补种只放置系统树苗，不额外掉落原木。失败策略 `DROP_SAPLING` 只在任务失败终态执行一次，位置预约和任务状态机防止重复掉落。补种校验要求目标方块为空或已经是对应树苗，不会覆盖玩家方块导致物品丢失。

## Folia 兼容设计

Folia 下按树根所在 chunk 调度 region 任务，玩家消息通过 entity scheduler 发送。跨 chunk 的多树苗方案会被保守拒绝，避免跨 region 同步写世界对象。插件使用反射检测 Folia API，因此同一 jar 可在 Spigot / Paper 上加载。

## 权限节点

- `lifelink.admin`：管理员总权限。
- `lifelink.reload`：允许 `/lifelink reload`。
- `lifelink.recover`：允许 `/lifelink recover`。
- `lifelink.admin.sapling-mode`：允许管理员切换个人树苗托管模式。
- `lifelink.bypass.sapling-protection`：绕过系统树苗保护。
- `lifelink.bypass.managed-tree`：绕过托管树砍伐限制。

## 配置说明

主要配置在 `config.yml`：

- `language.default`：语言文件名，例如 `zh_cn` 或 `en_us`。
- `harvest.allow-empty-hand`：是否允许空手砍树也触发补种，默认开启。
- `harvest.sneak-bypass-replant`：是否允许玩家潜行砍树时跳过自动补种，适合处理插件安装前已有的人工树或特殊装饰树。
- `harvest.allowed-tools`：允许触发补种的工具。
- `protection.protect-system-saplings`：是否保护系统树苗。
- `protection.protect-managed-trees`：是否限制托管树合法砍伐方式。
- `protection.bypass-requires-sneaking`：管理员 bypass 权限是否必须潜行才生效；默认开启，避免 OP 普通挖掘时误以为保护失效。
- `replant.failure-strategy`：`CANCEL`、`RETRY`、`DROP_SAPLING`、`RECORD_AND_RECOVER`。
- `replant.retry-delay-ticks`：重试间隔。
- `replant.max-retry-attempts`：最大重试次数。
- `replant.required-clear-height`：树苗上方最小净空，`0` 表示允许砍树根后立即补种，即使上方仍有原木。
- `replant.root-wait-retry-delay-ticks`：从树顶或树中段开始砍时，等待树根被砍掉的检查间隔。
- `replant.root-wait-timeout-seconds`：等待树根被砍掉的最长时间，默认 1800 秒，避免 2x2 树砍得慢导致任务过早过期。
- `effects.plant-particles.*`：系统补种树苗成功后的粒子效果配置。
- `plants.sugar-cane.*`：甘蔗留苗配置。
- `detection.*`：树木扫描范围、节点上限和树叶判定阈值。
- `persistence.file-name`：异步状态文件路径。
- `persistence.flush-delay-millis`：批量落盘延迟毫秒数，默认 250，同一窗口内的多次状态变更只写一次文件。

## 语言文件

语言文件位于 `src/main/resources/lang/`，运行后复制到插件数据目录。`messages.prefix` 是 MiniMessage 前缀，其他消息通过 `<prefix>` 引用。所有颜色使用十六进制 RGB MiniMessage 标签。支持占位符：`<player>`、`<tree_type>`、`<world>`、`<x>`、`<y>`、`<z>`、`<reason>`。

## 崩溃恢复与安全回滚

`LocalAsyncRepository` 使用单写线程和临时文件原子替换保存状态。启动恢复会：

- 重新调度未完成补种任务。
- 校验系统树苗是否仍存在，若已成长则升级为托管树。
- 校验托管树根部是否仍存在，若缺失则创建恢复补种任务。
- 按配置保留时间清理终态任务。

如果补种位置被占用、区块未加载或世界不可用，插件不会加载区块或覆盖方块，而是按失败策略重试、记录恢复、掉落树苗或回滚任务。
