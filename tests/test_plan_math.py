#!/usr/bin/env python3
"""Host-side smoke test for the plan/current text protocol.

The Android controller and the Xposed module both use this format:

current.txt:
  date=<ms>
  user=<id>
  total_usage=<ms>
  total_period=<ms>
  <package>|<usage_ms>|<period_ms>

plan.txt:
  date=<ms>
  user=<id>
  mode=delta
  base_at=<epoch seconds>
  base_total=<ms>
  total=<minutes>
  <package>|<minutes>

The module captures baseline.txt on the first page load after a plan is written;
displayed usage grows from the target after that baseline.

Legacy plan.txt without mode is treated as fixed.

This test checks the invariants the shell controller enforces.
"""
import random

def fmt_current(day, user, apps):
    total_u = sum(u for _, u, _ in apps)
    total_p = sum(p for _, _, p in apps)
    lines = [f"date={day}", f"user={user}", f"total_usage={total_u}", f"total_period={total_p}"]
    lines += [f"{pkg}|{u}|{p}" for pkg, u, p in apps]
    return "\n".join(lines) + "\n"

def parse_current(text):
    apps, head = [], {}
    for line in text.splitlines():
        if "=" in line and not line.startswith("com."):
            k, v = line.split("=", 1)
            if k in ("date", "user", "total_usage", "total_period"):
                head[k] = int(v)
                continue
        parts = line.split("|")
        if len(parts) == 3:
            apps.append((parts[0], int(parts[1]), int(parts[2])))
    return head, apps

def build_plan(day, user, targets, expected_total, bases=None, mode="delta"):
    assert sum(targets.values()) == expected_total
    bases = bases or {}
    if mode == "delta":
        lines = [f"date={day}", f"user={user}", "mode=delta",
                 "base_at=1793376000", f"base_total={sum(bases.values())}",
                 f"total={sum(targets.values())}"]
        lines += [f"{pkg}|{m}" for pkg, m in targets.items()]
    else:
        lines = [f"date={day}", f"user={user}", f"total={sum(targets.values())}"]
        lines += [f"{pkg}|{m}" for pkg, m in targets.items()]
    return "\n".join(lines) + "\n"

def parse_plan(text):
    head, apps = {}, []
    for line in text.splitlines():
        if line.startswith(("date=", "user=", "total=", "base_at=", "base_total=", "mode=")):
            k, v = line.split("=", 1)
            head[k] = v if k == "mode" else int(v)
        else:
            parts = line.split("|")
            if len(parts) < 2:
                continue
            apps.append((parts[0], int(parts[1]), int(parts[2]) if len(parts) > 2 else 0))
    return head, apps

def main():
    day, user = 1793376000000, 0
    apps = [("com.tencent.mm", 10800000, 10800000),
            ("com.android.chrome", 7200000, 7200000),
            ("tv.danmaku.bili", 5400000, 5400000),
            ("com.other", 6600000, 6600000)]
    head, parsed = parse_current(fmt_current(day, user, apps))
    assert head["date"] == day and head["user"] == user
    assert sum(u for _, u, _ in parsed) == head["total_usage"] == 30000000
    assert sum(p for _, _, p in parsed) == head["total_period"] == 30000000

    targets = {"com.tencent.mm": 45, "com.android.chrome": 28,
               "tv.danmaku.bili": 21, "com.other": 26}
    phead, papps = parse_plan(build_plan(day, user, targets, 120, mode="fixed"))
    assert phead["date"] == day and phead["user"] == user
    assert sum(m for _, m, _ in papps) == phead["total"] == 120
    assert phead["total"] == sum(targets.values())

    # zero target app is allowed; total remains exact
    targets2 = {"com.tencent.mm": 0, "com.android.chrome": 30,
                "tv.danmaku.bili": 0, "com.other": 0}
    phead2, papps2 = parse_plan(build_plan(day, user, targets2, 30, mode="fixed"))
    assert sum(m for _, m, _ in papps2) == phead2["total"] == 30

    # delta mode: displayed app minutes = target + floor(real_since_base / 60000)
    bases = {"com.tencent.mm": 600000, "com.android.chrome": 300000,
             "tv.danmaku.bili": 0, "com.other": 120000}
    real = {"com.tencent.mm": 900000, "com.android.chrome": 300000,
            "tv.danmaku.bili": 30000, "com.other": 180000}
    phead3, papps3 = parse_plan(build_plan(day, user, targets, 120, bases))
    assert phead3["mode"] == "delta"
    shown = []
    for pkg, target_min, base_ms in papps3:
        extra_min = max(0, real[pkg] - bases[pkg]) // 60000
        shown.append(target_min + extra_min)
    assert shown == [50, 28, 21, 27]
    assert sum(shown) == phead3["total"] + 6

    # baseline captured at first load => first render is exactly the target
    first_real = dict(bases)
    first = [m + max(0, first_real[pkg] - bases[pkg]) // 60000
             for pkg, m, _ in papps3]
    assert first == [45, 28, 21, 26]
    assert sum(first) == phead3["total"]
    print("plan/current protocol invariants: OK")

if __name__ == "__main__":
    main()
