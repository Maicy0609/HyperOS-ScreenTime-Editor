# ScreenTime Editor (HyperOS / Marble)

针对 Redmi Note 12 Turbo（`marble`）、HyperOS `OS3.0.5.0.VMRCNXM`、
Android 15（SDK 35）、MISettings `15.03.1211.10-phone` 的今日屏幕使用时长编辑器。

这是一个 **Vector / LSPosed 模块**。控制器脚本以 gzip 资源内嵌在 APK 中，
安装后打开 APK 即自动释放到 `/sdcard/Download/edit_today_screentime.sh`。
模块只替换页面展示数据，不修改系统 UsageStats、Room 数据库或 MMKV。

## 原理

MISettings 的今日统计不在 `app_usage` 表中，也不在 MMKV 的 `app_usage_*` 缓存中，
而是页面加载时实时查询 `UsageStatsManager` 后组装成页面模型。模块只 Hook
`com.xiaomi.misettings` 进程：

- 单日详情页：`y9.u.e(DetailPageModel)`
  - 仅处理“日”标签、`range == 0`（今天）
  - 写入真实数据快照 `current.txt`
  - 存在 `plan.txt` 时替换 `DetailPageModel`
- “健康使用手机”首页：`HomePageModel.<init>`
  - 替换首页今日总时长、7 天图表中今天的柱、应用使用卡片
  - 历史日期柱保持不变

`plan.txt` 有两种模式：

- `mode=delta`（默认）：页面显示“设定目标 + 基线之后新增的真实使用时间”。
  每个应用单独计算增量并向下取整到分钟，所以总时长会随着真实使用继续增长。
- `mode=fixed`：页面固定显示目标值，不随真实使用增长。旧版 plan 没有
  `mode` 字段时按 `fixed` 兼容。

单日图表会读取原始 24 小时分布，按最终显示总时长等比例缩放，因此柱状图的小时
位置不会全部堆到当前小时，且 24 小时合计等于应用明细合计。

## 数据文件

目录：

```text
/data/user/0/com.xiaomi.misettings/files/screentime/
```

`current.txt` 是页面实时数据的诊断快照，由模块在替换之前写入：

```text
date=1793376000000
user=0
total_usage=10200000
total_period=10200000
com.tencent.mm|7200000|0
```

当前日期的 `plan.txt` 由控制脚本写入，同时会按日期归档到：

```text
plans/<dayStartMs>.txt
baselines/<dayStartMs>.txt
```

历史日期页面会读取对应日期的归档计划；控制器菜单 3 只删除今天，不会删除历史。
菜单 3 可以修改昨天、前天或手动输入日期；历史日期使用 `fixed` 模式，
只影响该日期的页面显示。今天页面的“较昨日”比较也会在昨天存在 fixed 计划时
使用昨天设置的目标值，避免显示真实昨日数据而露出差异。

菜单 6 锁定今天的显示时长：锁定后页面不再随真实使用增长；计划中的
`lock=1` 和 `lock.txt` 会持久保存，重启后仍识别。再次选择菜单 6 会解除锁定，
解锁时会用锁定值重设基线，页面不会突然跳高，之后继续正常累计。

`plan.txt` 格式：

```text
date=1793376000000
user=0
mode=delta
base_at=1793376000
base_total=16658045
total=170
com.tencent.mm|77|107510
com.openai.chatgpt|19|1826545
```

第三列是该应用在计划创建时的真实 usage 基线，用于历史日期没有 baseline 文件时的回退。

- `total` 是设定时的目标总分钟。
- `delta` 模式下，模块在计划生效后的**第一次页面加载**捕获
  `baseline.txt`（每个应用的真实 usage）。因此刚应用计划时页面精确等于目标值，
  之后才按真实增量增长。
- 后续显示：

```text
应用显示分钟 = 目标分钟 + floor(max(0, 当前真实 usage - 基线 usage) / 60000)
页面总时长 = Σ 应用显示分钟
```

- 每次重新写入 `plan.txt`，控制器会删除 `baseline.txt`，下一次页面加载重新建立基线。
- `fixed` 模式只显示目标分钟。
- 模块校验 `Σ 目标分钟 == total`；不一致会拒绝替换并写 Vector 日志。
- 只匹配 `date` 为今天、`user=0` 的计划。
- `current.txt` 始终保留真实数据，不会被替换后的目标值覆盖，方便重新设定基线。

## 要求

- Root（KernelSU / Magisk）
- Vector 或 LSPosed
- 模块作用域：`com.xiaomi.misettings` 和 `com.miui.securitycenter`，用户 0
- MISettings 版本必须匹配 `15.03.1211.10-phone` / `251211320`

## 构建

GitHub Actions 手动触发 `build-module`，或本地：

```sh
ANDROID_HOME=/path/to/android-sdk bash module/build.sh
```

产物：

```text
module/dist/screentime-editor-module.apk
```

控制器脚本会先 gzip 压缩，再作为
`assets/edit_today_screentime.sh.gz` 打进 APK。

流程：`javac` → `d8` → `aapt2 link` → 打包 `classes.dex` /
`assets/xposed_init` / `assets/edit_today_screentime.sh.gz` →
`zipalign` → `apksigner`。

### 签名

仓库 **不包含任何 keystore**。CI 可通过仓库 Secret
`ANDROID_KEYSTORE_BASE64` 提供固定签名密钥；未提供时 `build.sh` 在
`module/build/` 生成一次性调试密钥，因此不同构建的 APK 签名可能不同，
覆盖安装前可能需要先卸载旧版本。

生成 Secret 内容：

```sh
base64 -w0 your-release.keystore
```

## 安装

```sh
su
pm install -r /sdcard/Download/screentime-editor-module.apk
```

安装后**打开一次 ScreenTime Editor 应用**。首次启动会请求“所有文件访问”
权限（`MANAGE_EXTERNAL_STORAGE`）：

- Android 11+：跳转到“所有文件访问”设置页，授予后返回；
- Android 10：请求存储权限；
- 授权后自动删除旧文件并释放：
  `/sdcard/Download/edit_today_screentime.sh`
- 如果已有旧脚本，会先删除再覆盖。

在 Vector 中启用模块，并把作用域设为：

```text
com.xiaomi.misettings/0
com.miui.securitycenter/0
```

`com.miui.securitycenter` 用于“省电与电池”页面的“使用时长”显示。

然后强制停止 MISettings：

```sh
am force-stop com.xiaomi.misettings
am start -n com.xiaomi.misettings/com.xiaomi.misettings.usagestats.UsageStatsMainActivity
```

## 使用

```sh
su
sh /sdcard/Download/edit_today_screentime.sh
```

先打开一次“设置 → 健康使用手机 → 今日”，等页面加载完成，再运行脚本：

1. 查看今日统计
2. 修改今日统计（输入总分钟，逐个或按比例分配，差额必须为 0）
3. 修改历史日期（先打开该日期页面）
4. 恢复今天实时数据（只删除今天，保留历史日期）
5. 查看 plan / 日志
6. 锁定/解除锁定今天的时长
7. 强制重启设置
8. 退出

恢复：

```sh
rm -f /data/user/0/com.xiaomi.misettings/files/screentime/plan.txt
rm -f /data/user/0/com.xiaomi.misettings/files/screentime/current.txt
am force-stop com.xiaomi.misettings
```

## 范围与限制

- 只注入 `com.xiaomi.misettings`。
- 只替换今天的页面模型；昨天及更早历史数据不修改。
- 不写入系统 UsageStats、`appusagestats.db`、Room `screen-time-database` 或 MMKV。
- 不修改“设备使用限制 / 应用使用限制”的配置。
- `com.miui.securitycenter` 的“省电与电池 → 使用时长”显示 `plan.txt` 的 `total` 目标分钟；它只改显示，不修改电池统计。
- 限额相关页面如果读取实时统计，可能仍显示原始数据；本模块只负责页面展示。
- 依赖 MISettings 内部类名和方法名；MISettings 升级后 Hook 点可能失效。
