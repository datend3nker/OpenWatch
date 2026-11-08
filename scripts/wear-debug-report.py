#!/usr/bin/env python3
"""Reads the debug watch app's measurement protocol and answers the power and source questions.

The debug build of the watch app writes `files/debug/protocol-<day>.jsonl`
(`DebugProtocol`, `DebugSnapshots`): a snapshot at each battery percent and at
each screen, Doze, charger or plan change, with the counters since the last
one, and events from the recorders. This script pulls those files over adb
(`run-as`, debug builds only) or reads saved ones, and prints:

1. the drain per state (plan, screen, Doze, bedtime), in %/h and mAh/h;
2. how often each sensor delivered, the processor wake-ups the app causes;
3. the power-save heart rate windows: alarms, windows, their length;
4. the Samsung platform: bind, first delivery, flush answers, lag, errors;
5. the night-time HRV windows per source: started, stored, dropped and why,
   and the RMSSD each source gave.

    python3 -I scripts/wear-debug-report.py --serial 192.168.178.63:41399
    python3 -I scripts/wear-debug-report.py --save out/ --serial ...   # also keeps the raw files
    python3 -I scripts/wear-debug-report.py protocol-20261108.jsonl ... # saved files
"""

import argparse
import collections
import datetime
import json
import os
import statistics
import subprocess
import sys

PACKAGE = "tech.mmarca.openwatch.debug"
STATE_KEYS = ("hrSec", "hrSource", "hrvOn", "bedtime", "screen", "idle")


def pull(serial, save):
    adb = ["adb"] + (["-s", serial] if serial else [])
    names = subprocess.run(
        adb + ["exec-out", "run-as", PACKAGE, "ls", "files/debug"],
        check=True, capture_output=True, text=True,
    ).stdout.split()
    lines = []
    for name in sorted(n for n in names if n.startswith("protocol-")):
        text = subprocess.run(
            adb + ["exec-out", "run-as", PACKAGE, "cat", f"files/debug/{name}"],
            check=True, capture_output=True, text=True,
        ).stdout
        if save:
            os.makedirs(save, exist_ok=True)
            with open(os.path.join(save, name), "w") as out:
                out.write(text)
        lines += text.splitlines()
    return lines


def parse(lines):
    rows = []
    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    rows.sort(key=lambda r: r["t"])
    return rows


def when(ms):
    return datetime.datetime.fromtimestamp(ms / 1000).strftime("%m-%d %H:%M")


def state_of(snap):
    parts = []
    if snap.get("sleepHold"):
        parts.append("hold synced" if snap.get("syncedHold") else "hold lock")
    hr = snap.get("hrSec")
    parts.append("hr off" if not hr else ("hr 10s" if hr == 10 else "hr save"))
    if hr:
        parts.append("samsung" if snap.get("hrSource") == "SAMSUNG_PLATFORM" else "sensor")
    if snap.get("bedtime"):
        parts.append("bedtime")
    parts.append("screen" if snap.get("screen") else ("doze" if snap.get("idle") else "dark"))
    return ", ".join(parts)


def drain(snaps):
    print("\n## 1. Drain per state (on battery, between consecutive snapshots)\n")
    per = collections.defaultdict(lambda: [0.0, 0, 0, collections.Counter()])  # hours, % lost, uAh lost, counters
    for a, b in zip(snaps, snaps[1:]):
        if a.get("charging") or b.get("charging"):
            continue
        hours = (b["t"] - a["t"]) / 3_600_000
        if hours <= 0 or hours > 3:
            continue
        slot = per[state_of(a)]
        slot[0] += hours
        slot[1] += max(0, (a.get("level") or 0) - (b.get("level") or 0))
        if a.get("chargeUah") and b.get("chargeUah"):
            slot[2] += max(0, a["chargeUah"] - b["chargeUah"])
        slot[3].update({k: v for k, v in b.get("c", {}).items() if not k.endswith(".max")})
    if not per:
        print("No stretch on battery yet.")
        return per
    print(f"{'state':38s} {'hours':>6s} {'%/h':>6s} {'mAh/h':>7s}")
    for key, (hours, pct, uah, _) in sorted(per.items(), key=lambda kv: -kv[1][0]):
        print(f"{key:38s} {hours:6.2f} {pct / hours:6.2f} {uah / 1000 / hours:7.2f}")
    return per


def wakeups(per):
    print("\n## 2. Sensor deliveries per hour (each batch is a processor wake-up for a wake-up sensor)\n")
    keys = ("hr.batches", "wake.cycles", "accel.events", "steps.batches", "baro.batches", "offbody.batches",
            "hr.alarms", "samsung.deliveries")
    labels = ("hr", "flushes", "accel/h", "steps", "baro", "offbody", "alarms", "samsung")
    print(f"{'state':38s} " + " ".join(f"{label:>8s}" for label in labels))
    for key, (hours, _, _, counters) in sorted(per.items(), key=lambda kv: -kv[1][0]):
        if hours < 0.1:
            continue
        print(f"{key:38s} " + " ".join(f"{counters.get(k, 0) / hours:8.1f}" for k in keys))


def power_save(snaps):
    print("\n## 3. Power-save heart rate windows\n")
    c = collections.Counter()
    for s in snaps:
        c.update(s.get("c", {}))
    windows = c.get("hr.windows", 0)
    if not windows and not c.get("hr.alarms"):
        print("None ran.")
        return
    print(f"alarms {c.get('hr.alarms', 0)}, windows {windows}, empty windows {c.get('hr.windowsEmpty', 0)}")
    if windows:
        print(f"mean window {c.get('hr.windowMs', 0) / windows / 1000:.1f} s with the sensor on")
    gaps = []
    last = None
    for s in snaps:
        if s.get("c", {}).get("hr.alarms") and last:
            gaps.append((s["t"] - last) / 60000 / s["c"]["hr.alarms"])
        if s.get("c", {}).get("hr.alarms"):
            last = s["t"]
    if gaps:
        print(f"mean minutes between alarms (rough, per snapshot) {statistics.mean(gaps):.1f}")


def samsung(events):
    print("\n## 4. Samsung Health Platform\n")
    feed = [e for e in events if e["src"] == "SamsungFeed"]
    if not feed:
        print("Not used.")
        return
    counts = collections.Counter(e["ev"] for e in feed)
    print("events:", dict(counts))
    first = [e["afterMs"] / 1000 for e in feed if e["ev"] == "first_delivery"]
    if first:
        print(f"first delivery after start: {', '.join(f'{x:.0f} s' for x in first)}")
    flushes = [e.get("afterMs") for e in feed if e["ev"] == "flush_done"]
    asked = counts.get("flush_asked", 0)
    answered = [x for x in flushes if x is not None]
    print(f"flushes asked {asked}, answered {len(answered)}"
          + (f", answer after {statistics.median(answered) / 1000:.1f} s median, {max(answered) / 1000:.1f} s max" if answered else ""))


def samsung_lag(snaps):
    lags = [s["c"]["samsung.lagMs.max"] for s in snaps if "samsung.lagMs.max" in s.get("c", {})]
    if lags:
        print(f"oldest point at its delivery: {statistics.median(lags) / 1000:.0f} s median, {max(lags) / 1000:.0f} s max")


def holds(snaps, events):
    print("\n## 6. Sleep holds and synced wake-ups\n")
    hold = [e for e in events if e["src"] == "SleepHold"]
    print("events:", dict(collections.Counter(e["ev"] for e in hold)) or "none")
    phone = [e for e in hold if e["ev"] == "phone_active"]
    if phone:
        print(f"phone unlocks seen {len(phone)}, of which ended a hold {sum(1 for e in phone if e.get('endedHold'))}")
    c = collections.Counter()
    for s in snaps:
        c.update({k: v for k, v in s.get("c", {}).items() if k.startswith("wake.")})
    if c.get("wake.cycles"):
        print(f"flush cycles {c['wake.cycles']}, mean {c.get('wake.flushMs', 0) / c['wake.cycles']:.0f} ms awake each, timeouts {c.get('wake.timeouts', 0)}")
    print("accelerometer completeness: compare accel/h per state in section 2; whole minutes hold 375 readings, 22,500 an hour")


def hrv(events):
    print("\n## 5. Night-time HRV windows\n")
    night = [e for e in events if e["src"] == "NightHrv"]
    if not night:
        print("None started (they need bedtime mode, the watch worn and off the charger, ten still minutes).")
        return
    started = collections.Counter(e.get("source") for e in night if e["ev"] == "window_started")
    print("started per source:", dict(started))
    drops = collections.Counter(e.get("why") for e in night if e["ev"] == "window_dropped")
    print("dropped:", dict(drops))
    for name, kind in (("raw PPG", "ppg_window"), ("Samsung", "samsung_window")):
        windows = [e for e in night if e["ev"] == kind]
        values = [e["rmssd"] for e in windows if e.get("rmssd") is not None]
        if not windows:
            continue
        line = f"{name}: {len(windows)} windows, {len(values)} with a value"
        if values:
            line += f", RMSSD median {statistics.median(values):.1f} ms ({min(values):.1f}-{max(values):.1f})"
        if kind == "samsung_window":
            waits = [e["waitMs"] / 1000 for e in windows]
            flagged = sum(e.get("flagged", 0) for e in windows)
            total = sum(e.get("intervals", 0) for e in windows)
            line += f"; flush wait median {statistics.median(waits):.1f} s; flagged {flagged} of {total} intervals"
        print(line)
    stored = [e for e in night if e["ev"] == "window_stored"]
    print(f"stored: {len(stored)}" + "".join(f"\n  {when(e['startedAt'])}  {e['rmssd']:.1f} ms  {e['beats']} beats" for e in stored))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("files", nargs="*", help="saved protocol files; without them the watch is asked over adb")
    parser.add_argument("--serial", help="adb serial of the watch")
    parser.add_argument("--save", help="directory to keep the pulled files in")
    args = parser.parse_args()
    if args.files:
        lines = []
        for path in args.files:
            with open(path) as f:
                lines += f.read().splitlines()
    else:
        lines = pull(args.serial, args.save)
    rows = parse(lines)
    if not rows:
        sys.exit("The protocol is empty: is the debug build installed and its service running?")
    snaps = [r for r in rows if r["k"] == "snap"]
    events = [r for r in rows if r["k"] == "ev"]
    print(f"# Watch protocol {when(rows[0]['t'])} to {when(rows[-1]['t'])}: {len(snaps)} snapshots, {len(events)} events")
    plans = [e for e in events if e["src"] == "HeartRate" and e["ev"] == "plan"]
    if plans:
        print("heart rate plans:", ", ".join(f"{when(e['t'])} {e['intervalSec']}s {e['source']}" for e in plans[-8:]))
    per = drain(snaps)
    if per:
        wakeups(per)
    power_save(snaps)
    samsung(events)
    samsung_lag(snaps)
    hrv(events)
    holds(snaps, events)


if __name__ == "__main__":
    main()
