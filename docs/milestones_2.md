# Sublevel Milestones 2

目标不是完整复刻 Sable，而是逐步把 physx4mc 的 sublevel 接进 Minecraft 已有的 chunk、tick、交互和物理同步入口。物理几何、断裂检测、terrain obstacle、VoxelShape 重构继续沿用 physx4mc 现有架构。

## M1: Plot Chunk 活化

目标：让原版认为 sublevel 的 plot chunk 是 loaded/ticking chunk。

验收：
- 红石线、中继器、观察者、按钮、拉杆在 sublevel 内能正常 tick。
- `ServerChunkCache` / `ChunkMap` 查询能拿到 plot chunk。
- plot chunk 创建、重建、移除时不会残留 tick container 或 chunk holder。

当前状态：已有第一版实现。`shouldTickBlocksAt`、`ServerChunkCache.isPositionTicking`、`ServerLevel.isPositionTickingWithEntitiesLoaded` 都会放行 plot chunk，避免 scheduled tick 因 entity manager 未确认 loaded 而不执行；sublevel 初次装载后会对 plot 内方块补一次邻居更新，并在后续数个 server tick 内继续做短暂自举更新；自举更新会同时模拟输入侧每个邻居变化和输出侧邻居通知；中继器/比较器会在自举期补一个短 block tick，并直接执行一次 vanilla tick 判断，用于启动红石 loop。需要游戏内验证红石 scheduled tick。

## M2: Scheduled Tick 迁移

目标：捕获 sublevel 时，把源世界里已经排队的 block/fluid ticks 搬到 plot chunk。

验收：
- 正在延迟中的中继器、比较器、水/岩浆更新在 sublevel 化后不丢 tick。
- 捕获前已排队的 tick 能映射到 plot 坐标继续执行。
- 移除或还原 sublevel 时不会留下错误 tick。

当前状态：已有第一版初次 assemble 迁移，使用 vanilla `LevelTicks.copyArea` / `clearArea` 把源区域的 block/fluid scheduled ticks 移到 plot 坐标；plot chunk 重建时会保留旧 tick 队列，restore 时会把 plot tick 迁回源坐标，sublevel 删除时会清理 plot tick。需要游戏内验证延迟红石和流体更新。

## M3: 实体感知映射

目标：让 plot chunk 里的方块能感知真实世界坐标里的玩家/实体。

验收：
- 玩家站在移动 sublevel 上能触发压力板。
- 绊线、探测铁轨等依赖实体查询的方块具备同类基础能力。
- 只在 plot chunk 查询实体时做投影，避免影响普通世界查询。

当前状态：已有第一版 `entityInside` 投影 tick 和 plot AABB 实体查询投影，需要游戏内验证压力板、绊线等具体方块。

## M4: 方块状态与物理同步查漏

目标：plot chunk 中方块变化后，物理体、断裂结构、terrain obstacle、显示 chunk 保持一致。

验收：
- 破坏、放置、红石改变方块状态后，物理体与 plot chunk 方块一致。
- 无碰撞方块变化不会破坏 sublevel 物理体。
- sublevel 拆分、移除、还原时不会留下旧物理或旧显示状态。

当前状态：开始补一致性边界。物理 body 重建改为 replacement body 和 plot chunk 重建都成功后再替换旧 body；body id 变化后会刷新 debug visual，并同时按旧 body pose 与新 body pose 扫描清理 orphan visual；破坏路径不再提前局部删除 visual，而是由 rebuild/remove 统一清理和重建；debug visual 启用状态改为显式记录，实体会写入 sublevel 专属 tag，清理时会额外扫描并删除 orphan visual，并兼容清理旧 custom name visual；客户端收到 plot chunk 全量包时会替换整个 `LevelChunk` 对象；plot chunk rebuild 的 full chunk resync 改为 tick 末尾合并发送，避免同一 vanilla/redstone 更新调用栈里的后续 block update 覆盖最终状态；包含 `MOVING_PISTON` 的临时 chunk rebuild 不发送 full chunk resync，也跳过普通单块变化广播，避免客户端通过普通方块包收到无法还原 BlockEntity 的 moving piston；sublevel 拆分时会把原 plot chunk 中对应方块的 block/fluid scheduled ticks 迁到 child plot；活塞 block event 广播会按物理位置计算可见范围，活塞 base/head/moving piston 已加入依赖删除。需要游戏内验证破坏、放置、拆分、restore/remove、活塞以及 visual 是否残留。

## M5: BlockEntity 兼容

目标：常见 block entity 在 sublevel 内能稳定保存状态、tick、重建后不丢关键数据。

验收：
- 箱子、告示牌、漏斗、比较器相关方块至少不明显错乱。
- plot chunk 重建后 block entity ticker 不重复、不丢失。
- block entity NBT 与运行时状态的同步策略明确。

当前状态：开始覆盖发射器/投掷器这类 block entity 交互。plot 坐标中通过 `addFreshEntity` 生成的物品、投射物会在加入世界前映射到 sublevel 物理位置，并旋转初速度方向。需要游戏内验证发射器/投掷器的库存变化、发射实体位置和速度。

## M6: 生命周期与清理

目标：sublevel 创建、重建、移除、服务器关闭时资源清理一致。

验收：
- 没有残留 plot chunk、tick container、block entity ticker、物理 body、客户端假 chunk。
- 服务器关闭、维度卸载、sublevel 删除路径都能安全释放资源。
- 如果暂不做完整持久化，运行时清理边界必须稳定。

## M7: 兼容测试集

目标：建立固定测试场景，用来反复验证 sublevel 行为。

验收：
- 覆盖红石钟、压力板门、绊线、活塞、无碰撞方块、block entity、拆分、移除。
- 每次改动后能快速复现核心场景。

## 建议顺序

优先顺序：M1 -> M2 -> M3 -> M4 -> M5 -> M6。

M7 从 M1 后开始积累，不等到最后再补。
