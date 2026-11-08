#!/bin/sh
# Copy channels/channels.json (and any logo files next to it) to Rusty on the TV.
# Rusty reloads it the next time it comes to the front (press Menu).
cd "$(dirname "$0")"
DEST=/sdcard/Android/data/dev.akash.rusty/files
./tv sh "mkdir -p $DEST/channels"
./tv push channels/channels.json $DEST/channels.json
for f in channels/*.png channels/*.jpg; do
  if [ -f "$f" ]; then ./tv push "$f" "$DEST/channels/$(basename "$f")"; fi
done
