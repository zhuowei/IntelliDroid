#!/bin/bash
set -e
cd "$(dirname $0)"
rm -r bin || true
mkdir bin

gator_path="../gator-3.4/SootAndroid"
gator_classpath="$gator_path/bin:$gator_path/lib/*"

javac -classpath "src:lib/*:$gator_classpath" -d bin src/intellidroid/appanalysis/gator/GUIHierarchyPrinterClient.java
