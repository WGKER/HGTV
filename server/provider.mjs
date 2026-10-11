import fs from "node:fs";
import vm from "node:vm";
import crypto from "node:crypto";
import axios from "axios";

// 搜索登录态（可选）：App 搜索接口对匿名设备返回 HTTP 200 + 空响应体，此时会退回官网搜索。
// 把 App 抓到的 x-tt-token（整段 Cookie 也可以）配到 HONGGUO_SESSION_TOKEN，
// App 搜索接口就会按登录态返回数据（结果更全、按内容类型分 tab、可翻页）。
const sessionToken = (process.env.HONGGUO_SESSION_TOKEN || "").trim();
const sessionHeaders = sessionToken
  ? {
      "x-tt-token": sessionToken,
      ...(sessionToken.includes("=") ? { Cookie: sessionToken } : {}),
    }
  : {};
/** 只给 App 搜索接口补登录态；播放地址解析等请求保持原样。 */
function upstream(config) {
  const client = axios.create(config);
  const attach = (url, options) =>
    !sessionHeaders["x-tt-token"] || !String(url).includes("/reading/bookapi/")
      ? options
      : { ...options, headers: { ...(options.headers || {}), ...sessionHeaders } };
  const call = (url, options = {}) => client(url, attach(url, options));
  call.post = (url, body, options = {}) =>
    client.post(url, body, attach(url, options));
  return call;
}

// Trusted, pinned GPL source. This is a compatibility wrapper, not a sandbox for user scripts.
const source = fs.readFileSync(
  new URL("./vendor/hongguo.cjs", import.meta.url),
  "utf8"
);
const context = vm.createContext({
  require: (name) => {
    if (name === "node:crypto") return crypto;
    throw new Error("Unsupported module");
  },
  Buffer,
  URL,
  URLSearchParams,
  axios: upstream({
    maxContentLength: 16 * 1024 * 1024,
    maxBodyLength: 1024 * 1024,
  }),
});
vm.runInContext(
  source +
    "\nthis.api = {categoryPage, categoryData, itemToCard, appSearchPage, routerData, resolveStream, prepareHeader, remoteRange, decryptPartial, streamCache, videoInfoCache, pageCache, appSearchCache};",
  context,
  { timeout: 5000 }
);
const api = context.api;
const SITE = "https://hongguoduanju.com";
const card = (row) => ({
  id: String(row.url),
  title: row.title,
  cover: row.img || "",
  description: row.content || "",
  badge: row.desc || "",
  tags: row.tname || "",
});
const cards = (rows) =>
  Array.from(rows)
    .filter((r) => /^\d+$/.test(String(r.url)) && r.title)
    .map(card);

export const provider = {
  async home(page = 1) {
    const data = await api.categoryPage("tab=1&sort_type=1", page);
    const pageData =
      data?.loaderData?.category_page || data?.loaderData?.["category_$"];
    if (!Array.isArray(pageData?.recommendList))
      throw new Error("首页数据结构已变化");
    return { items: cards(pageData.recommendList.map(api.itemToCard)), page };
  },
  async search(keyword, page = 1) {
    // 先走 App 搜索接口：它按内容类型分 tab、也能翻页，配置 HONGGUO_SESSION_TOKEN 后即可用。
    try {
      const result = await api.appSearchPage(keyword, 11, page, 20);
      if (result.list.length) return { items: cards(result.list), page };
    } catch {
      /* 匿名设备该接口返回空响应体；退回官网搜索，失败在下文如实报错，绝不返回空列表当成功。 */
    }
    const data = await api.routerData(
      `${SITE}/search/${encodeURIComponent(keyword)}?page=${page}`
    );
    const pageData =
      data?.loaderData?.["search_(keyword)/page"] ||
      data?.loaderData?.search_page;
    if (!Array.isArray(pageData?.searchList)) throw new Error("搜索数据不可用");
    return { items: cards(pageData.searchList.map(api.itemToCard)), page };
  },
  async detail(id) {
    const data = await api.routerData(`${SITE}/detail?series_id=${id}`);
    const series = data?.loaderData?.detail_page?.seriesDetail;
    if (!series || !Array.isArray(series.vid_list) || !series.vid_list.length)
      throw new Error("剧集不存在或分集暂不可用");
    const episodes = Array.from(series.vid_list, (vid, index) => ({
      id: String(vid),
      title: `第 ${index + 1} 集`,
      number: index + 1,
    }));
    if (
      episodes.some((e) => !/^\d+$/.test(e.id)) ||
      new Set(episodes.map((e) => e.id)).size !== episodes.length
    )
      throw new Error("分集数据异常");
    return { ...card(api.itemToCard(series)), id, episodes };
  },
  async stream(id, fresh = false) {
    if (fresh) {
      api.streamCache.delete(`${id}|auto`);
      api.videoInfoCache.delete(id);
    }
    const stream = await api.resolveStream(id, "auto");
    const prepared = await api.prepareHeader(stream);
    return {
      total: prepared.total,
      chunk: async (start, end) =>
        api.decryptPartial(
          (await api.remoteRange(stream, start, end)).data,
          start,
          stream.key,
          prepared
        ),
    };
  },
};

// Limit the pinned upstream's otherwise unbounded caches during long-lived home use.
setInterval(() => {
  for (const cache of [
    api.streamCache,
    api.videoInfoCache,
    api.pageCache,
    api.appSearchCache,
  ]) {
    for (const [key, value] of cache)
      if (Date.now() - value.time > 300000) cache.delete(key);
    while (cache.size > 100) cache.delete(cache.keys().next().value);
  }
}, 60000).unref();
