#!/bin/bash
# Regenerates md5/sha1 checksums for the vendored DaVoice AARs (local Maven
# repo in libs/) and prints each AAR's date and size, so a stale copy is obvious.
# The private library repos' cp_lib.sh scripts copy fresh AARs here.
set -e
cd "$(dirname "$0")/libs/com/davoice"

md5_of() { if command -v md5sum >/dev/null; then md5sum "$1" | cut -d' ' -f1; else md5 -q "$1"; fi; }
sha1_of() { if command -v sha1sum >/dev/null; then sha1sum "$1" | cut -d' ' -f1; else shasum -a 1 "$1" | cut -d' ' -f1; fi; }

for artifact in keyworddetection tts llm; do
  dir="$artifact/1.0.0"
  for f in "$dir/$artifact-1.0.0.aar" "$dir/$artifact-1.0.0.pom"; do
    [ -f "$f" ] || { echo "missing: $f"; exit 1; }
    md5_of "$f" > "$f.md5"
    sha1_of "$f" > "$f.sha1"
  done
  ls -lh "$dir/$artifact-1.0.0.aar" | awk '{print $6, $7, $8, $5, $9}'
done
