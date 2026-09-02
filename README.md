# YknMinions

面向 Paper 1.21.10 及以上版本的 Hypixel SkyBlock 风格小人插件。模型采用小型盔甲架、装备和工作挥臂动画；GUI 为 6 行布局，包含
15 格资源仓库、燃料栏、两个升级模块栏、收取、普通升级、告示牌快速升级和收起按钮。资源仓库在 1 级时开放 5 格，每升一级再开放 1 格，未开放位置以淡白色玻璃板锁定。战斗与动物类小人会在周围生成对应生物，转身后逐一击杀，并把产出直接存入资源仓库。

## 安装

1. 使用 Java 25 和 Paper 1.21.10 或更高版本；Skyblock-Core API 本身由 Java 25 编译，因此服务器也必须运行 Java 25。
2. 先安装 `Skyblock-Core-0.1.0-SNAPSHOT.jar` 及其依赖，再将 `target/YknMinions-1.1.0.jar` 放入服务器 `plugins` 目录；Skyblock-Core 是领取小人仓库所需的硬依赖。
3. MMOItems、ItemsAdder、CraftEngine、AuraSkills、EcoCollections 均为可选软依赖；需要对应物品、技能经验或图鉴进度时再安装。
4. 启动服务器，修改生成的 `config.yml`、`minions.yml`、`auto-craft-recipes.yml`，然后执行 `/minions reload`。

## 命令

- `/minions get diamond_spread`：获得“钻石蔓延”。
- `/minions get emerald_spread|iron_spread|gold_spread|lapis_spread`：获得对应资源蔓延物品。
- `/minions get auto_craft`：获得“自动合成”。
- `/minions get infinite_energy|small_fuel|medium_fuel|super_fuel`：获得四种小人专用燃料。
- `/minions give slime [1-11] [玩家]`：获得可放置的默认史莱姆小人。
- `/minions level slime <当前等级>`：打开升级材料编辑器，保存该等级升到下一级所需的全部材料。
- `/minions admin minions`：先打开小人类型列表，点击类型后进入该小人的全部等级 GUI，再左键选择等级领取。
- `/minions admin special`：打开插件全部特殊物品的管理 GUI，左键点击即可领取。
- `/minions reload`：重载全部配置。

`level` 与 `admin` 管理命令默认仅服务器管理员（OP）可用，对应权限为 `yknminions.level` 和
`yknminions.admin`。材料编辑器前 5 行共 45 格均可放入样品；橡木箱子保存、屏障退出，关闭界面后样品会完整退还。
管理员列表每页显示 45 个物品，内容超过一页会自动分页；列表底部箭头左键进入下一页、右键返回上一页。小人等级 GUI 底部依次为返回、关闭和下一个小人；最后一个类型继续切换时会循环回第一个。

“自动合成”漏斗与“无尽能源”沉重核心只能作为小人功能物品使用，玩家无法将它们作为方块放置；普通漏斗和普通沉重核心不受影响。

## 自定义物品写法

```yaml
minecraft:diamond
mmoitems:MATERIAL:ENCHANTED_SLIME
itemsadder:namespace:item_id
craftengine:namespace:item_id
```

升级材料配置在 `minions.yml` 的 `types.<id>.levels.<目标等级>.upgrade-materials`。快速升级会将当前等级之后直到目标等级的材料全部累加。
材料编辑器会通过物品本身识别原版、MMOItems、ItemsAdder 与 CraftEngine 物品，保存时自动写成上述标识；相同物品会合并数量，并覆盖该次升级原有的材料列表。

玩家主动收取小人仓库时，插件会先把逐条领取记录以 `PREPARED` 状态持久化并锁定对应物品，再调用 Skyblock-Core 的 `DetailedItemDeliveryApi`，使用 `INVENTORY_THEN_STASH`、`ALLOW_PARTIAL` 和 `REJECT_TO_SOURCE` 发放。`COMPLETED` 或 `PARTIAL` 会按 Core 返回的逐条实际数量扣减，只把未交付余量留回小人仓库；`REJECTED` 会完整解除锁定，异常会保留原 claim。结算后的 `data.yml` 保存失败时会恢复内存中的 reservation，并用原 `operationId` 重试，避免重复发放或吞物。

内置 `minions.yml` 已为全部 39 种小人启用 AuraSkills，并按
[Hypixel SkyBlock Minions 表](https://hypixelskyblock.minecraft.wiki/w/Minions)的主产物经验值配置。
战斗类对应 AuraSkills 的真实技能 ID `fighting`；自动合成可能产生的原版压缩方块通过
`equivalent` 折算成基础物品数量：

```yaml
types:
  coal:
    # ...原有配置...
    skill:
      provider: auraskills
      id: mining
      rewards:
        - { item: "minecraft:coal", xp-per-base-unit: 0.3, equivalent: 1 }
        - { item: "minecraft:coal_block", xp-per-base-unit: 0.3, equivalent: 9 }
    collection:
      provider: ecocollections
      id: coal
```

EcoCollections 默认只启用本服 2026.31 配置中确实存在的 31 个 ID。鸡、牛、猪、兔、羊、
沙、雪和黏土目前没有对应 ID，因此没有写入会被适配器拒绝的虚假 collection；洞穴蜘蛛与
普通蜘蛛共用现有的 `spider`，红/棕蘑菇共用 `mushroom`。未安装对应插件或 ID 无效时，
只跳过该项集成，不影响 Minion 生产与收取。

技能经验只按本次成功进入背包或 Stash 的 claim line 计算，公式为 `成功交付数量 × equivalent × xp-per-base-unit`，并在 Minion 结算保存成功后交给 AuraSkills。旧版在途 claim 没有技能快照，因此不会按新配置追溯补发经验。EcoCollections 只在主产物实际进入小人仓库时增加；自动合成结果、扩散奖励和玩家收取不会重复计数。owner 离线时，增量聚合保存在该 Minion 的 `pending-collections`，玩家上线后通过 EcoCollections 正常增量 API 冲销。

两个第三方 API 都没有 operation-key 幂等接口，因而无法与 `data.yml` 组成跨插件事务：技能经验采用保存后唯一一次调用，极端崩溃窗口可能漏发但不会为同一已清除 claim 自动重发；EcoCollections pending 在“API 已成功、清零尚未保存”的极端窗口也无法同时保证绝不重复和绝不遗漏。日志会保留 operation ID、逐条成功量与经验值供审计。

小人数据使用临时文件原子替换写入，并保留上一版 `data.yml.bak`。重启时若小人所在的岛屿世界尚未加载，该记录会原样保留，等到对应 `WorldLoadEvent` 后再生成小人；单条记录暂时解析失败时也不会被下一次自动保存删除。若 `data.yml` 缺少有效数据节点，插件会优先读取备份，否则停止覆盖数据文件并输出错误日志。
小人主 GUI 每个服务器 tick 增量刷新仓库格子，无需关闭界面即可看到新收获、自动合成和领取完成后的数量变化；内容未改变时不会重复写入格子。

小人只接受“无尽能源”“小型燃料”“中型燃料”和“超级燃料”，普通煤炭、木炭、煤炭块、木炭块与岩浆桶不再有效。有限燃料的燃烧时间位于 `config.yml` 的 `fuels.<id>.burn-time-seconds`，效率位于 `efficiency`；例如 `0.10` 表示提升 10%，实际速度倍率为 `1 + efficiency`。无尽能源不会耗尽或消耗，移出燃料栏后立即失效。超级燃料无法被右键倒出岩浆。修改配置后执行 `/minions reload` 即可热加载。

消息前缀可通过 `config.yml` 顶层的 `prefix` 修改，支持 `&` 颜色代码；执行 `/minions reload` 后立即生效，无需重启服务器。

史莱姆大小权重、每次击杀的随机掉落数量和转身后的攻击延迟，可在 `minions.yml` 的 `types.slime.slime-settings` 中设置。大史莱姆会按原版规则分裂为 2～4 个半尺寸史莱姆，并由小人以至少 1 秒的击杀间隔逐一击杀。

战斗与动物类小人包括史莱姆、岩浆史莱姆、末影人、僵尸、蜘蛛、骷髅、烈焰人、洞穴蜘蛛、鸡、牛、爬行者、猪、兔子和羊。史莱姆与岩浆史莱姆会随机大小并按原版尺寸分裂，其余类型不会；目标生物、每次击杀掉落数量和转身攻击延迟位于各类型的 `mob-settings`。

采集类小人包括煤、圆石、钻石、绿宝石、末地石、金、铁、橡木原木、黑曜石、石英、红石、沙子、雪、黏土和青金石。它们会在以自身为中心的 5×5 区域中工作（中心格保留给小人，共 24 个工作格），随机放置或挖掘配置的方块；没有自己生成的方块可挖时只会放置。小人只记录和挖掘自己放置的方块，工作区状态保存在 `data.yml`。煤、钻石、绿宝石、金、铁、石英、红石和青金石直接产出熔炼后的资源，黏土产出黏土球，雪产出雪球，其余按配置产出原方块。放置方块与产物可在各类型的 `mining-settings.block` 和 `drop` 中修改。

农业类小人包括仙人掌、胡萝卜、红蘑菇、灰蘑菇、地狱疣、马铃薯、甘蔗、小麦、西瓜和南瓜。它们同样使用 5×5 工作区，只收割成熟作物：普通作物及瓜茎使用湿润耕地，蘑菇使用菌丝，地狱疣使用灵魂沙，仙人掌使用间隔种植的沙地；甘蔗采用沙地种植列与水列交替，西瓜和南瓜采用茎列与结果列交替。胡萝卜、马铃薯、小麦、西瓜和南瓜小人的脚底会建立中心水源。小人自己种下并追踪的作物获得 50% 生长速度加成，不会影响附近玩家种植的作物；仙人掌和甘蔗也会由小人主动执行向上生长动作。小人收起时会清理自己种植的作物，并在未被玩家再次修改的格子中恢复原底层方块。作物类型位于 `farming-settings.crop`。

钻石、绿宝石、铁、金和青金石蔓延的概率与随机数量分别位于 `config.yml` 的 `diamond-spread`、`emerald-spread`、`iron-spread`、`gold-spread` 和 `lapis-spread`；修改后执行 `/minions reload` 即可热加载。

升级旧版本时，缺少的内置类型会自动合并进服务器现有的 `minions.yml`；已有类型缺少的
`skill` / `collection` 节点也会安全补齐。已有类型配置和自定义奖励节点不会被覆盖。

“自动合成”会自动发现 Bukkit 中注册的 3×3 同材料压缩配方，也会读取 CraftEngine 的公开配方 API。MMOItems 工作站配方、
ItemsAdder/CraftEngine 未注册到 Bukkit 的特殊配方可在 `auto-craft-recipes.yml` 中明确声明，物品身份和完整 ItemStack 元数据都会保留。
