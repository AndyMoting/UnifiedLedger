"""P8-00 V-8 font-scale sample rig: the parts that are decidable without a device."""

import pytest

from android_fontscale.sampler import (
    SampleError,
    dump_contains,
    find_center,
    parse_font_scale,
    screen_center,
)

_DUMP = """<?xml version="1.0" encoding="UTF-8"?>
<hierarchy rotation="0">
  <node bounds="[0,0][1080,2400]" text="" content-desc="">
    <node bounds="[0,2180][360,2400]" text="首页" content-desc="首页" />
    <node bounds="[360,2180][720,2400]" text="账户" content-desc="账户，2 个账户" />
    <node bounds="[400,900][900,1000]" text="账本为空，还没有任何交易。" content-desc="" />
  </node>
</hierarchy>
"""


def test_screen_center_is_the_middle_of_the_bounds() -> None:
    assert screen_center("[360,2180][720,2400]") == (540, 2290)


def test_screen_center_rejects_unparsable_bounds() -> None:
    with pytest.raises(SampleError):
        screen_center("0,0,1080,2400")


def test_find_center_matches_text_and_content_description() -> None:
    assert find_center(_DUMP, "账户") == (540, 2290)
    assert find_center(_DUMP, "账户，2 个账户") == (540, 2290)


def test_find_center_returns_none_when_the_label_is_absent() -> None:
    assert find_center(_DUMP, "分析") is None


def test_dump_contains_searches_text_and_description() -> None:
    assert dump_contains(_DUMP, "账本为空")
    assert dump_contains(_DUMP, "2 个账户")
    assert not dump_contains(_DUMP, "还没有可管理的账户")


def test_dump_contains_rejects_a_broken_dump() -> None:
    with pytest.raises(SampleError):
        dump_contains("<hierarchy", "账本为空")


def test_parse_font_scale_reads_the_platform_answer() -> None:
    assert parse_font_scale("1.3\n") == pytest.approx(1.3)


def test_parse_font_scale_fails_loudly_on_a_non_number() -> None:
    with pytest.raises(SampleError):
        parse_font_scale("null")
