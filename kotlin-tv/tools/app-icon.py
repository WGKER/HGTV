#!/usr/bin/env python3
"""红果 TV 的应用图标与电视横幅：一份几何、一套材质，出五份产物。

图标是一件**拟物**的小物件——一颗带高光与反光的红果、一片果叶、一枚压进果肉的播放三角，
**背景透明**（按需求）：物件本身没有底板、没有描边、没有圆角矩形，投影是它自己的接触阴影
（越靠近果底越深、越往外越散）。果身用径向渐变做迎光面与背光边缘，右下补一道环境反光。

**桌面上如果出现一圈底板，那是启动器画的，不是这个文件里的东西**，这一条在设备上量过三轮：

1. `background` 层留透明时，模拟器上的 Lawnchair 给每个图标垫一块纯黑底板（深色模式），
   桌面上就是一个黑方块；
2. 把 `background` 换成 alpha=1 的白色，量到的是 (1,1,1)——白底叠在黑底板上，
   说明底板在图标下面；
3. 把**前景也换成全透明**（图标什么都不画），那一块纯黑方圆仍然在（128×128、
   15596 个 `sum(rgb)<40` 的像素）——它跟图标的内容无关，换启动器即消失。

所以这里保持 `@android:color/transparent`，把透明背景这件事做干净；要不要垫底板是启动器的风格。

**前景必须待在安全区里**，这一条也在同一台设备上量过：启动器把 108dp 画布画成 192px、
只露出中央 72dp（128px）的方圆遮罩，越出去就被切。所以物件在画布里缩到 0.80：果身整个
落在中央 66dp 的圆里（最远 31dp），果叶尖到 42dp，离 72dp 的遮罩边缘还有 3dp（实测 7px）。
严格圆形的遮罩会削掉果叶尖最后几个像素，方形 / 圆角方形遮罩都完整。

五份产物都从这里生成，避免手改漂移：

| 产物 | 位置 | 用途 |
| --- | --- | --- |
| `app_icon.xml` | `app/src/main/res/drawable/` | 桌面图标 / 自适应图标的前景（矢量，透明底） |
| `ic_launcher(.round).xml` | `app/src/main/res/mipmap-anydpi-v26/` | 自适应图标：背景层透明 + 上面的前景 |
| `ic_launcher(.round).png` | `app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/` | API 26 以下与直接读位图的启动器兜底（透明底） |
| `tv_banner.png` | `app/src/main/res/drawable/` | Android TV 桌面卡片（320×180dp，出图 640×360） |
| `AppIcon.appiconset/*.png` | `../ios/HongguoTV/Images.xcassets/` | iOS 工程（iOS 不收透明图，铺浅色底板） |

矢量与位图共用同一份轮廓与色值：位图用 PIL + numpy 逐层合成（超采样 + 高斯柔化），
矢量用同一批控制点转成三次贝塞尔，阴影 / 高光用「两层错位的同形路径 + 渐变」代替高斯模糊。
因此 `--emit-vector` 写出的 XML 与位图是同一件东西的两种实现。

用法：
    python3 tools/app-icon.py                # 出全部位图 + preview/icon.png
    python3 tools/app-icon.py --emit-vector  # 同时重写两个 app_icon.xml
    python3 tools/app-icon.py --check        # 只校验：矢量 / 位图是否与脚本同源（CI 用）
依赖：Pillow、numpy；中文用系统 PingFang / Hiragino。
"""

import json
import math
import os
import re
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
KOTLIN = os.path.dirname(HERE)
REPO = os.path.dirname(KOTLIN)
K_RES = os.path.join(KOTLIN, "app", "src", "main", "res")
R_RES = os.path.join(REPO, "android", "app", "src", "main", "res")
IOS_SET = os.path.join(REPO, "ios", "HongguoTV", "Images.xcassets", "AppIcon.appiconset")
PREVIEW = os.path.join(KOTLIN, "preview")

UNIT = 108.0          # 矢量画布坐标系：108dp 画布 = 108 单位
MASTER = 2048         # 主图边长（108 单位 → 2048px，约 19px/单位），再由它缩出所有尺寸
ICON_SCALE = 0.80     # 图标画布里物件的缩放：自适应图标安全区（见文件头）
MARK_H = 80.0         # 标志在 108 画布里的高度（果叶尖 → 果底）
MARK_CY = 54.0        # 标志的竖直中心（画布中心）：上下都要落在 72dp 可见区里
IOS_SCALE = 1.0       # iOS 由系统自己套方圆遮罩，可以占满
BANNER = (640, 360)   # 320×180dp 的卡片，出图按 2 倍

# Apple 标志的官方几何（814×1000，绝对坐标；果身一条闭合子路径 + 果叶一条闭合子路径）。
APPLE_D = (
    "M788.1 340.9c-5.8 4.5-108.2 62.2-108.2 190.5 0 148.4 130.3 200.9 134.2 202.2"
    "-.6 3.2-20.7 71.9-68.7 141.9-42.8 61.6-87.5 123.1-155.5 123.1"
    "s-85.5-39.5-164-39.5c-76.5 0-103.7 40.8-165.9 40.8s-105.6-57-155.5-127"
    "C46.7 790.7 0 663 0 541.8c0-194.4 126.4-297.5 250.8-297.5 66.1 0 121.2 43.4 162.7 43.4"
    " 39.5 0 101.1-46 176.3-46 28.5 0 130.9 2.6 198.3 99.2z"
    "m-234-181.5c31.1-36.9 53.1-88.1 53.1-139.3 0-7.1-.6-14.3-1.9-20.1"
    "-50.6 1.9-110.8 33.7-147.1 75.8-28.5 32.4-55.1 83.6-55.1 135.5 0 7.8 1.3 15.6 1.9 18.1"
    " 3.2.6 8.4 1.3 13.6 1.3 45.4 0 102.5-30.4 135.5-71.3z"
)

# 中文必须用带 CJK 字形的系统字体：PingFang.ttc 在这里取到的两个 face 都只有拉丁字形。
FONT_PATH = "/System/Library/Fonts/Hiragino Sans GB.ttc"


def font(size, bold=False):
    for idx in ((2, 0) if bold else (0, 2)):
        try:
            return ImageFont.truetype(FONT_PATH, size, index=idx)
        except Exception:
            pass
    try:
        return ImageFont.truetype("/System/Library/Fonts/STHeiti Medium.ttc", size)
    except Exception:
        return ImageFont.load_default()


# ============ 材质（与 TvStyle 的深邃暗调同一族） ============

FRUIT_HI = (0xFF, 0x9E, 0x80)      # 迎光面
FRUIT_LIGHT = (0xFF, 0x76, 0x55)
FRUIT = (0xEE, 0x4C, 0x33)         # 本色（与旧图标的 #FF694F 同族，压深一档以便做明暗）
FRUIT_MID = (0xC9, 0x2E, 0x1B)
FRUIT_DEEP = (0x9E, 0x1B, 0x0C)
FRUIT_EDGE = (0x6E, 0x10, 0x06)    # 背光边缘
RIM = (0xFF, 0xB4, 0x8E)           # 右下反光（环境反弹光）

LEAF_HI = (0xA9, 0xD0, 0x74)
LEAF = (0x6E, 0x9F, 0x3E)
LEAF_DEEP = (0x4A, 0x74, 0x2B)
LEAF_VEIN = (0x3E, 0x65, 0x22)

PLAY_HI = (0xFF, 0xFF, 0xFF)
PLAY = (0xFD, 0xF7, 0xEE)
PLAY_LOW = (0xEF, 0xDF, 0xC6)
PLAY_CARVE = (0x7A, 0x1B, 0x08)    # 压进果肉的内阴影

SHADOW = (0x14, 0x0B, 0x08)        # 投影不用纯黑：红果落在桌面上的影子带一点暖

# 浅色新拟态材质（左上迎光、右下柔影，与 App 的浅色主题同一族）。
# 只给 **iOS** 用：iOS 不接受透明图标，也不接受带 alpha 的 1024 图标，所以那边必须铺一层底。
PLATE_HI = (0xFD, 0xFE, 0xFF)
PLATE = (0xF0, 0xF4, 0xF8)
PLATE_MID = (0xE4, 0xEA, 0xF0)
PLATE_LOW = (0xD2, 0xDA, 0xE4)
PLATE_SHADE = (0xA3, 0xB1, 0xC6)   # 经典浅灰主题的暗部阴影色
PLATE_LIGHT = (0xFF, 0xFF, 0xFF)

INK = (0xF4, 0xF3, 0xF1)           # 横幅文字（TvStyle.playerText）
ACCENT = (0xDF, 0xB7, 0x7C)        # 香槟金（TvStyle.accent）
GRAPHITE = (0x2E, 0x32, 0x38)      # 深邃暗调底色（TvStyle.background）


# ============ 路径解析（SVG 子集 → 绝对三次贝塞尔） ============

TOKEN = re.compile(r"[MmLlHhVvCcSsZz]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def parse_path(d):
    """返回 [(子路径, [('M'|'L'|'C', 点 或 (c1,c2,p))])]。"""
    toks = TOKEN.findall(d)
    subs = []
    segs = []
    x = y = 0.0
    sx = sy = 0.0
    c2 = None                      # 上一个控制点（给 S/s 用）
    i = 0
    cmd = None

    def take(n):
        nonlocal i
        vals = [float(v) for v in toks[i:i + n]]
        i += n
        return vals

    while i < len(toks):
        if toks[i].isalpha():
            cmd = toks[i]
            i += 1
            if cmd in "Zz":
                segs.append(("Z", None))
                x, y = sx, sy
                continue
        if cmd in "Mm":
            px, py = take(2)
            x, y = (px, py) if cmd == "M" else (x + px, y + py)
            sx, sy = x, y
            if segs:
                subs.append(segs)
            segs = [("M", (x, y))]
            cmd = "L" if cmd == "M" else "l"
            c2 = None
        elif cmd in "Ll":
            px, py = take(2)
            x, y = (px, py) if cmd == "L" else (x + px, y + py)
            segs.append(("L", (x, y)))
            c2 = None
        elif cmd in "Hh":
            px = take(1)[0]
            x = px if cmd == "H" else x + px
            segs.append(("L", (x, y)))
            c2 = None
        elif cmd in "Vv":
            py = take(1)[0]
            y = py if cmd == "V" else y + py
            segs.append(("L", (x, y)))
            c2 = None
        elif cmd in "CcSs":
            if cmd in "Cc":
                x1, y1, x2v, y2v, px, py = take(6)
                if cmd == "c":
                    x1, y1, x2v, y2v, px, py = (x + x1, y + y1, x + x2v, y + y2v, x + px, y + py)
            else:
                x2v, y2v, px, py = take(4)
                if cmd == "s":
                    x2v, y2v, px, py = (x + x2v, y + y2v, x + px, y + py)
                x1, y1 = (2 * x - c2[0], 2 * y - c2[1]) if c2 else (x, y)
            segs.append(("C", ((x1, y1), (x2v, y2v), (px, py))))
            c2 = (x2v, y2v)
            x, y = px, py
        else:
            raise ValueError("不支持的路径命令：%r" % cmd)
    if segs:
        subs.append(segs)
    return subs


def sub_bbox(segs):
    xs, ys = [], []
    for cmd, arg in segs:
        if cmd == "M" or cmd == "L":
            xs.append(arg[0]); ys.append(arg[1])
        elif cmd == "C":
            for p in arg:
                xs.append(p[0]); ys.append(p[1])
    return min(xs), min(ys), max(xs), max(ys)


def flatten(segs, samples=26):
    """把子路径压成多边形点列。"""
    pts = []
    cur = (0.0, 0.0)
    for cmd, arg in segs:
        if cmd == "M":
            cur = arg
            pts.append(cur)
        elif cmd == "L":
            cur = arg
            pts.append(cur)
        elif cmd == "C":
            c1, c2, p = arg
            for s in range(1, samples + 1):
                t = s / samples
                u = 1 - t
                pts.append((
                    u ** 3 * cur[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t ** 3 * p[0],
                    u ** 3 * cur[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t ** 3 * p[1],
                ))
            cur = p
    return pts


# ============ 仿射：814×1000 → 108 画布 ============

_APPLE_SUBS = parse_path(APPLE_D)
_APPLE_BB = (min(sub_bbox(s)[0] for s in _APPLE_SUBS),
             min(sub_bbox(s)[1] for s in _APPLE_SUBS),
             max(sub_bbox(s)[2] for s in _APPLE_SUBS),
             max(sub_bbox(s)[3] for s in _APPLE_SUBS))
APPLE_S = MARK_H / (_APPLE_BB[3] - _APPLE_BB[1])
APPLE_CX = (_APPLE_BB[0] + _APPLE_BB[2]) / 2.0
APPLE_CY = (_APPLE_BB[1] + _APPLE_BB[3]) / 2.0


def T(p):
    """源坐标 → 108 画布坐标。"""
    return (54.0 + (p[0] - APPLE_CX) * APPLE_S, MARK_CY + (p[1] - APPLE_CY) * APPLE_S)


def t_segs(segs):
    return [(cmd, (T(arg) if cmd in ("M", "L") else tuple(T(p) for p in arg)) if arg else None)
            for cmd, arg in segs]


BODY_SEGS = t_segs(_APPLE_SUBS[0])
LEAF_SEGS = t_segs(_APPLE_SUBS[1])
BODY_PTS = flatten(BODY_SEGS, 30)
LEAF_PTS = flatten(LEAF_SEGS, 30)
BB = sub_bbox(BODY_SEGS)                     # 果身包围盒（108 画布）


def num(v):
    """路径与渐变的坐标：一位小数（108 单位里 0.1 ≈ 0.1dp，够用）。"""
    s = "%.1f" % v
    return s[:-2] if s.endswith(".0") else s


def num2(v):
    """group 的缩放：两位小数。0.88 被写成 0.9 会越过自适应图标的安全区。"""
    s = "%.2f" % v
    return s.rstrip("0").rstrip(".") if "." in s else s


def path_d(segs, dx=0.0, dy=0.0):
    out = []
    for cmd, arg in segs:
        if cmd == "M":
            out.append("M%s,%s" % (num(arg[0] + dx), num(arg[1] + dy)))
        elif cmd == "L":
            out.append("L%s,%s" % (num(arg[0] + dx), num(arg[1] + dy)))
        elif cmd == "C":
            c1, c2, p = arg
            out.append("C%s,%s %s,%s %s,%s" % (num(c1[0] + dx), num(c1[1] + dy),
                                               num(c2[0] + dx), num(c2[1] + dy),
                                               num(p[0] + dx), num(p[1] + dy)))
        else:
            out.append("Z")
    return "".join(out)


def poly_d(pts, dx=0.0, dy=0.0):
    out = ["M%s,%s" % (num(pts[0][0] + dx), num(pts[0][1] + dy))]
    for p in pts[1:]:
        out.append("L%s,%s" % (num(p[0] + dx), num(p[1] + dy)))
    out.append("Z")
    return "".join(out)


def scale_about(pts, s, cx, cy):
    return [(cx + (x - cx) * s, cy + (y - cy) * s) for x, y in pts]


def ellipse_pts(cx, cy, rx, ry, n=48):
    return [(cx + rx * math.cos(i * 2 * math.pi / n), cy + ry * math.sin(i * 2 * math.pi / n))
            for i in range(n)]


def circle_pts(cx, cy, r, n=36):
    return ellipse_pts(cx, cy, r, r, n)


# ============ 播放三角：位置与大小由果身形状决定 ============

TRI_R = 4.6


def rounded_triangle(ax, ay, bx, by, cx, cy, r):
    """圆角三角的多边形采样（位图与矢量共用，保证两边一模一样）。"""
    V = [(ax, ay), (bx, by), (cx, cy)]
    gc = ((ax + bx + cx) / 3.0, (ay + by + cy) / 3.0)
    out = []
    for i in range(3):
        prev, cur, nxt = V[i - 1], V[i], V[(i + 1) % 3]
        u1 = unit(cur[0] - prev[0], cur[1] - prev[1])
        u2 = unit(nxt[0] - cur[0], nxt[1] - cur[1])
        ang = math.acos(max(-1.0, min(1.0, u1[0] * u2[0] + u1[1] * u2[1])))
        d = r / math.tan(ang / 2.0)
        t1 = (cur[0] - u1[0] * d, cur[1] - u1[1] * d)
        t2 = (cur[0] + u2[0] * d, cur[1] + u2[1] * d)
        inward = unit(gc[0] - cur[0], gc[1] - cur[1])
        c = (cur[0] + inward[0] * (r / math.sin(ang / 2.0)),
             cur[1] + inward[1] * (r / math.sin(ang / 2.0)))
        a1 = math.atan2(t1[1] - c[1], t1[0] - c[0])
        a2 = math.atan2(t2[1] - c[1], t2[0] - c[0])
        da = a2 - a1
        while da > math.pi:
            da -= 2 * math.pi
        while da < -math.pi:
            da += 2 * math.pi
        for s in range(13):
            a = a1 + da * s / 12.0
            out.append((c[0] + math.cos(a) * r, c[1] + math.sin(a) * r))
    return out


def unit(x, y):
    L = math.hypot(x, y) or 1.0
    return (x / L, y / L)


def body_field(scale=2.0):
    """果身的「离边界有多深」场（0=贴着轮廓，1=深入内部），用于给三角找位置。"""
    w = h = int(UNIT * scale)
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).polygon([(x * scale, y * scale) for x, y in BODY_PTS], fill=255)
    m = m.filter(ImageFilter.GaussianBlur(2.6 * scale))
    a = np.asarray(m, np.float32) / 255.0
    return np.clip((a - 0.5) / 0.5, 0.0, 1.0), scale


def place_triangle():
    """把三角放在果身最「厚」的位置（果身有缺口，几何中心不是视觉中心）。"""
    field, sc = body_field()
    h, w = field.shape
    yy, xx = np.mgrid[0:h, 0:w]
    core = field > 0.72                       # 离轮廓约 2dp 以上的区域
    cx = float(xx[core].mean()) / sc
    cy = float(yy[core].mean()) / sc
    bh = BB[3] - BB[1]
    height = 0.52 * bh
    width = 0.78 * height
    r = TRI_R
    for _ in range(8):
        ax, ay = cx - width / 2.0, cy - height / 2.0
        tri = rounded_triangle(ax, ay, ax, ay + height, ax + width, ay + height / 2.0, r)
        vals = [field[min(h - 1, max(0, int(y * sc))), min(w - 1, max(0, int(x * sc)))]
                for x, y in tri]
        if min(vals) > 0.55:
            break
        height *= 0.94
        width *= 0.94
    return (cx, cy, height, width)


# ============ 位图渲染 ============


def ramp(t, stops):
    out = np.zeros(t.shape + (3,), np.float32)
    ts = np.clip(t, 0.0, 1.0)
    for i in range(len(stops) - 1):
        o0, c0 = stops[i]
        o1, c1 = stops[i + 1]
        if o1 <= o0:
            continue
        m = (ts >= o0) & (ts <= o1)
        f = np.clip((ts - o0) / (o1 - o0), 0.0, 1.0)[..., None]
        seg = np.array(c0, np.float32) * (1 - f) + np.array(c1, np.float32) * f
        out = np.where(m[..., None], seg, out)
    out = np.where((ts < stops[0][0])[..., None], np.array(stops[0][1], np.float32), out)
    out = np.where((ts > stops[-1][0])[..., None], np.array(stops[-1][1], np.float32), out)
    return out


TRI = place_triangle()
SPEC_SOFT = (34.6, 46.6, 9.6, 6.8, 0.46)       # 果身左上迎光面（柔光）
SPEC_HOT = (38.4, 43.6, 4.0, 2.8, 0.88)        # 亮点：收紧一点，缩到 48dp 才像玻璃


def render_master():
    N = MASTER
    k = N / UNIT
    yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)
    X, Y = xx / k, yy / k

    def poly_mask(pts, dx=0.0, dy=0.0):
        m = Image.new("L", (N, N), 0)
        ImageDraw.Draw(m).polygon([((x + dx) * k, (y + dy) * k) for x, y in pts], fill=255)
        return np.asarray(m, np.float32) / 255.0

    def blur(a, sigma):
        im = Image.fromarray((np.clip(a, 0, 1) * 255).astype(np.uint8))
        return np.asarray(im.filter(ImageFilter.GaussianBlur(max(sigma * k, 0.6))), np.float32) / 255.0

    def blob(cx, cy, sx, sy):
        return np.exp(-(((X - cx) / sx) ** 2 + ((Y - cy) / sy) ** 2))

    m_body = poly_mask(BODY_PTS)
    m_leaf = poly_mask(LEAF_PTS)
    cx, cy, th, tw = TRI
    tri_pts = rounded_triangle(cx - tw / 2.0, cy - th / 2.0, cx - tw / 2.0, cy + th / 2.0,
                               cx + tw / 2.0, cy, TRI_R)
    m_tri = poly_mask(tri_pts)

    rgb = np.zeros((N, N, 3), np.float32)
    alpha = np.zeros((N, N), np.float32)

    def over(l_rgb, l_a):
        nonlocal rgb, alpha
        la = np.clip(l_a, 0.0, 1.0)
        out_a = la + alpha * (1 - la)
        safe = np.maximum(out_a, 1e-6)[..., None]
        rgb = (np.asarray(l_rgb, np.float32) * la[..., None]
               + rgb * alpha[..., None] * (1 - la[..., None])) / safe
        alpha = out_a

    # 形状感知的边缘场与内法线：任何形状（含缺口）都能拿到贴边的一圈
    g = blur(m_body, 3.0)
    edge = np.clip((0.5 - g) / 0.30, 0.0, 1.0) ** 1.05
    gy, gx = np.gradient(g)
    nn = np.sqrt(gx * gx + gy * gy) + 1e-6
    nx, ny = -gx / nn, -gy / nn

    # —— 1. 接触阴影：外圈大而淡 + 内圈小而深 ——
    over(SHADOW, blur(blob(54.0, 92.0, 27.0, 8.6), 2.6) * 0.40)
    over(SHADOW, blur(blob(54.0, 90.6, 20.5, 5.0), 1.5) * 0.46)

    # —— 2. 果身 ——
    d_light = np.sqrt((X - 33.0) ** 2 + (Y - 45.0) ** 2) / 72.0
    col = ramp(d_light, [(0.00, FRUIT_HI), (0.15, FRUIT_LIGHT), (0.40, FRUIT),
                         (0.66, FRUIT_MID), (0.85, FRUIT_DEEP), (1.00, FRUIT_EDGE)])
    col *= (1.0 - 0.46 * edge)[..., None]
    rim = np.clip(nx * 0.64 + ny * 0.77, 0.0, 1.0) ** 1.5 * edge
    col += np.array(RIM, np.float32) * (0.42 * rim)[..., None]
    # 左上迎光面：柔光 + 亮点
    sx, sy, sw, sh, sa = SPEC_SOFT
    col += np.array(PLAY_HI, np.float32) * (blur(blob(sx, sy, sw, sh), 1.2) * sa)[..., None]
    sx, sy, sw, sh, sa = SPEC_HOT
    col += np.array(PLAY_HI, np.float32) * (blur(blob(sx, sy, sw, sh), 0.5) * sa)[..., None]
    # 果叶压在果身顶上的影子
    col *= (1.0 - 0.26 * blur(blob(56.0, BB[1] + 3.0, 8.0, 3.4), 0.9))[..., None]
    over(np.clip(col, 0, 255), m_body)

    # —— 3. 果叶：沿叶轴渐变 + 上缘反光 + 中脉 ——
    lx0, ly0, lx1, ly1 = sub_bbox(LEAF_SEGS)
    bx, by = lx0 + (lx1 - lx0) * 0.10, ly1 - (ly1 - ly0) * 0.08      # 叶基
    tx, ty = lx1 - (lx1 - lx0) * 0.06, ly0 + (ly1 - ly0) * 0.06      # 叶尖
    dx, dy = tx - bx, ty - by
    L = math.hypot(dx, dy)
    ux, uy = dx / L, dy / L
    u = ((X - bx) * ux + (Y - by) * uy) / L
    v = ((X - bx) * (-uy) + (Y - by) * ux)
    leaf_col = ramp(u, [(0.0, LEAF_DEEP), (0.42, LEAF), (1.0, LEAF_HI)])
    leaf_col *= (1.0 - 0.24 * np.clip((v + 1.0) / 9.0, 0, 1))[..., None]
    leaf_col += np.array(PLAY_HI, np.float32) * (
        0.34 * np.clip(1 - np.abs(v + 4.0) / 2.2, 0, 1) * np.clip((u - 0.15) / 0.25, 0, 1)
        * np.clip((1.02 - u) / 0.3, 0, 1))[..., None]
    leaf_col *= (1.0 - 0.32 * np.clip(1 - np.abs(v) / 1.1, 0, 1))[..., None]
    leaf_col += np.array(LEAF_HI, np.float32) * (
        0.28 * np.clip(1 - np.abs(v - 1.4) / 0.9, 0, 1) * np.clip((u - 0.1) / 0.2, 0, 1))[..., None]
    over(np.clip(leaf_col, 0, 255), m_leaf)

    # —— 4. 播放三角：投影 → 本体 → 左上内阴影 → 右下内高光 ——
    over(SHADOW, blur(poly_mask(tri_pts, 0.0, 2.0), 1.1) * 0.40)
    over(SHADOW, blur(poly_mask(tri_pts, 0.0, 1.1), 0.5) * 0.34)
    d_play = ((X - (cx - tw / 2.0)) + (Y - (cy - th / 2.0))) / (tw + th)
    play_col = ramp(d_play, [(0.0, PLAY_HI), (0.5, PLAY), (1.0, PLAY_LOW)])
    over(play_col, m_tri)
    carve = np.clip(m_tri - poly_mask(tri_pts, 1.5, 1.6), 0, 1)
    carve *= np.clip(1.0 - d_play, 0, 1) ** 0.7
    over(PLAY_CARVE, blur(carve, 0.45) * 0.62)
    lip = np.clip(m_tri - poly_mask(tri_pts, -1.5, -1.6), 0, 1)
    lip *= np.clip(d_play - 0.35, 0, 1) ** 0.8
    over(PLAY_HI, blur(lip, 0.4) * 0.55)

    return Image.fromarray(
        np.dstack([np.clip(rgb, 0, 255), np.clip(alpha * 255, 0, 255)]).astype(np.uint8), "RGBA")


_MASTER_IMG = None


def master():
    global _MASTER_IMG
    if _MASTER_IMG is None:
        _MASTER_IMG = render_master()
    return _MASTER_IMG


def icon_image(size, scale=ICON_SCALE, plate=False, bg=None):
    """把主图按 scale 放进画布中央，缩到 size×size。

    默认透明底（桌面图标用的就是这一版）；plate=True 铺一层浅色柔光底板，只给 iOS 用
    （iOS 不收透明图）；bg 是纯色底，给预览用。
    """
    src = master()
    inner = max(1, int(round(size * scale)))
    art = src.resize((inner, inner), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    if plate:
        canvas.alpha_composite(plate_image(size))
    elif bg is not None:
        canvas.alpha_composite(Image.new("RGBA", (size, size), tuple(bg) + (255,)))
    off = (size - art.width) // 2
    canvas.alpha_composite(art, (off, off))
    return canvas


def plate_image(size):
    """浅色柔光底板：左上迎光、右下柔影（新拟态「凸起面」），铺满整张画布。"""
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    X, Y = xx / size * UNIT, yy / size * UNIT
    r = np.sqrt(((X - 34.0) / 92.0) ** 2 + ((Y - 26.0) / 100.0) ** 2)
    col = ramp(r, [(0.00, PLATE_HI), (0.30, PLATE), (0.64, PLATE_MID), (1.00, PLATE_LOW)])
    d = np.clip(((X - 54.0) * 0.60 + (Y - 54.0) * 0.80) / 62.0, 0.0, 1.0) ** 1.5
    col = col * (1.0 - 0.16 * d)[..., None] + np.array(PLATE_SHADE, np.float32) * (0.16 * d)[..., None]
    l = np.clip((-(X - 54.0) * 0.60 - (Y - 54.0) * 0.80) / 62.0, 0.0, 1.0) ** 1.5
    col = col * (1.0 - 0.30 * l)[..., None] + np.array(PLATE_LIGHT, np.float32) * (0.30 * l)[..., None]
    return Image.fromarray(np.clip(col, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def graphite_plate(w, h, center=(0.34, 0.5)):
    """石墨径向底：中心亮一档、四角压深，没有边界线也没有圆角。"""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    cx, cy = center[0] * w, center[1] * h
    r = np.sqrt(((xx - cx) / (w * 0.95)) ** 2 + ((yy - cy) / (h * 1.15)) ** 2)
    col = ramp(r, [(0.0, (0x40, 0x46, 0x4F)), (0.42, GRAPHITE), (1.0, (0x1E, 0x22, 0x27))])
    return Image.fromarray(col.astype(np.uint8), "RGB")


def banner_image():
    w, h = BANNER
    img = graphite_plate(w, h).convert("RGBA")
    mark_h = int(round(h * 0.60))
    mark = icon_image(mark_h, IOS_SCALE)
    img.alpha_composite(mark, (int(w * 0.10), (h - mark.height) // 2))
    d = ImageDraw.Draw(img)
    f = font(62, bold=True)
    text = "红果 TV"
    box = d.textbbox((0, 0), text, font=f)
    tx = int(w * 0.47)
    ty = int(h * 0.5 - (box[1] + box[3]) / 2.0) - 8
    d.text((tx, ty), text, font=f, fill=INK + (255,))
    d.rounded_rectangle([tx + 3, ty + (box[3] - box[1]) + 26, tx + 171,
                         ty + (box[3] - box[1]) + 30], radius=2, fill=ACCENT + (255,))
    return img.convert("RGB")


# ============ 矢量 ============


def grad_radial(cx, cy, r, stops, indent=20):
    pad = " " * indent
    out = ['%s<gradient android:type="radial" android:centerX="%s" android:centerY="%s" '
           'android:gradientRadius="%s">' % (pad, num(cx), num(cy), num(r))]
    for off, col in stops:
        out.append('%s    <item android:offset="%s" android:color="%s"/>'
                   % (pad, ("%.2f" % off).rstrip("0").rstrip("."), col))
    out.append("%s</gradient>" % pad)
    return "\n".join(out)


def grad_linear(x1, y1, x2, y2, stops, indent=20):
    pad = " " * indent
    out = ['%s<gradient android:type="linear" android:startX="%s" android:startY="%s" '
           'android:endX="%s" android:endY="%s">'
           % (pad, num(x1), num(y1), num(x2), num(y2))]
    for off, col in stops:
        out.append('%s    <item android:offset="%s" android:color="%s"/>'
                   % (pad, ("%.2f" % off).rstrip("0").rstrip("."), col))
    out.append("%s</gradient>" % pad)
    return "\n".join(out)


def hexc(rgb, a=None):
    if a is None:
        return "#%02X%02X%02X" % tuple(rgb)
    return "#%02X%02X%02X%02X" % ((a,) + tuple(rgb))


def reindent(block, indent):
    """把一段 XML 整体挪到 indent，保留它自己的层级。"""
    lines = [l for l in block.splitlines() if l.strip()]   # 不能用 strip()：它会把第一行的缩进也吃掉
    base = min(len(l) - len(l.lstrip()) for l in lines)
    return "\n".join(" " * (indent + len(l) - len(l.lstrip()) - base) + l.strip() for l in lines)


def vector_path(pd, fill=None, grad=None, fill_type=None, indent=8):
    pad = " " * indent
    attrs = ['android:pathData="%s"' % pd]
    if fill_type:
        attrs.append('android:fillType="%s"' % fill_type)
    if grad is None:
        attrs.append('android:fillColor="%s"' % (fill or "#00000000"))
        return "%s<path %s/>" % (pad, " ".join(attrs))
    return "\n".join(["%s<path %s>" % (pad, " ".join(attrs)),
                      " " * (indent + 4) + '<aapt:attr name="android:fillColor">',
                      reindent(grad, indent + 8),
                      " " * (indent + 4) + "</aapt:attr>",
                      pad + "</path>"])


def vector_xml():
    cx, cy, th, tw = TRI
    tri_pts = rounded_triangle(cx - tw / 2.0, cy - th / 2.0, cx - tw / 2.0, cy + th / 2.0,
                               cx + tw / 2.0, cy, TRI_R)
    body_d = path_d(BODY_SEGS)
    inner_edge = poly_d(scale_about(BODY_PTS, 0.88, 54.0, (BB[1] + BB[3]) / 2.0))
    inner_rim = poly_d(scale_about(BODY_PTS, 0.90, 54.0, (BB[1] + BB[3]) / 2.0))
    leaf_d = path_d(LEAF_SEGS)
    tri_d = poly_d(tri_pts)
    lx0, ly0, lx1, ly1 = sub_bbox(LEAF_SEGS)
    vein_a = (lx0 + (lx1 - lx0) * 0.14, ly1 - (ly1 - ly0) * 0.10)
    vein_b = (lx1 - (lx1 - lx0) * 0.12, ly0 + (ly1 - ly0) * 0.10)

    L = []
    A = L.append
    A("<!--")
    A("  应用图标的前景：苹果公司的 Apple 标志（果身 + 果叶）+ 果身正中的播放三角，透明底。")
    A("")
    A("  这一层只有物件，底板在另一个文件（ic_launcher_background.xml），由")
    A("  mipmap-anydpi-v26/ic_launcher.xml 的 background 层引用。为什么要拆成两层：")
    A("  启动器会给「背景层透明」的图标自己垫一块底板（在模拟器的三星风格启动器上量到的是")
    A("  纯黑），所以底板必须由图标自己给；前景保持透明，物件才能干净地压在底板上。")
    A("")
    A("  物件的做法：果身径向渐变（迎光面 → 本色 → 背光边缘），右下补一道环境反光，")
    A("  投影是它自己的接触阴影（两层错位的椭圆渐变）。播放三角是「压进果肉」的：")
    A("  先一层下移的深色（投影），再白色渐变的本体，然后左上内阴影、右下内高光各一圈")
    A("  （同形路径错位 1.5 单位后取差集）。它的大小与位置由果身的形状决定")
    A("  （tools/app-icon.py 里按「离轮廓最深的点」求解），因为果身有缺口，几何中心不是视觉中心。")
    A("")
    A("  外层 group 的 0.80 是自适应图标的安全区（在真机启动器上量过：108dp 画布只露出")
    A("  中央 72dp）：果身整个落在中央 66dp 的圆里（最远 31dp），果叶尖到 42dp，离 72dp")
    A("  的遮罩边缘还有 3dp，不会被启动器切掉。改形状改 tools/app-icon.py，然后重跑，")
    A("  不要手改本文件。")
    A("-->")
    A('<vector xmlns:android="http://schemas.android.com/apk/res/android"')
    A('    xmlns:aapt="http://schemas.android.com/aapt"')
    A('    android:width="108dp" android:height="108dp"')
    A('    android:viewportWidth="108" android:viewportHeight="108">')
    A('    <group android:scaleX="%s" android:scaleY="%s" android:pivotX="54" android:pivotY="54">'
      % (num2(ICON_SCALE), num2(ICON_SCALE)))

    # 1 接触阴影
    for cyy, rx, ry, peak in ((92.0, 27.0, 8.6, 0x3D), (90.6, 20.5, 5.0, 0x5A)):
        A('        <group android:scaleY="%s" android:pivotY="%s">' % (num2(ry / rx), num(cyy)))
        A(vector_path(poly_d(circle_pts(54.0, cyy, rx)),
                      grad=grad_radial(54.0, cyy, rx,
                                       [(0.0, hexc(SHADOW, peak)),
                                        (0.55, hexc(SHADOW, int(peak * 0.62))),
                                        (1.0, hexc(SHADOW, 0))]), indent=12))
        A("        </group>")
    # 2 果身
    A(vector_path(body_d, grad=grad_radial(
        33.0, 45.0, 72.0,
        [(0.0, hexc(FRUIT_HI)), (0.15, hexc(FRUIT_LIGHT)), (0.40, hexc(FRUIT)),
         (0.66, hexc(FRUIT_MID)), (0.85, hexc(FRUIT_DEEP)), (1.0, hexc(FRUIT_EDGE))])))
    # 3 背光边缘加深
    A(vector_path(body_d + inner_edge, fill_type="evenOdd", grad=grad_radial(
        54.0, (BB[1] + BB[3]) / 2.0, (BB[3] - BB[1]) / 2.0,
        [(0.0, hexc(FRUIT_EDGE, 0)), (0.70, hexc(FRUIT_EDGE, 0)),
         (0.90, hexc(FRUIT_EDGE, 0x4D)), (1.0, hexc(FRUIT_EDGE, 0x80))])))
    # 4 右下反光
    A(vector_path(body_d + inner_rim, fill_type="evenOdd", grad=grad_linear(
        BB[0] + 4.0, BB[1] + 6.0, BB[2] + 2.0, BB[3] - 2.0,
        [(0.0, hexc(RIM, 0)), (0.55, hexc(RIM, 0)), (0.88, hexc(RIM, 0x4D)),
         (1.0, hexc(RIM, 0x66))])))
    # 5 高光
    for sx, sy, sw, sh, peak in (SPEC_SOFT, SPEC_HOT):
        # 渐变半径取短半轴：椭圆边界之外已经是透明，否则会在椭圆轮廓上留下一条硬边
        A(vector_path(poly_d(ellipse_pts(sx, sy, sw, sh)), grad=grad_radial(
            sx, sy, min(sw, sh),
            [(0.0, hexc(PLAY_HI, int(255 * peak))),
             (0.55, hexc(PLAY_HI, int(255 * peak * 0.42))), (1.0, hexc(PLAY_HI, 0))])))
    # 6 果叶压在果身顶上的影子
    A(vector_path(poly_d(ellipse_pts(56.0, BB[1] + 3.0, 8.0, 3.4)), grad=grad_radial(
        56.0, BB[1] + 3.0, 3.4, [(0.0, hexc(SHADOW, 0x42)), (1.0, hexc(SHADOW, 0))])))
    # 7 果叶
    A(vector_path(leaf_d, grad=grad_linear(
        (lx0 + (lx1 - lx0) * 0.10), (ly1 - (ly1 - ly0) * 0.08),
        (lx1 - (lx1 - lx0) * 0.06), (ly0 + (ly1 - ly0) * 0.06),
        [(0.0, hexc(LEAF_DEEP)), (0.42, hexc(LEAF)), (1.0, hexc(LEAF_HI))])))
    A('        <path android:pathData="M%s,%s L%s,%s" android:strokeColor="%s" '
      'android:strokeWidth="1.1" android:strokeLineCap="round"/>'
      % (num(vein_a[0]), num(vein_a[1]), num(vein_b[0]), num(vein_b[1]), hexc(LEAF_VEIN, 0x73)))
    # 8 播放三角
    A(vector_path(poly_d(tri_pts, 0.0, 2.0), fill=hexc(SHADOW, 0x66)))
    A(vector_path(poly_d(tri_pts, 0.0, 1.1), fill=hexc(SHADOW, 0x55)))
    A(vector_path(tri_d, grad=grad_linear(
        cx - tw / 2.0, cy - th / 2.0, cx + tw / 2.0, cy + th / 2.0,
        [(0.0, hexc(PLAY_HI)), (0.5, hexc(PLAY)), (1.0, hexc(PLAY_LOW))])))
    A(vector_path(tri_d + poly_d(tri_pts, 1.5, 1.6), fill_type="evenOdd", grad=grad_linear(
        cx - tw / 2.0, cy - th / 2.0, cx + tw / 2.0, cy + th / 2.0,
        [(0.0, hexc(PLAY_CARVE, 0x9E)), (0.45, hexc(PLAY_CARVE, 0x4D)),
         (1.0, hexc(PLAY_CARVE, 0))])))
    A(vector_path(tri_d + poly_d(tri_pts, -1.5, -1.6), fill_type="evenOdd", grad=grad_linear(
        cx - tw / 2.0, cy - th / 2.0, cx + tw / 2.0, cy + th / 2.0,
        [(0.0, hexc(PLAY_HI, 0)), (0.5, hexc(PLAY_HI, 0)), (1.0, hexc(PLAY_HI, 0x8C))])))
    A("    </group>")
    A("</vector>")
    return "\n".join(L) + "\n"


def adaptive_xml():
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!--\n'
            '  自适应图标：背景层透明，图标只有物件本身。\n'
            '  桌面上那圈底板（如果看得到）是启动器自己画的，不是这个文件里的东西：把前景也换成\n'
            '  全透明后那块黑方圆仍在，见 tools/app-icon.py 文件头的三轮实测。\n'
            '  前景 @drawable/app_icon 已经把物件缩进安全区（见 app_icon.xml）。\n'
            '-->\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@android:color/transparent"/>\n'
            '    <foreground android:drawable="@drawable/app_icon"/>\n'
            '</adaptive-icon>\n')


# ============ 输出 ============

MIPMAPS = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]

IOS_ICONS = [
    ("iphone", "20x20", "2x", 40), ("iphone", "20x20", "3x", 60),
    ("iphone", "29x29", "2x", 58), ("iphone", "29x29", "3x", 87),
    ("iphone", "40x40", "2x", 80), ("iphone", "40x40", "3x", 120),
    ("iphone", "60x60", "2x", 120), ("iphone", "60x60", "3x", 180),
    ("ios-marketing", "1024x1024", "1x", 1024),
]


def save_xml(text, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def preview_icon(size, scale=ICON_SCALE):
    """图标本体（背景透明），预览里直接贴在壁纸上——遮罩交给启动器。"""
    return icon_image(size, scale).convert("RGBA")


def preview_sheet():
    """离线校对图：模拟启动器裁切后的大图 + 真实小尺寸 + 横幅。"""
    W = 1160
    wallpapers = [("深色壁纸", (0x18, 0x1E, 0x2A)), ("中灰", (0x8A, 0x90, 0x99)),
                  ("浅色壁纸", (0xE6, 0xEA, 0xEF))]
    big, pad, head = 300, 26, 96
    H = head + big + 150 + 250
    img = Image.new("RGB", (W, H), (0xFA, 0xFA, 0xFB))
    d = ImageDraw.Draw(img)
    d.text((pad, 26), "红果 TV 应用图标 · Apple 标志 + 播放三角（背景透明）",
           font=font(30, bold=True), fill=(0x1A, 0x1D, 0x22))
    d.text((pad, 62), "tools/app-icon.py 生成：图标本身没有底板，下面是同一张图贴在三种壁纸上。",
           font=font(19), fill=(0x5A, 0x62, 0x6C))
    x = pad
    for label, bg in wallpapers:
        cell = Image.new("RGB", (big + 40, big + 40), bg)
        art = preview_icon(big)
        cell.paste(art, (20, 20), art)
        img.paste(cell, (x, head))
        d.text((x + 6, head + big + 46), label, font=font(19), fill=(0x5A, 0x62, 0x6C))
        x += big + 40 + 14
    y = head + big + 84
    d.text((pad, y), "真实尺寸（48–192dp）：", font=font(19), fill=(0x5A, 0x62, 0x6C))
    y += 34
    for label, bg in (("深色壁纸", (0x18, 0x1E, 0x2A)), ("浅色壁纸", (0xE6, 0xEA, 0xEF))):
        strip = Image.new("RGB", (W - 2 * pad, 210), bg)
        xx = 20
        for size in (192, 144, 96, 72, 48):
            art = preview_icon(size)
            strip.paste(art, (xx, 105 - size // 2), art)
            xx += size + 26
        d.text((pad + 12, y + 6), label, font=font(19),
               fill=(0x3A, 0x40, 0x48) if bg[0] > 0x80 else (0xC8, 0xCE, 0xD6))
        img.paste(strip, (pad, y))
        y += 226
    b = banner_image().resize((int(BANNER[0] * 0.72), int(BANNER[1] * 0.72)), Image.LANCZOS)
    d.text((pad, y - 30), "电视横幅 tv_banner.png（320×180dp，Android TV 桌面卡片）：",
           font=font(19), fill=(0x5A, 0x62, 0x6C))
    img.paste(b, (pad, y))
    return img


def contrast(a, b):
    def lum(c):
        c = [v / 255.0 for v in c]
        c = [(v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4) for v in c]
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
    la, lb = lum(a), lum(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def check():
    """核对仓库里的矢量、自适应图标、位图与脚本同源（改完图标跑这一条）。"""
    bad = 0

    def want_file(path, want, what):
        nonlocal bad
        got = open(path, encoding="utf-8").read() if os.path.exists(path) else ""
        if got != want:
            print("漂移：%s（%s 与 tools/app-icon.py 不一致，跑 --emit-vector 重写）"
                  % (os.path.relpath(path, REPO), what))
            bad += 1

    def want_png(path, size):
        nonlocal bad
        if not os.path.exists(path):
            print("缺失：%s" % os.path.relpath(path, REPO))
            bad += 1
            return
        got = Image.open(path).size
        if got != size:
            print("尺寸不对：%s 是 %dx%d，应为 %dx%d"
                  % (os.path.relpath(path, REPO), got[0], got[1], size[0], size[1]))
            bad += 1

    for res in (K_RES, R_RES):
        want_file(os.path.join(res, "drawable", "app_icon.xml"), vector_xml(), "矢量")
        for name in ("ic_launcher", "ic_launcher_round"):
            want_file(os.path.join(res, "mipmap-anydpi-v26", "%s.xml" % name),
                      adaptive_xml(), "自适应图标")
        want_png(os.path.join(res, "drawable", "tv_banner.png"), BANNER)
        for dpi, px in MIPMAPS:
            want_png(os.path.join(res, "mipmap-%s" % dpi, "ic_launcher.png"), (px, px))
            want_png(os.path.join(res, "mipmap-%s" % dpi, "ic_launcher_round.png"), (px, px))

    contents = os.path.join(IOS_SET, "Contents.json")
    if not os.path.exists(contents):
        print("缺失：%s" % os.path.relpath(contents, REPO))
        bad += 1
    else:
        want = {("icon-%d@%s.png" % (int(size.split("x")[0]), scale)): px
                for _, size, scale, px in IOS_ICONS}
        got = {i.get("filename"): int(i["size"].split("x")[0]) * int(i["scale"][0])
               for i in json.load(open(contents, encoding="utf-8"))["images"]}
        if got != want:
            print("iOS Contents.json 的清单与脚本不一致：%s" % sorted(set(got) ^ set(want)))
            bad += 1
        for name, px in want.items():
            want_png(os.path.join(IOS_SET, name), (px, px))

    if not bad:
        print("图标同源：2 份矢量、4 份自适应图标、20 张启动器位图、2 张电视横幅、9 张 iOS 图标")
    print("播放三角 / 果肉对比度：%.2f:1（非文字门槛 3:1）" % contrast(PLAY, FRUIT))
    print("果肉 / 浅色桌面对比度：%.2f:1（图标自身轮廓要能站住，不看 4.5:1 文字门槛）"
          % contrast(FRUIT, (0xE0, 0xE5, 0xEC)))
    print("果肉 / 深色桌面对比度：%.2f:1" % contrast(FRUIT, GRAPHITE))
    return bad


def write_all(emit_vector=True):
    made = []

    def save(img, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
        made.append(path)

    for res in (K_RES, R_RES):
        for dpi, px in MIPMAPS:
            img = icon_image(px)
            save(img, os.path.join(res, "mipmap-%s" % dpi, "ic_launcher.png"))
            # 圆形变体与方形同一张图：遮罩形状由启动器自己套
            save(img, os.path.join(res, "mipmap-%s" % dpi, "ic_launcher_round.png"))
        for name in ("ic_launcher", "ic_launcher_round"):
            save_xml(adaptive_xml(), os.path.join(res, "mipmap-anydpi-v26", "%s.xml" % name))
        save(banner_image(), os.path.join(res, "drawable", "tv_banner.png"))
        if emit_vector:
            save_xml(vector_xml(), os.path.join(res, "drawable", "app_icon.xml"))

    # iOS：系统不收透明图（1024 也不许带 alpha），铺同一块浅色底板
    for idiom, size, scale, px in IOS_ICONS:
        logical = int(size.split("x")[0])
        save(icon_image(px, IOS_SCALE, plate=True).convert("RGB"),
             os.path.join(IOS_SET, "icon-%d@%s.png" % (logical, scale)))
    save_xml(json.dumps({
        "images": [{"idiom": idiom, "scale": scale, "size": size,
                    "filename": "icon-%d@%s.png" % (int(size.split("x")[0]), scale)}
                   for idiom, size, scale, _ in IOS_ICONS],
        "info": {"author": "xcode", "version": 1},
    }, indent=2, ensure_ascii=False) + "\n", os.path.join(IOS_SET, "Contents.json"))

    save(preview_sheet(), os.path.join(PREVIEW, "icon.png"))
    return made


def main():
    args = sys.argv[1:]
    if "--check" in args:
        sys.exit(1 if check() else 0)
    made = write_all(emit_vector="--emit-vector" in args or "--all" in args)
    cx, cy, th, tw = TRI
    print("标志：%d 条子路径，果身包围盒 x %.1f–%.1f / y %.1f–%.1f（108 画布）"
          % (len(_APPLE_SUBS), BB[0], BB[2], BB[1], BB[3]))
    print("播放三角：中心 (%.1f, %.1f)，%.1f × %.1f，圆角 %.1f"
          % (cx, cy, tw, th, TRI_R))
    print("写出 %d 个文件：" % len(made))
    for p in made:
        print("  " + os.path.relpath(p, REPO))
    print("播放三角 / 果肉对比度：%.2f:1（非文字门槛 3:1）" % contrast(PLAY, FRUIT))


if __name__ == "__main__":
    main()
