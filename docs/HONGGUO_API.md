# 红果短剧接口参考

本文件整理红果短剧（`com.phoenix.read`，内容侧与番茄小说同源）在**匿名设备**下可用的接口。
内容分两层：

- **官网层**（`https://hongguoduanju.com`）：Next.js SSR 页面，数据藏在 HTML 的 `_ROUTER_DATA` 里，
  不需要签名，负责分类、片库目录、榜单、详情、官网搜索（App 搜索不可用时的兜底）。
- **App 层**（`https://api5-normal-*.fqnovel.com`）：字节系 App 接口，需要一整套设备参数与签名头，
  负责 App 搜索、播放地址解析、弹幕。

整理来源与可信度：

| 来源 | 用途 |
| --- | --- |
| `kotlin-tv/core`（本工程 Kotlin 实现，逐行核对） | 字段名、参数、签名与响应结构的主依据 |
| `server/vendor/hongguo.cjs`（本工程 Node 实现，同一套接口的另一份实现） | 交叉验证官网路由名与榜单路线 |
| Android 12 模拟器 + 系统 CA + Frida 挂钩 `SSL_write`/`SSL_read` 实测 | 验证请求头、响应压缩（brotli）与匿名采样结论 |
| `woshishiq1/drpys`（22261ad）、`zhenyong97/hongguo-downloader` | 签名算法与协议变换的移植来源（GPL-3.0） |

> 所有内容都是**客户端接口的逆向整理**，不是官方开放 API，也不承诺长期稳定。
> 平台改字段、改签名、关接口都可能发生；本文件描述的是当前实现所依赖的行为。

---

## 1. 域名与线路

| 用途 | 地址 |
| --- | --- |
| 官网 / SSR 数据 | `https://hongguoduanju.com` |
| App 接口（主） | `https://api5-normal-sinfonlinea.fqnovel.com` |
| App 接口（备用） | `https://api5-normal-sinfonlinec.fqnovel.com`、`https://api5-normal-sinfonlineb.fqnovel.com` |
| App 接口（备用） | `https://api5-normal-lf.fqnovel.com`、`https://api5-normal-lq.fqnovel.com`、`https://api5-normal-hl.fqnovel.com` |
| 播放地址解析 | `https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/` |
| 弹幕 | 上述六条线路，按 `sinfonlinea → c → b → lf → lq → hl` 顺序失败转移 |
| 视频 CDN | 由播放接口返回的 `fallback_api` 给出，域名随资源与调度变化，不要写死 |

线路都是同一套接口的不同入口；单条失败（超时 / 非 2xx / 空响应）时依次换下一条，
弹幕窗口是唯一实现了「六线轮询」的调用，其余接口按需换线。

---

## 2. 通用约定

### 2.1 设备参数（query 里的 `device`）

官网接口不需要这些；App 接口**每一个**都要带上，缺字段会被按旧版本或异常客户端处理。

| 字段 | 值 | 说明 |
| --- | --- | --- |
| `iid` | `1905892595382586` | 安装 ID |
| `device_id` | `1905892595378490` | 设备 ID |
| `ac` | `wifi` | 网络类型 |
| `channel` | `update_64` | 渠道 |
| `aid` | `8662` | 应用 ID（红果短剧） |
| `app_name` | `novelread` | 应用名 |
| `version_code` | `71332` / `72232` | 播放器接口用 71332，搜索与弹幕用 72232 |
| `version_name` | `7.1.3.32` / `7.2.2.32` | 与 `version_code` 成对 |
| `manifest_version_code` | `71332` / `72232` | 与 `version_code` 同步 |
| `update_version_code` | `71332` / `72232` | 与 `version_code` 同步 |
| `pv_player` | `71332` / `72232` | 与 `version_code` 同步 |
| `device_platform` / `os` | `android` | |
| `ssmix` | `a` | |
| `device_type` | `25053RT47C` | |
| `device_brand` | `Redmi` | |
| `language` | `zh` | |
| `os_api` | `36` | |
| `os_version` | `16` | |
| `resolution` | `1280*2772` | |
| `dpi` | `520` | |
| `host_abi` | `arm64-v8a` | |
| `dragon_device_type` | `phone` | |
| `compliance_status` | `0` | |
| `need_personal_recommend` | `1` | |
| `player_so_load` | `1` | |
| `is_android_pad_screen` | `0` | |

两套版本号在实现里叫 `DEVICE`（播放器版本 7.1.3.32）与 `APP_DEVICE`（App 版本 7.2.2.32，
即上表的 `version_code` / `version_name` / `manifest_version_code` / `update_version_code` / `pv_player`
五项改成 72232 / 7.2.2.32）。**搜索与弹幕用 App 版本，播放地址解析用播放器版本**，
混用会被平台按旧客户端处理。

### 2.2 签名（App 接口必需）

App 接口的 query 里除了设备参数，还要加两个时间字段，并带上一整套签名头：

1. `ts` = 当前秒级时间戳；`_rticket` = 毫秒级时间戳。
2. 用 `ts` / `_rticket` / query（SM3）/ body（MD5）算出一个分支号 `branch`；
   **只有 `branch == 0` 的请求才会被接受**，因此要把 `_rticket` 从 `now` 起逐个 +1 试，
   最多试 128 次，直到算出的分支号为 0（`Signer.sign` 的循环）。
3. 分支号命中后，再生成下列头：

   | 头 | 内容 |
   | --- | --- |
   | `x-ss-req-ticket` | 试出来的 `_rticket` |
   | `x-khronos` | `ts` |
   | `x-ladon` / `x-argus` | `ts` 的大端 / 小端字节再 base64 |
   | `x-gorgon` | RC4 变体（key 含随机数）加密 `MD5(query)[0:4] + MD5(body)[0:4] + 0 + 67503104 + ts`，再逐字节半字节交换、按位反转、异或 20 |
   | `x-helios` | 34 轮表驱动的伪随机流，输入 `ts-1588093228-8662` |
   | `x-medusa` | protobuf 环境信息（设备、版本、时间、`cmr/vpn/kd` 等开关）+ `AesV3` 加密后 base64；外层是「20 字节版本前缀 + 4 字节随机尾」+ 密文，明文包以 `0x35 + 随机数 + 校验` 开头 |
   | `x-tt-request-tag` | `t=0;n=0` |
   | `sdk-version` | `2` |
   | `passport-sdk-version` | `50561` |
   | `x-xs-from-web` | `0` |
   | `x-tt-dt` | 空 |
   | `Accept` | `application/json; charset=utf-8,application/x-protobuf` |

4. 有 body 时（POST）额外加：

   | 头 | 内容 |
   | --- | --- |
   | `Content-Type` | `application/json; charset=UTF-8` |
   | `x-vc-bdturing-sdk-version` | `3.7.2.cn` |
   | `x-ss-stub` | body 的 MD5，**大写十六进制** |

5. `User-Agent`：有 body 时用播放器 UA，无 body 时用 App UA（见 2.4）。

`AesV3` 是红果自己的分组变换（不是标准 AES）：4 张 256 字节 sbox（按 `ts & 3` 选一张）、
固定初始常量、8→12 轮密钥扩展；加密时把 248 字节以上的明文按每 8 字节取 1 位压成 32 字节，
用两段 CBC 派生密钥，再把密钥的 8 位逐位写回原字节的固定比特位，密文前置 1 字节密钥尾。

### 2.3 响应约定

- 官网接口返回 HTML（`text/html`），数据在 `_ROUTER_DATA` 里，需要自己按括号配平截出 JSON
  （不能正则，字符串里会有 `}`）。
- App 接口返回 JSON，**成功判定是 `code == 0`**；播放接口大写优先：`Code` 取不到再取 `code`。
- 响应体上限 16 MiB，超过直接判失败（防止异常响应把内存吃满）。
- 超时：连接 15s / 读 30s / 整次调用 45s。
- 弹幕、播放地址这类响应可能是 **brotli** 压缩（不是 gzip），抓包时要注意解压。

### 2.4 User-Agent

```
# 播放器（有 body 的请求、视频 CDN 拉流）
com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)

# App（无 body 的请求）
com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)
```

官网接口统一用播放器 UA + `Accept-Language: zh-CN,zh;q=0.9`。

---

## 3. 官网层接口（无需签名）

### 3.1 `_ROUTER_DATA` 提取

所有官网接口共用一个解析入口：在 HTML 里找到 `_ROUTER_DATA`，从其后第一个 `{` 开始做括号配平
（要考虑字符串与转义），截出的 JSON 结构是：

```json
{ "loaderData": { "<route-key>": { "……页面数据……": null } } }
```

route-key 随页面不同，下面每节单独列。

### 3.2 首页 / 分类页

```
GET https://hongguoduanju.com/category?tab=1&sort_type=1[&page=N]
```

| 参数 | 说明 |
| --- | --- |
| `tab` | `1` = 短剧（真人剧） |
| `sort_type` | `1` = 最热，`2` = 最新 |
| `page` | 第 2 页起才带；不传即第 1 页 |

route-key：`category_page`（部分响应是 `category_$`）。

```json
{
  "category_page": {
    "recommendList": [ { "video_data": { "series_id": "……" } } ],
    "pagination": { "totalPages": 100 }
  }
}
```

- 列表优先取 `recommendList`，其次 `categoryData.recommendList`；
- `pagination.totalPages` 缺省按 100 处理，客户端再夹到 100 页上限；
- 卡片字段映射见 4.1。

### 3.3 片库目录（搜索补页用）

```
GET https://hongguoduanju.com/category/comic-drama?page=N     # 漫剧，24 部一页、约 34 页
GET https://hongguoduanju.com/category/real-drama?page=N     # 真人剧
```

route-key：`category_$`（**字面量**，不是模板）。

```json
{ "category_$": { "recommendList": [ { "video_data": {} } ], "pagination": { "totalPages": 34 } } }
```

官网搜索没有分页参数，能往下翻的只有这本目录，因此 App 搜索接口不可用时，搜索第 2 页起走这里：
每次往后扫 3 页，只保留**片名包含关键词**（忽略空白、忽略大小写）且界面还没发过的条目。

### 3.4 剧集详情

```
GET https://hongguoduanju.com/detail?series_id={剧集ID}
```

route-key：`detail_page`。

```json
{
  "detail_page": {
    "seriesDetail": {
      "series_id": "7615470177953532990",
      "series_name": "聚宝仙盆之杂灵根才是真BOSS",
      "series_cover": "https://……jpg",
      "series_intro": "……",
      "episode_cnt": 99,
      "episode_right_text": "全 99 集",
      "tags": [ { "name": "玄幻" }, { "name": "爽文" } ],
      "vid_list": ["7615470177953532991", "7615470177953532992"]
    }
  }
}
```

- `vid_list` 就是**分集 ID 列表，顺序即集号**，播放与弹幕都用它；
- 客户端会校验：列表非空、每项都是 1–30 位数字、无重复，否则判为「分集数据异常」。

### 3.5 官网搜索（第 1 页兜底）

```
GET https://hongguoduanju.com/search/{URL 编码后的关键词}?page=1
```

route-key：`search_(keyword)/page`（部分响应是 `search_page`）。

```json
{ "search_(keyword)/page": { "searchList": [ { "video_data": {} } ] } }
```

**`page` 参数被服务端忽略**：`page` / `page_num` / `offset` / `page_size` / `count` 试过都一样，
一次只给相关度最高的约 10 条，`totalCount` 却显示 200 左右。所以它只负责第 1 页：
App 搜索接口对匿名设备返回空响应时，客户端退回到这里取第 1 页，第 2 页起改用片库目录（3.3）。

### 3.6 榜单

```
GET https://hongguoduanju.com/rank/{route}[?page=N]
```

| route | 榜单 |
| --- | --- |
| `hot-drama` | 红果热播榜 |
| `hot-real-drama` | 真人剧热播榜 |
| `hot-comic-drama` | 漫剧热播榜 |
| `hot-ai-drama` | AI 剧热播榜 |

响应有两种形态，客户端先试第一种、失败再退回第二种：

1. **序列化数据**（优先）：HTML 里的 `<script data-fn-name data-fn-args>`，
   `data-fn-name` 为 `r`（且 `data-fn-args[1] == "content"`）或 `mergeLoaderData`
   （`routerDataFnName == "p"`），`data-fn-args[0]` 为 `rank_hot-comic-drama/page`。

   ```json
   {
     "isSuccess": true,
     "rankList": [
       { "seriesId": "……", "title": "……", "cover": "……", "description": "……",
         "episodeCount": 99, "heatText": "1.2 亿", "rank": 1, "tags": [ "玄幻" ] }
     ],
     "pagination": { "pageNum": 1, "totalPages": 20 }
   }
   ```

   校验：`isSuccess` 为真、`pagination.pageNum == 请求页`、`totalPages >= 1`。

2. **已渲染的 DOM 兜底**：`article[aria-labelledby^=rank-title-]`（`rank-title-{seriesId}` 即 ID），
   名次取 `[class*=badge-number-]`，热度取 `p[class*=metrics-]`，
   分页取 `nav[aria-label=榜单分页]`（`[aria-current=page]` 必须等于请求页）。

另外 `loaderData["rank_hot-comic-drama/page"].updatedText` 是「更新于 ……」文案。

---

## 4. App 层接口（需要签名）

### 4.1 卡片字段映射（官网与 App 搜索共用）

```json
{
  "video_data": {
    "series_id": "7615470177953532990",
    "series_name": "聚宝仙盆之杂灵根才是真BOSS",
    "series_cover": "https://……jpg",
    "series_intro": "……",
    "episode_cnt": 99,
    "episode_right_text": "全 99 集",
    "tags": [ { "name": "玄幻" } ]
  }
}
```

| 输出字段 | 取值 | 兜底 |
| --- | --- | --- |
| `id` | `series_id` | — |
| `title` | `series_name` | `series_title` → 外层 `name` |
| `cover` | `series_cover` | 反转义 `\/`、`\u002F`、`\u0026`、`&amp;` |
| `description` | `series_intro` | — |
| `badge` | `episode_right_text` | `episode_cnt > 0` 时「全 N 集」 |
| `tags` | `tags[].name`（最多 5 个，` · ` 连接） | `category_list` |

`episode_cnt` 还可以藏在 `series_episode_info.episode_cnt`。
入库前统一校验：ID 为 1–30 位数字、标题非空、按 ID 去重。

### 4.2 App 搜索

```
POST https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v?<device + ts + _rticket>
```

query 除设备参数（**App 版本 7.2.2.32**）外：

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `query` | 关键词 | |
| `count` | `20` | 每页条数 |
| `offset` | `(page-1)*20` | 翻页时被上一页游标里的 `next_offset` 覆盖 |
| `tab_type` | `11` = 短剧/真人剧，`19` = 漫剧 | |
| `bookshelf_search_plan` | `4` | |
| `use_correct` | `true` | |
| `user_is_login` | `0` | 匿名 |
| `search_id` | 上一页 `search_tabs[].search_id` | 第 2 页起必带（漫剧） |
| `passback` | 上一页 `search_tabs[].passback` | 同上 |

响应（截取相关部分）：

```json
{
  "code": 0,
  "search_tabs": [
    {
      "tab_type": 19,
      "search_id": "……",
      "passback": "……",
      "next_offset": "20",
      "has_more": true,
      "data": [
        { "book_id": "……", "cell_name": "……", "lynx_data": "{……}",
          "search_high_light": { "title": { "text": "聚宝<em>仙盆</em>" } },
          "video_data": [ { "series_id": "……", "title": "……", "cover_url": "……",
                            "episode_cnt": 99, "video_desc": "……", "rec_text": "……", "sub_title": "……" } ] }
      ]
    }
  ]
}
```

- 取 `tab_type` 与请求一致的那个 tab；
- 每个 cell 先看自身，再展开 `JSON.parse(cell.lynx_data).cell_data`（`lynx_data` 是字符串化的 JSON）；
- 每条取 `video_data[0]`：`series_id`（兜底 `book_id` → `search_result_id`）、
  `search_high_light.title.text`（兜底 `video_data.title` → `cell_name`）、
  `cover`/`cover_url`、`video_desc`、`episode_cnt`、`rec_text`、`sub_title`；
- 游标（`search_id` / `passback` / `next_offset`）按「类型 + 关键词 + 页码」缓存 300 秒，
  最多 100 条；漫剧翻页缺游标时抛「搜索结果已过期，请从第 1 页重新搜索」。

> **搜索的路径**：客户端（Kotlin 原生版与本地内容服务）先请求这个接口，第 2 页起带上一页的游标；
> 匿名设备下它返回空响应体，此时退回官网搜索（第 1 页）+ 片库目录（第 2 页起）。

> **匿名设备当前拿不到数据**：平台对未登录设备返回 **HTTP 200 + 空响应体**
> （六条线路、多组设备参数、GET / POST、全新设备标识都一样，2026-10-09 复测）。
> 因此必须**先判空**再解析——直接丢给 JSON 解析器会抛 `A JSONObject text must begin with '{'`，
> 把回退路径打断。带上登录态（`x-tt-token`，整段 Cookie 也可以）后接口按该账号返回数据，
> 搜索第 1 页起就直接用它：原生版在「设置 → 搜索登录态」用手机扫码填写（可选，不填走上面的兜底），
> 内容服务读 `HONGGUO_SESSION_TOKEN`。
> 同一套设备参数请求播放地址解析仍然正常（`code: 0`），可见这是搜索接口自己的登录门槛。

### 4.3 播放地址解析

```
POST https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/?<device + ts + _rticket>
Content-Type: application/json; charset=UTF-8
x-ss-stub: <body 的 MD5，大写>
```

body（`id` = 分集 ID，也就是 `vid_list` 里的一项）：

```json
{
  "biz_param": {
    "detail_page_version": 0, "device_level": 3, "disable_digg_stat": false,
    "need_all_video_definition": true, "need_mp4_align": false,
    "use_os_player": false, "use_server_dns": false, "video_platform": 1024
  },
  "mixed_video_id_map": { "1004": ["7615470177953532991"] }
}
```

响应：

```json
{
  "Code": 0,
  "data": {
    "7615470177953532991": {
      "video_model": { "fallback_api": { "fallback_api": "https://……" } }
    }
  }
}
```

- `video_model` 可能是对象，也可能是**字符串化的 JSON**，两种都要处理；
- `fallback_api` 可能是对象（取 `.fallback_api`）、数组（取 `[0]`）或字符串（再解析一层）；
- 拿到 `fallback_api` 后**再发一次普通 GET**（同样用播放器 UA，不需要签名）：

```json
{
  "video_info": {
    "data": {
      "key_seed": "……base64……",
      "video_list": {
        "1080p": {
          "main_url": "……（可能是加密串）",
          "codec_type": "h264",
          "spade_a": "……base64……",
          "quality_desc": "1080P",
          "height": 1080
        }
      }
    }
  }
}
```

- `key_seed` 非空时用 `main_url` 走 `MediaCrypto.decryptUrl`（见 5.1）；
- `spade_a` 非空时用 `MediaCrypto.deriveKey` 得到 16 字节内容密钥（CENC 解密用）；
- **跳过 `codec_type` 为 `bytevc2` / `bvc2` 的资源**：这类资源在 Android 的 MP4 解析器里
  可能只有音轨、没有画面；
- 清晰度数值由 `quality_desc` / `height` / `vheight` / `quality` / key 依次兜底解析，
  支持 `2160/1440/1080/720/576/540/480/360`，以及 `1920→1080`、`1280→720`、
  `1024→576`、`854→480`、`640→360`；按数值降序取第一个 ≤ 请求上限的，
  都不满足时取最低的一档并在画面上标注「兼容资源」。

### 4.4 弹幕（评论接口）

```
POST https://{六条线路之一}/novel/commentapi/comment/list/{vid}/v1/?<device + ts + _rticket>
comment-source: 601
server-channel: 1000
x-ss-stub: ""            ← 注意：这个接口的 x-ss-stub 被显式置空
```

body：

```json
{
  "comment_source": 601,
  "server_channel": 1000,
  "group_id": "7615470177953532991",
  "group_type": 30,
  "comment_type": 20,
  "sort": 1,
  "count": 90,
  "cursor": "",
  "aid": 8662,
  "compliance_status": 0,
  "business_param": {
    "book_id": "7615470177953532990",
    "start_offset_time": 0,
    "playlet_item_duration": 126000,
    "need_danmaku_guide_type": [1, 3, 4, 2]
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `group_id` | 分集 ID（vid） |
| `business_param.book_id` | 剧集 ID（series_id） |
| `start_offset_time` | 本窗口起点，毫秒 |
| `playlet_item_duration` | 本集总时长，毫秒 |
| `cursor` | 上一窗口 `common_list_info.cursor` 原样回传 |

响应：

```json
{
  "code": 0,
  "data": {
    "data_list": [
      {
        "comment": {
          "common": { "content": { "text": "这剧真好看" } },
          "expand": { "offset_time": 12340 },
          "stat": { "digg_count": 12 }
        }
      }
    ],
    "extra": { "next_query_danmaku_list_time": 30000 },
    "common_list_info": { "cursor": "……", "has_more": true }
  }
}
```

| 输出 | 来源 |
| --- | --- |
| 文本 | `comment.common.content.text`（`isNull` 视为空） |
| 时间点 | `comment.expand.offset_time`（毫秒） |
| 点赞 | `comment.stat.digg_count` |

客户端规则：

- **一次只回 30 秒窗口**，下一个窗口起点取 `extra.next_query_danmaku_list_time`
  （取不到或没前进时按 `start + 30000` 兜底）；
- `common_list_info.has_more` 表示还没到片尾；知道本集时长时以「窗口起点 ≥ 时长」为停止条件；
- 最多 40 个窗口（= 20 分钟）、最多保留 3000 条，按 `offsetMs:text` 去重；
- **窗口之间至少间隔 180ms**：这个接口对匿名设备有频控，连发会返回风控错误；
- 一条都拿不到、或所有线路都失败时返回空列表，界面上就是「这一集没有弹幕」，不报错。

> **匿名设备只拿到采样后的「热弹幕」**：实测同一集原版 App 也只收到 2 条，
> 用抓到的请求体逐字段回放结果一致；`cursor` 里的 `danmaku_count`（自称 542）
> **不是弹幕条数**。表现就是「有的剧一屏弹幕（实测某集 210 条）、有的剧一条都没有」。

### 4.5 视频拉流（CDN）

播放地址是带签名的 CDN 直链，客户端自己按段拉：

```
GET {fallback_api 返回的直链}
User-Agent: <播放器 UA>
Referer: https://novel.snssdk.com/
Range: bytes={start}-{end}          # 单段最大 8 MiB
Accept-Encoding: identity
```

- **必须返回 `206`** 并给出 `Content-Range: bytes a-b/total`，客户端会校验
  `a == 请求起点`、`b == min(请求终点, total-1)`、`total > 0`；返回 200（不支持分段）直接判失败；
- 首次读取 256 KiB 定位 `moov`，`moov` 必须紧跟 `ftyp` 且在文件开头
  （索引大小上限 8 MiB），否则「视频索引不在文件开头」；
- `total` 在一次会话内必须一致，变化即「视频资源已变化，请重试」。

---

## 5. 媒体解密

### 5.1 播放地址解密 `MediaCrypto.decryptUrl`

```text
raw     = base64(main_url)
前置校验 = raw[0] == 0xA8 && raw[2] == 1 && raw[3] == 0
h       = SHA512( SHA512(key_seed) + 常量串 )
plain   = AES-CBC( key = h[0:16], iv = h[16:32], data = raw[4:] 对齐到 16 的整数倍 )
```

去掉 PKCS#7 填充后再去掉尾部 `0x00`，得到真正的 URL。

### 5.2 内容密钥 `MediaCrypto.deriveKey`

```text
raw    = base64(spade_a)
len    = min(raw.size - (raw[0]^raw[1]^raw[2]) + 47, raw.size - 1)   # 需 >= 33
work   = raw[1 : 1+len]
逐字节变换：a=85, b=246 交替参与，( -21 - popcount(i) + (previous ^ old) ) & 0xFF
key    = hex( ascii(work[1:33]) )        # 32 个十六进制字符 -> 16 字节
```

### 5.3 样本解密（CENC）

拿到 `spade_a` 派生的密钥后，客户端**自己解析 MP4**（`MediaCrypto.prepare`）：

1. 遍历 `moov → trak → stbl`，读 `stsz`（样本大小）、`stsc`（块→样本映射）、
   `stco`/`co64`（块偏移）、`saiz`/`saio`（辅助信息大小与偏移）；
2. 每个样本的 8 字节 IV 从辅助信息里取；`auxSize == 8` 表示整样本加密，
   更大时按 `subsample_count + 6*count` 拆出「明文段 + 密文段」；
3. `sinf → frma` 给出**原始编码格式**（AVC / HEVC），把它写回 `encv`/`enca` 位置、
   把 `sinf` 换成 `free` 清零，这样播放器看到的是普通 MP4；
4. 读取时按样本绝对偏移做 **AES-CTR** 解密，计数器是 `IV + 偏移/16`，
   段内不对齐的部分按 `relative % 16` 跳过。

---

## 6. 端到端调用顺序

```text
浏览：GET /category?tab=1&sort_type=1            -> 卡片列表 + totalPages
榜单：GET /rank/hot-comic-drama?page=1           -> rankList + 名次/热度
搜索：GET /reading/bookapi/search/tab/v          -> tab_type 11=短剧 / 19=漫剧，一次 20 条
        └─ 第 2 页起带上一页的 search_id / passback / next_offset（游标缺省即回到第 1 页重搜）
        └─ 空响应体（匿名设备）/ 失败 -> GET /search/{kw}?page=1            （第 1 页）
                                      -> GET /category/{comic|real}-drama?page=N 往后扫（第 2 页起）
详情：GET /detail?series_id={id}                 -> seriesDetail + vid_list[]
播放：POST /novel/player/multi_video_model/v1/   -> fallback_api
      GET  {fallback_api}                        -> video_list + key_seed/spade_a
      GET  {main_url} Range: bytes=0-262143      -> 206 + moov -> 样本解密 -> 播放器
弹幕：POST /novel/commentapi/comment/list/{vid}/v1/  -> 30 秒窗口循环
```

本地联调入口（本工程）：

```bash
# 榜单 / 搜索 / 弹幕 三条探针
./kotlin-tv/gradlew -p kotlin-tv --offline :core:run --args="--ranking"
./kotlin-tv/gradlew -p kotlin-tv --offline :core:run --args="--search 聚宝 --short"
# 搜索探针带登录态（不带 token 时匿名请求会退回官网搜索 + 片库目录）
./kotlin-tv/gradlew -p kotlin-tv --offline :core:run --args="--search 聚宝 --short --token <x-tt-token>"
./kotlin-tv/gradlew -p kotlin-tv --offline :core:run --args="--danmaku"

# 默认探针：搜索 -> 首页 -> 详情 -> 播放地址 -> 分段读取（--save 落一份首段 MP4）
./kotlin-tv/gradlew -p kotlin-tv --offline :core:run
```

---

## 7. 已知限制与坑（实测）

| 现象 | 原因 / 处理 |
| --- | --- |
| App 搜索返回 HTTP 200 空 body | 平台对匿名设备关闭；必须判空后回退官网搜索，否则异常会打断兜底（配置 `x-tt-token` / `HONGGUO_SESSION_TOKEN` 后该接口可用） |
| 官网搜索只有约 10 条、翻页无效 | 服务端忽略所有分页参数；App 搜索不可用时第 2 页起改用片库目录扫描 |
| 漫剧搜索报 `A JSONObject text must begin with '{'` | 空 body 直接进 JSON 解析器；先判空即可 |
| 弹幕条数远少于平台自报 | 匿名设备只下发采样热弹幕，与参数无关 |
| 弹幕请求偶发风控 | 窗口之间留 180ms；六条线路轮询 |
| 某些集只有声音没画面 | 选到了 `bytevc2`/`bvc2` 编码；选清晰度时跳过 |
| 播放返回 200 而非 206 | CDN 不支持分段；必须按分段协议处理，否则解密与 seek 都不成立 |
| 抓包拿不到有效数据 | App 有证书校验 + QUIC；需系统 CA + Frida 解 pinning，或直接挂钩 `SSL_write`/`SSL_read`，并注意响应是 brotli |
| 签名请求被拒 | `_rticket` 必须落在 `branch == 0` 的分支上（本实现只发这一支） |

---

## 8. 其他实现里出现、本工程未使用的接口

以下路径在开源实现中出现过，本工程没有采用，列出来便于对照排查：

| 用途 | 路径 | 备注 |
| --- | --- | --- |
| 分集详情 | `POST /novel/player/multi_video_detail/v1/` | 与 `vid_list` 同源信息 |
| 分集详情（预加载） | `POST /novel/player/multi_video_detail/preload/v1` | `zhenyong97/hongguo-downloader` |
| 视频模型（预加载） | `POST /novel/player/multi_video_model/preload/v1` | 同上 |
| 分享链接转剧集 ID | — | 由分享页 HTML 提取，本工程未实现 |

## 9. 来源与许可

- 签名与 `AesV3` 协议变换移植自 `woshishiq1/drpys`（commit `22261adfa31435e3b3ef5a730b8a2a75bcaf1715`，GPL-3.0）。
- 内容协议与加密处理移植自 `drpys`，`MediaCrypto` 在此基础上做了 Kotlin 适配。
- 本工程以 GPL-3.0 发布，见 `kotlin-tv/NOTICE.md`。
