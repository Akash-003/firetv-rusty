#!/bin/sh
# Re-enable every package we disabled (logged in backup/disabled_by_us.txt)
# and give the Home button back to the stock Amazon launcher.
# Usage: ./restore.sh            (TV_IP=192.168.1.18 by default)
cd "$(dirname "$0")"
LOG=backup/disabled_by_us.txt
# Stop routing the Home button to Rusty
./tv sh 'settings put secure enabled_accessibility_services ""'
./tv sh 'settings put secure accessibility_enabled 0'
# Stock launcher first so Home always has somewhere to go
./tv sh 'pm enable com.amazon.tv.launcher'
./tv sh 'cmd package set-home-activity com.amazon.tv.launcher/.ui.HomeActivity_vNext'
[ -f "$LOG" ] || { echo "no disabled-package log ($LOG)"; exit 0; }
# one pm call per shell session: chained pm commands can stop after the first
sort -u "$LOG" | while read -r pkg; do
  [ -n "$pkg" ] && echo "$pkg: $(./tv sh "pm enable $pkg" </dev/null)"
done
echo "Done. Optional: ./tv sh 'pm uninstall dev.akash.rusty' to remove the custom launcher."
