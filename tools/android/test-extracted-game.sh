#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
sources=(android/app/src/main/java/org/wwhdrecomp/app/ExtractedGame.java tools/android/ExtractedGameTest.java)
if command -v javac >/dev/null; then
  javac --release 17 -d "$test_dir" "${sources[@]}"
else
  # Some runtimes retain the compiler module without the javac executable or ct.sym archive.
  java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -d "$test_dir" "${sources[@]}"
fi
java -Xmx32m -cp "$test_dir" org.wwhdrecomp.app.ExtractedGameTest
