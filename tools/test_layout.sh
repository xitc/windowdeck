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
  "$project_dir/module/src/dev/windowdeck/app/PaneLayout.java" \
  "$project_dir/module/src/dev/windowdeck/app/CardLayout.java" \
  "$project_dir/module/src/dev/windowdeck/app/OrientationPolicy.java" \
  "$project_dir/tests/CardLayoutTest.java"
java -cp "$out" dev.windowdeck.app.CardLayoutTest

javac -encoding UTF-8 -d "$out" \
  "$project_dir/module/src/dev/windowdeck/app/GesturePolicy.java" \
  "$project_dir/tests/GesturePolicyTest.java"
java -cp "$out" dev.windowdeck.app.GesturePolicyTest

javac -encoding UTF-8 -d "$out" \
  "$project_dir/module/src/dev/windowdeck/app/OrientationPolicy.java" \
  "$project_dir/tests/OrientationPolicyTest.java"
java -cp "$out" dev.windowdeck.app.OrientationPolicyTest

javac -encoding UTF-8 -d "$out" \
  "$project_dir/module/src/dev/windowdeck/app/BackdropPolicy.java" \
  "$project_dir/tests/BackdropPolicyTest.java"
java -cp "$out" dev.windowdeck.app.BackdropPolicyTest

javac -encoding UTF-8 -d "$out" \
  "$project_dir/module/src/dev/windowdeck/app/SurfaceFit.java" \
  "$project_dir/tests/SurfaceFitTest.java"
java -cp "$out" dev.windowdeck.app.SurfaceFitTest

javac -encoding UTF-8 -d "$out" \
  "$project_dir/module/src/dev/windowdeck/app/PaneLayout.java" \
  "$project_dir/module/src/dev/windowdeck/app/CardPerspective.java" \
  "$project_dir/tests/CardPerspectiveTest.java"
java -cp "$out" dev.windowdeck.app.CardPerspectiveTest

javac -encoding UTF-8 -d "$out" \
  "$project_dir/module/src/dev/windowdeck/app/IngressPolicy.java" \
  "$project_dir/tests/IngressPolicyTest.java"
java -cp "$out" dev.windowdeck.app.IngressPolicyTest

javac -encoding UTF-8 -d "$out" \
  "$project_dir/module/src/dev/windowdeck/app/SwipePanelPolicy.java" \
  "$project_dir/tests/SwipePanelPolicyTest.java"
java -cp "$out" dev.windowdeck.app.SwipePanelPolicyTest
