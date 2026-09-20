#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
out="$project_dir/build/layout-tests"
mkdir -p "$out"
javac -encoding UTF-8 -d "$out" \
  "$project_dir/module/src/dev/windowdeck/app/PaneLayout.java" \
  "$project_dir/tests/PaneLayoutTest.java"
java -cp "$out" dev.windowdeck.app.PaneLayoutTest

javac -encoding UTF-8 -d "$out" \
  "$project_dir/module/src/dev/windowdeck/app/GesturePolicy.java" \
  "$project_dir/tests/GesturePolicyTest.java"
java -cp "$out" dev.windowdeck.app.GesturePolicyTest

javac -encoding UTF-8 -d "$out" \
  "$project_dir/module/src/dev/windowdeck/app/OrientationPolicy.java" \
  "$project_dir/tests/OrientationPolicyTest.java"
java -cp "$out" dev.windowdeck.app.OrientationPolicyTest
