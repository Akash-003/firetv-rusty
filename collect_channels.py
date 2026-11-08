#!/usr/bin/env python3
"""Rebuild channels/channels.json from the Fire TV Live TV guide.

Fire OS keeps the guide's channels private to Amazon's apps, but the guide logs each channel
(name + the exact link Amazon uses to play it) when it is focused. This opens the guide, walks
down it a step at a time, and reads those log lines before the small log buffer rotates.

    ./collect_channels.py && ./push_channels.sh
"""
import json, re, subprocess, os
HERE = os.path.dirname(os.path.abspath(__file__))
TV = os.path.join(HERE, "tv")

def tv(cmd):
    return subprocess.run([TV, "sh", cmd], capture_output=True, text=True).stdout

tv("logcat -c; am start -a android.intent.action.VIEW -d livetv://showEpg")
subprocess.run(["sleep", "5"])
seen, misses = {}, 0
while misses < 4:  # stop after 4 steps with nothing new: the guide wraps back to the top
    out = tv("input keyevent KEYCODE_DPAD_DOWN; sleep 0.4; input keyevent KEYCODE_DPAD_DOWN; sleep 0.6;"
             " logcat -d | grep 'Updating last focused station'; logcat -c")
    new = 0
    for line in out.splitlines():
        cid = re.search(r"mTifChannelId=(\d+)", line)
        name = re.search(r"mDisplayName='([^']*)'", line)
        link = re.search(r"mPlaybackDeepLinkUri='([^']*)'", line)
        if cid and name and link and link.group(1) != "null" and cid.group(1) not in seen:
            seen[cid.group(1)] = {"name": name.group(1), "link": link.group(1)}
            new += 1
    misses = 0 if new else misses + 1
tv("input keyevent KEYCODE_BACK")
channels = [seen[k] for k in sorted(seen, key=int)]  # guide order = channel id order
exclude_path = os.path.join(HERE, "channels", "exclude.txt")  # channel names you don't want, one per line
if os.path.exists(exclude_path):
    excluded = {line.strip() for line in open(exclude_path) if line.strip()}
    channels = [c for c in channels if c["name"] not in excluded]
path = os.path.join(HERE, "channels", "channels.json")
if os.path.exists(path):  # keep logos / tile colours already set up for channels that are still there
    old = {c["name"]: c for c in json.load(open(path))}
    for c in channels:
        for k in ("logo", "bg"):
            if k in old.get(c["name"], {}):
                c[k] = old[c["name"]][k]
with open(path, "w") as f:
    json.dump(channels, f, indent=1, ensure_ascii=False)
print(f"{len(channels)} channels -> channels/channels.json")
