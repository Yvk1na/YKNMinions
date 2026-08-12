# YknMinions

面向 Paper 1.21.10 的 Hypixel SkyBlock 风格小人插件。模型采用小型盔甲架、装备和工作挥臂动画；GUI 为 6 行布局，包含
15 格资源仓库、燃料栏、两个升级模块栏、收取、普通升级、告示牌快速升级和收起按钮。资源仓库在 1 级时开放 5 格，每升一级再开放 1 格，未开放位置以淡白色玻璃板锁定。战斗与动物类小人会在周围生成对应生物，转身后逐一击杀，并把产出直接存入资源仓库。

## 安装

1. 使用 Java 21 和 Paper 1.21.10。
2. 将 `target/YknMinions-1.0.0.jar` 放入服务器 `plugins` 目录。
3. MMOItems、ItemsAdder、CraftEngine 均为可选软依赖；需要对应物品时再安装。
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

小人主 GUI 每个服务器 tick 增量刷新仓库格子，无需关闭界面即可看到新收获、自动合成和领取后的数量变化；内容未改变时不会重复写入格子。

小人数据使用临时文件原子替换写入，并保留上一版 `data.yml.bak`。重启时若小人所在的岛屿世界尚未加载，该记录会原样保留，等到对应世界加载后再生成小人；单条记录暂时解析失败时也不会被下一次自动保存删除。若 `data.yml` 缺少有效数据节点，插件会优先读取备份，否则停止覆盖数据文件并输出错误日志。

小人只接受“无尽能源”“小型燃料”“中型燃料”和“超级燃料”，普通煤炭、木炭、煤炭块、木炭块与岩浆桶不再有效。有限燃料的燃烧时间位于 `config.yml` 的 `fuels.<id>.burn-time-seconds`，效率位于 `efficiency`；例如 `0.10` 表示提升 10%，实际速度倍率为 `1 + efficiency`。无尽能源不会耗尽或消耗，移出燃料栏后立即失效。超级燃料无法被右键倒出岩浆。修改配置后执行 `/minions reload` 即可热加载。

消息前缀可通过 `config.yml` 顶层的 `prefix` 修改，支持 `&` 颜色代码；执行 `/minions reload` 后立即生效，无需重启服务器。

史莱姆大小权重、每次击杀的随机掉落数量和转身后的攻击延迟，可在 `minions.yml` 的 `types.slime.slime-settings` 中设置。大史莱姆会按原版规则分裂为 2～4 个半尺寸史莱姆，并由小人以至少 1 秒的击杀间隔逐一击杀。

战斗与动物类小人包括史莱姆、岩浆史莱姆、末影人、僵尸、蜘蛛、骷髅、烈焰人、洞穴蜘蛛、鸡、牛、爬行者、猪、兔子和羊。史莱姆与岩浆史莱姆会随机大小并按原版尺寸分裂，其余类型不会；目标生物、每次击杀掉落数量和转身攻击延迟位于各类型的 `mob-settings`。

采集类小人包括煤、圆石、钻石、绿宝石、末地石、金、铁、橡木原木、黑曜石、石英、红石、沙子、雪、黏土和青金石。它们会在以自身为中心的 5×5 区域中工作（中心格保留给小人，共 24 个工作格），随机放置或挖掘配置的方块；没有自己生成的方块可挖时只会放置。小人只记录和挖掘自己放置的方块，工作区状态保存在 `data.yml`。煤、钻石、绿宝石、金、铁、石英、红石和青金石直接产出熔炼后的资源，黏土产出黏土球，雪产出雪球，其余按配置产出原方块。放置方块与产物可在各类型的 `mining-settings.block` 和 `drop` 中修改。

农业类小人包括仙人掌、胡萝卜、红蘑菇、灰蘑菇、地狱疣、马铃薯、甘蔗、小麦、西瓜和南瓜。它们同样使用 5×5 工作区，只收割成熟作物：普通作物及瓜茎使用湿润耕地，蘑菇使用菌丝，地狱疣使用灵魂沙，仙人掌使用间隔种植的沙地；甘蔗采用沙地种植列与水列交替，西瓜和南瓜采用茎列与结果列交替。胡萝卜、马铃薯、小麦、西瓜和南瓜小人的脚底会建立中心水源。小人自己种下并追踪的作物获得 50% 生长速度加成，不会影响附近玩家种植的作物；仙人掌和甘蔗也会由小人主动执行向上生长动作。小人收起时会清理自己种植的作物，并在未被玩家再次修改的格子中恢复原底层方块。作物类型位于 `farming-settings.crop`。

钻石、绿宝石、铁、金和青金石蔓延的概率与随机数量分别位于 `config.yml` 的 `diamond-spread`、`emerald-spread`、`iron-spread`、`gold-spread` 和 `lapis-spread`；修改后执行 `/minions reload` 即可热加载。

升级旧版本时，缺少的内置类型会自动合并进服务器现有的 `minions.yml`，已有类型配置不会被覆盖。

“自动合成”会自动发现 Bukkit 中注册的 3×3 同材料压缩配方，也会读取 CraftEngine 的公开配方 API。MMOItems 工作站配方、
ItemsAdder/CraftEngine 未注册到 Bukkit 的特殊配方可在 `auto-craft-recipes.yml` 中明确声明，物品身份和完整 ItemStack 元数据都会保留。
