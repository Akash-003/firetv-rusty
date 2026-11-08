#!/bin/sh
# Snapshot the TV before changing anything: package lists, disabled packages, getprop, all settings and the
# current home activity. Stored in backup/ (gitignored: it holds device identifiers).
cd "$(dirname "$0")"
mkdir -p backup
./tv sh 'pm list packages -f -u' > backup/packages_all.txt
./tv sh 'pm list packages -d' > backup/packages_disabled.txt
./tv sh 'pm list packages -s' > backup/packages_system.txt
./tv sh 'pm list packages -3' > backup/packages_thirdparty.txt
./tv sh 'getprop' > backup/getprop.txt
for ns in system secure global; do ./tv sh "settings list $ns" > backup/settings_$ns.txt; done
./tv sh 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' > backup/home_activity.txt
./tv shot backup/home_before.png
echo "Saved to backup/"
