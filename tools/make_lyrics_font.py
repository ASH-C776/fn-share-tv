#!/usr/bin/env python3
"""重新生成歌词字体 `app/src/main/res/font/fn_lyrics_kai.ttf`。

上游是霞鹜文楷 Lite（LXGW WenKai Lite Regular v1.530，SIL OFL 1.1），原文件
14.0MB / 25694 个码点。直接打包会让 APK 从 17.8MB 涨到 25MB，而歌词只用到常用汉字，
所以这里做 GB2312 子集：3.46MB（APK 内 deflate 后约 2.05MB）。

**为什么要改名**：OFL 的 Reserved Font Name 条款规定修改版（子集化属于修改）不得
继续使用保留名；上游声明保留的是「霞鹜 / 霞鶩 / 落霞孤鹜 / 落霞孤鶩 / LXGW」。
所以 family / full / PostScript 名全部改成 FN Lyrics Kai，同时把 copyright 与
OFL 声明原样写回 name 表（子集化会丢掉 nameID 10/11/13/14）。

用法：
    pip install fonttools
    python tools/make_lyrics_font.py "F:/下载/LXGWWenKaiLite-Regular.ttf"

想扩字符集（例如发现某首歌的生僻字回落到系统字体），改 `build_charset()` 即可。
"""

import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

# 生成后的字体名。不要改回含 LXGW / 霞鹜 的名字——见上面的 OFL 说明。
FAMILY = "FN Lyrics Kai"
PS_NAME = "FNLyricsKai-Regular"
VERSION = "Version 1.530; subset (GB2312) of LXGW WenKai Lite"

# 除了汉字，这些也要带上：ASCII（英文歌词/数字）、中文标点、全角数字、
# 几个歌词里常见的符号。少了标点的话，全角逗号句号会回落到系统字体，
# 一行里字重突变，很显眼。
EXTRA = (
    "　、。〃々〈〉《》「」『』【】〔〕〖〗〝〞–—‘’“”…‧﹏·¥€£°′″"
    "０１２３４５６７８９"
)

OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/font/fn_lyrics_kai.ttf"


def build_charset() -> str:
    """GB2312 全集 + ASCII + 标点。"""
    chars = set()
    for hi in range(0xA1, 0xFF):
        for lo in range(0xA1, 0xFF):
            try:
                chars.add(bytes([hi, lo]).decode("gb2312"))
            except UnicodeDecodeError:
                pass
    chars |= {chr(c) for c in range(0x20, 0x7F)}
    chars |= set(EXTRA)
    return "".join(sorted(chars))


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    src = Path(sys.argv[1])
    if not src.exists():
        print(f"找不到上游字体：{src}")
        return 1

    text = build_charset()
    print(f"字符集 {len(text)} 个字符 → {OUT}")

    # --layout-features='' ：中文歌词用不到 OpenType 连字/替换，留着纯占体积。
    # 丢掉 vhea/vmtx：竖排是 Compose 逐字排的，不用字体的垂直排版表。
    subset.main([
        str(src),
        f"--text={text}",
        f"--output-file={OUT}",
        "--layout-features=",
        "--drop-tables+=vhea,vmtx,DSIG",
        "--no-hinting",
    ])

    font = TTFont(OUT)
    name = font["name"]
    # 所有语言/平台的 family 记录都要改，否则中文 locale 下仍是「霞鶩文楷」。
    for rec in list(name.names):
        if rec.nameID in (1, 16):
            rec.string = FAMILY
        elif rec.nameID == 4:
            rec.string = FAMILY
        elif rec.nameID == 3:
            rec.string = f"{FAMILY}: {VERSION}"
        elif rec.nameID == 6:
            rec.string = PS_NAME
    name.setName(FAMILY, 1, 3, 1, 0x409)
    name.setName("Regular", 2, 3, 1, 0x409)
    name.setName(f"{FAMILY}: {VERSION}", 3, 3, 1, 0x409)
    name.setName(FAMILY, 4, 3, 1, 0x409)
    name.setName(PS_NAME, 6, 3, 1, 0x409)
    # 这三条是 subset 会丢、而 OFL 要求随字体一起分发的。
    name.setName(
        "This Font Software is licensed under the SIL Open Font License, Version 1.1. "
        "This license is available with a FAQ at: https://openfontlicense.org",
        13, 3, 1, 0x409,
    )
    name.setName("https://openfontlicense.org", 14, 3, 1, 0x409)
    name.setName("https://github.com/lxgw/LxgwWenKai-Lite", 11, 3, 1, 0x409)
    name.setName(
        f"{FAMILY} is a GB2312 subset of LXGW WenKai Lite v1.530, "
        "renamed per the OFL Reserved Font Name clause.",
        10, 3, 1, 0x409,
    )
    font.save(OUT)

    check = TTFont(OUT)
    print(f"生成完成：{OUT.stat().st_size / 1048576:.2f} MB，"
          f"{len(check.getBestCmap())} 个码点")
    print("记得确认 copyright（nameID 0）里仍带着上游的保留名声明。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
