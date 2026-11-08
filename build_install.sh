#!/bin/sh
# Build the Rusty launcher (R8-optimised release, signed with the debug key), install it on the TV, make it the default home.
# Usage: ./build_install.sh      (TV_IP=192.168.1.18 by default)
set -e
cd "$(dirname "$0")"
PKG=dev.akash.rusty
APK=launcher/app/build/outputs/apk/release/app-release.apk

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
(cd launcher && ./gradlew -q assembleRelease)

# adb push can hang over this link, so tv.py pushes base64 chunks and checks md5
./tv push "$APK" /data/local/tmp/rusty.apk
./tv sh 'pm install -r /data/local/tmp/rusty.apk'
./tv sh 'rm /data/local/tmp/rusty.apk'
./tv sh "pm grant $PKG android.permission.ACCESS_COARSE_LOCATION"   # lets it read the Wi-Fi name
./tv sh "cmd package set-home-activity $PKG/.HomeActivity"
./push_channels.sh
# the Ruska wallpaper (a reinstall keeps it, but a fresh install starts without it)
./tv push themes/ruska/wallpaper.jpg /sdcard/Android/data/$PKG/files/wallpaper.jpg
./tv sh "am start -n $PKG/.HomeActivity" >/dev/null
# Never `am force-stop` the app: Android removes a force-stopped app's accessibility service from the enabled list.
echo "Installed. If the Menu button stops opening Rusty, run ./enable_home_button.sh."
