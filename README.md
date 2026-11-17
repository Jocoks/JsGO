# JsGO

面向 AI 训练数据的 jsonl 编辑器，Android 应用。

- 应用名：JsGO
- 包名：`com.jocoks.jsgo`
- 形态：WebView 壳 + 单个内嵌 HTML 页面，无网络、无第三方库、无后端
- 数据：直接打开 / 保存 `.jsonl` 文件，一个文件一个训练集

## 做什么

| 能力 | 说明 |
|---|---|
| 解析 | 读入 jsonl，逐行解析成条目；坏行单独标出，不拖垮整份文件 |
| 分类 | 同一句 user 提示词的条目自动归为一个分类；分类内可再按子标签（`meta.sub`）筛选 |
| 编辑 | 逐条改 role / content，增删轮次，增删自定义字段，坏行原文修复 |
| 生成 | 新建条目，先 user 后 assistant 空模板 |
| 批量修改 | 按关键词/role/字数/错误筛选后：查找替换、重写 user、统一子标签、去首尾空白、按内容去重、删除匹配 |
| 保存 | 分块写回文件；首次另存弹系统对话框，之后复用同一文件直接覆盖 |

## 数据格式

一行一条，`messages` 必需，`id` 可有可无：

```jsonl
{"id":"1","messages":[{"role":"user","content":"写一段夜晚街道的片段。"},{"role":"assistant","content":"路灯把水洼照成一片一片的碎光。"}]}
```

- 必填：`messages`，`role` 只有 `user` / `assistant` / `system`
- `id` 可选：数据里本来有 `id`，界面就拿它当标题；没有就显示 `#行号`。程序不会自动生成，也不会改写这个字段——训练器不读它，纯粹是给人看的编号
- 手动填的编号如果和别的条目重复，保存时会被拦下
- 其它字段（`_src`、`meta` 等）原样保留，不认识的 key 不会被改动
- 子分类标签用 `meta.sub`（字符串）。不写这个字段完全可以；写了就在分类里按标签分组，`meta` 为空时保存会自动去掉
- 坏行不丢：没修好之前保存，原文按原样写回
- 单条正文按 3000 汉字设计，编辑与导出不截断

## 界面

- 主色：米白 `#FAF7F0`
- 辅色：浅绿 `#A9C9A4`
- 文字：墨绿灰 `#3B3A32`
- 所有色值、圆角、间距集中在 `index.html` 顶部 `--*` 变量，改主题只改这一处

## 代码结构

`app/assets/index.html` 单文件，内部分层：

```
配置层 CONFIG        —— 分页数等常量
工具层 util          —— 转义、防抖、toast、id、大文本分块过桥
数据层 Jsonl         —— parse / stringify / validate / summary / chars / searchText
分类层 Cats          —— 按首条 user 归并分类、子标签统计与写入（meta.sub）
状态层 State         —— 条目、筛选、编辑草稿、撤销快照
操作层 Ops           —— 单条增删改
批量层 Batch         —— 条件筛选 + replace / rewrite / trim / dedupe / remove
存储层 Persist       —— 工作区自动保存（写 App 私有 workspace.json）
渲染层 UI            —— 只读 State，不直接改数据（分类视图 / 条目视图 / 编辑面板）
事件层 Events        —— 统一委托，调用 Ops / Batch
```

规则：

- 数据修改只走 `Ops` / `Batch`，UI 层不直接改 `State.items`
- 批量操作前自动存快照，支持一次撤销
- 新增功能先加接口，再挂 UI

## 页面与 Java 的桥

大文本一律分块（64K）过桥，避免 WebView 参数超限。

| JS 侧 | 作用 |
|---|---|
| `JsGO.storeBegin / storeAppend / storeCommit` | 分块写工作区 `workspace.json` |
| `JsGO.storeLength / storeChunk(a,b)` | 分块读回工作区 |
| `JsGO.openFile()` | 系统文件选择器打开 jsonl |
| `JsGO.openLength / openChunk(a,b)` | 分块取回刚打开的文件内容 |
| `JsGO.saveBegin / appendText / saveCommit` | 分块写回文件；没有绑定文件时弹保存对话框 |
| `window.JsGO_receive(text, action)` | Java 回调页面，`action` = `open` / `save` / `error` |

浏览器的降级路径：没有 `JsGO` 时用 `localStorage` 存工作区、`Blob` 下载导出，方便在桌面浏览器里调试页面本身。

## 构建

```bash
cd /root/duihua/jsgo
./build.sh
```

产物：`dist/JsGO-unsigned.apk`（未签名，自行签名安装）。

工具链注意（本机为 aarch64）：

- `aapt2` / `zipalign` 用 `/usr/lib/android-sdk/build-tools/29.0.3` 的版本（`/root/build/sdk/build-tools/36.0.0` 里的是 x86-64，跑不起来）
- `d8` 用 `/root/build/sdk/build-tools/36.0.0/lib/d8.jar`，`java -cp` 调用
- 平台 jar 用 `/root/build/sdk/platforms/android-30/android.jar`（aapt2 2.19 读不了 android-36 的资源表），manifest 里仍声明 `targetSdkVersion 36`

## 目录

```
jsgo/
  README.md
  build.sh
  dist/JsGO-unsigned.apk
  app/
    AndroidManifest.xml
    java/com/jocoks/jsgo/MainActivity.java
    assets/index.html
    res/values/strings.xml
```

## 变更记录

- v9 三级导航：一级 = 系统提示词分类（色块头像 + 身份名 + 要求摘要），二级 = 该分类下的子标签（含「全部条目」「未加标签」），三级 = 具体条目；二级/三级点「新建」自动带入当前分类的提示词与当前标签，返回键逐级后退
- v8 分类改为系统提示词：分类 = 第一条 `system` 消息，没有 `system` 的归到「未设系统提示词」；分类卡片改成色块头像 + 身份名 + 要求摘要 + 条数/字数
- v7 应用图标：米白圆角底 + 浅绿行号条 + 墨绿数据行，五档 mipmap
- v6 返回键：targetSdk 36 下系统启用预测性返回，`onBackPressed` 不再被调用导致返回直接退应用；改为反射注册 `OnBackInvokedDispatcher` 回调（平台 jar 是 android-30，不硬引用 API 33 类），`onBackPressed` 保留作低版本兜底，350ms 防抖
- v5 编号改为只读标题：数据里有 `id` 就显示它，没有显示 `#行号`；不生成、不补号、不改写已有 id，只在手动填写重复时拦截
- v4 编号（已废弃）：自动补号/自动发号，方向错误已回退
- v3 分类与标签：分类视图（同一 user 提示词归并）、子标签 chips 与批量统一标签、编辑框按内容自动长高（上限 70% 视口后内部滚动）、补状态栏/导航栏/输入法内边距
- v2 补齐外壳与构建：MainActivity（SAF 打开/保存、返回键回传、edge-to-edge 内边距）、工作区持久化改为私有目录 `workspace.json`、大文本分块过桥、坏行原文保留、导出改为全量（不受筛选影响）
- v1 初始版本：解析、单条编辑、新建、批量替换/去重、导入导出
