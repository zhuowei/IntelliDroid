#!/bin/bash
set -e
# make into absolute paths
gator_path="$(dirname $0)/../gator-3.4"

READLINK="readlink"
if [ "$(uname)" == "Darwin" ]; then
    READLINK="greadlink"
fi

if [ ! -e "$gator_path" ]; then
	echo "Gator not found: not running Gator. Please place gator-3.4 in AppAnalysis/preprocess."
	exit 0
fi

if [ ! -e "$gator_path/SootAndroid/bin/presto/android/Main.class" ]; then
	(cd "$gator_path/SootAndroid"; ant;)
fi
$(dirname $0)/build.sh
cp -r $(dirname $0)/bin/* $gator_path/SootAndroid/bin/
cp -r $(dirname $0)/lib/* $gator_path/SootAndroid/lib/
export GatorRoot="$(readlink -m $gator_path)"
if [ -z "$ADK" ]; then
	export ADK=$ANDROID_HOME
fi
absApkPath="$($READLINK -m $1)"
absOutputPath="$($READLINK -m $2)"
cd $gator_path/AndroidBench
echo python3 runGatorOnApk.py "$absApkPath" -client intellidroid.appanalysis.gator.GUIHierarchyPrinterClient -outputFile "$absOutputPath"
python3 runGatorOnApk.py "$absApkPath" -client intellidroid.appanalysis.gator.GUIHierarchyPrinterClient -outputFile "$absOutputPath"
