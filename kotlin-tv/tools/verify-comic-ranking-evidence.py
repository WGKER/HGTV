#!/usr/bin/env python3
"""Check recorded uiautomator XML; this does not drive a device or replace live QA."""
import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(sys.argv[1] if len(sys.argv) > 1 else "outputs/comic-ranking")
checks = 0


def nodes(name):
    return list(ET.parse(root / (name + ".xml")).iter("node"))


def verify(condition, label):
    global checks
    assert condition, label
    checks += 1


def has_text(name, text):
    return any(n.get("text") == text for n in nodes(name))


def has_desc(name, desc):
    return any(n.get("content-desc") == desc for n in nodes(name))


def focused_rank(name, rank):
    return any(n.get("focused") == "true" and n.get("content-desc", "").startswith(f"第 {rank} 名，") for n in nodes(name))


def shelf_order(name):
    """剧架标题在 XML 里的出现顺序（「热门发现 · 短剧」这类带副标题的按前缀匹配）。"""
    titles = [n.get("text", "") for n in nodes(name)]

    def first(shelf):
        for index, text in enumerate(titles):
            if text == shelf or text.startswith(shelf + " ·"):
                return index
        return -1

    return [first(shelf) for shelf in ("历史记录", "我的收藏", "热门推荐", "热门发现")]


verify(has_text("release-ranking", "漫剧热播榜"), "ranking entry opens comic ranking")
verify(focused_rank("release-ranking", 1), "first ranked card receives focus")
verify(any("热度" in n.get("text", "") for n in nodes("release-ranking")), "source heat shown")
verify(any("已更新" in n.get("text", "") for n in nodes("release-ranking")), "source update shown")
verify(not any(n.get("text") in ("上一页", "下一页") for n in nodes("release-ranking")), "ranking has no page flip buttons")
verify(any(n.get("text", "").startswith("已显示 20 / ") for n in nodes("release-ranking")), "ranking reports how much is already shown")
verify(any(n.get("text") == "展示更多 ↓" for n in nodes("release-ranking")), "ranking bottom offers a downward load-more button")
for page in range(2, 6):
    verify(focused_rank(f"release-more-{page}", (page - 1) * 20 + 1), f"load more appends page {page} and keeps global ranks")
    verify(any(n.get("text", "").startswith(f"已显示 {page * 20} / ") for n in nodes(f"release-more-{page}")), f"page {page} list grows in place")
    verify(not any(n.get("text") in ("上一页", "下一页") for n in nodes(f"release-more-{page}")), f"page {page} has no page flip buttons")
verify(focused_rank("release-more-offline", 21), "failed load more keeps the shown list and the button")
verify(any(n.get("text") == "展示更多 ↓" for n in nodes("release-more-offline")), "failed load more leaves a retry button")
# 首页「热门推荐 / 热门发现」向右进入的热门集合页复用同一套向下追加，一并核对。
verify(has_text("release-collection", "热门发现"), "home shelves open the hot collection")
verify(has_text("release-collection", "‹ 返回首页"), "hot collection keeps a way back to the home shelves")
verify(not any(n.get("text") in ("上一页", "下一页", "下一批热门") for n in nodes("release-collection")), "hot collection has no page flip or next-batch buttons")
verify(any(n.get("text") == "展示更多 ↓" for n in nodes("release-collection")), "hot collection offers the same downward load-more button")
verify(any(n.get("text", "").startswith("已显示 20 部") for n in nodes("release-collection")), "hot collection reports how much is already shown")
verify(any(n.get("text", "").startswith("已显示 40 部") for n in nodes("release-collection-more")), "hot collection list grows in place")
verify(not any(n.get("text") in ("上一页", "下一页", "下一批热门") for n in nodes("release-collection-more")), "collection load more still has no page flip buttons")
verify(has_text("release-detail", "剧集详情"), "ranking opens detail")
verify(focused_rank("release-return", 23), "detail back restores the appended list and original card")
verify(any(n.get("text") == "刷新榜单" and n.get("focused") == "true" for n in nodes("release-refresh-focus")), "refresh reachable with remote")
verify(focused_rank("release-refreshed", 1), "refresh returns to page one")
verify(has_text("release-race-return", "正在加载…"), "return during slow refresh reloads")
verify(not any(n.get("content-desc", "").startswith("第 ") for n in nodes("release-race-return")), "pending page has no stale rank cards")
verify(focused_rank("release-race-result", 1), "refresh race recovers with correct page")
verify(has_text("release-last-bottom", "第 100 名"), "last rank visible")
verify(has_text("release-last-bottom", "已显示全部 100 部"), "last page reports the whole list")
verify(not any(n.get("text") in ("上一页", "下一页", "展示更多 ↓") for n in nodes("release-last-bottom")), "last page cannot advance and has no page flip buttons")
verify(focused_rank("release-large-font", 1), "large font ranking remains usable")
for label in ["设置", "刷新榜单"]:
    node = next(n for n in nodes("release-large-font") if n.get("text") == label)
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
    verify(0 <= left < right <= 1920 and 0 <= top < bottom <= 1080, f"large font {label} inside viewport")
verify(has_text("release-refresh-switched", "设置"), "settings page reachable while a ranking refresh is slow")
verify(has_text("release-home-shelves", "历史记录"), "home shows the watch history shelf")
verify(has_text("release-home-shelves", "我的收藏"), "home shows the favorites shelf")
order = shelf_order("release-home-shelves")
verify(all(index >= 0 for index in order) and order == sorted(order), "home shelf order: history, favorites, hot picks, hot discoveries")
verify(not any(n.get("content-desc") in ("收藏", "最近观看") for n in nodes("release-home-shelves")), "top navigation drops the favorites and history entries")
verify(has_desc("release-home-shelves", "设置"), "settings entry stays in the top navigation")
verify(any(n.get("text", "").startswith("清晰度上限：") for n in nodes("release-settings")), "settings tab preserved")
verify(has_text("release-search", "输入短剧名称或关键词"), "search tab and content preference preserved")
print(f"{checks} ranking UI evidence checks passed")
