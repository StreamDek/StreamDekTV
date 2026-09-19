#!/system/bin/sh
# Run via adb shell after verifying Home is settled and the first card of a row
# containing at least two items has focus. Do not run on the profile picker or Fuse portal.
# Keep device, build type, profile, row, artwork cache and compilation mode consistent.
set -e
dumpsys gfxinfo com.streamdek.tv reset >/dev/null
for cycle in 1 2 3 4 5 6; do
  input keyevent 22
  sleep 0.5
  input keyevent 21
  sleep 0.5
done
dumpsys gfxinfo com.streamdek.tv framestats
