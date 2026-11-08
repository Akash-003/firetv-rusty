#!/usr/bin/env python3
"""Tiny ADB client for the TV (macOS Local Network privacy blocks stock adb).

usage:
  tv.py sh  '<cmd>'            run a shell command, print output
  tv.py key KEYCODE [...]      inject key presses (DPAD_DOWN, HOME, ...)
  tv.py shot out.png           screenshot from the TV
  tv.py push local remote      push file as base64 chunks, md5-verified
"""
import base64, hashlib, os, sys, time
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

HOST = os.environ.get("TV_IP", "192.168.1.18")
KEY = os.path.expanduser("~/.android/adbkey")


def connect():
    with open(KEY) as f:
        priv = f.read()
    with open(KEY + ".pub") as f:
        pub = f.read()
    d = AdbDeviceTcp(HOST, 5555, default_transport_timeout_s=30)
    d.connect(rsa_keys=[PythonRSASigner(pub, priv)], auth_timeout_s=60)
    return d


def sh(d, cmd, timeout=120):
    return d.shell(cmd, timeout_s=timeout, read_timeout_s=timeout)


def push(d, local, remote):
    data = open(local, "rb").read()
    b64 = base64.b64encode(data).decode()
    tmp = remote + ".b64"
    sh(d, f"rm -f '{tmp}'")
    step = 48000  # ponytail: shell arg limit ~128K, 48K chunks stay well under it
    for i in range(0, len(b64), step):
        sh(d, f"echo -n '{b64[i:i + step]}' >> '{tmp}'")
    sh(d, f"base64 -d '{tmp}' > '{remote}' && rm '{tmp}'")
    remote_md5 = sh(d, f"md5sum '{remote}'").split()[0]
    local_md5 = hashlib.md5(data).hexdigest()
    if remote_md5 != local_md5:
        sys.exit(f"md5 mismatch {local_md5} != {remote_md5}")
    print(f"pushed {len(data)} bytes, md5 ok")


def main():
    cmd, args = sys.argv[1], sys.argv[2:]
    d = connect()
    if cmd == "sh":
        print(sh(d, args[0]), end="")
    elif cmd == "key":
        for k in args:
            sh(d, f"input keyevent KEYCODE_{k}")
            time.sleep(0.35)
    elif cmd == "shot":
        b64 = sh(d, "screencap -p /data/local/tmp/s.png && base64 /data/local/tmp/s.png")
        open(args[0], "wb").write(base64.b64decode("".join(b64.split())))
        print(args[0])
    elif cmd == "push":
        push(d, args[0], args[1])
    d.close()


if __name__ == "__main__":
    main()
