#!/bin/sh
# Disable every package in debloat_list.txt, one pm call per shell session.
# Successes are appended to backup/disabled_by_us.txt for restore.sh.
cd "$(dirname "$0")"
mkdir -p backup
grep -v '^#' debloat_list.txt | grep . | while read -r pkg; do
  out=$(./tv sh "pm disable-user --user 0 $pkg" </dev/null 2>&1 | tail -1)
  echo "$pkg: $out"
  case "$out" in *"new state: disabled"*) echo "$pkg" >> backup/disabled_by_us.txt ;; esac
done
