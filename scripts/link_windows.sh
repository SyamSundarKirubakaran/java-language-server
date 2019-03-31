#!/bin/bash
# Links everything into a self-contained executable using jlink.

set -e

# Check JAVA_HOME points to correct java version
./scripts/check_java_home.sh

# Compile sources
mvn compile

# Patch gson
if [ ! -e modules/gson.jar ]; then
  ./scripts/patch_gson.sh
fi

# Download windows jdk
if [ ! -e jdks/windows/jdk-11.0.1 ]; then
  mkdir -p jdks/windows
  cd jdks/windows
  curl https://download.java.net/java/GA/jdk11/13/GPL/openjdk-11.0.1_windows-x64_bin.zip > windows.zip
  unzip windows.zip
  rm windows.zip
  cd ../..
fi

# Set env variables to build with mac toolchain but windows target
REAL_JAVA_HOME=$JAVA_HOME
JAVA_HOME="./jdks/windows/jdk-11.0.1"

# Build in dist/windows
rm -rf dist/windows
$REAL_JAVA_HOME/bin/jlink \
  --module-path $JAVA_HOME/jmods:modules/gson.jar:target/classes \
  --add-modules gson,javacs \
  --launcher launcher=javacs/org.javacs.Main \
  --output dist/windows \
  --compress 2 

# Restore launcher
echo '#!/bin/sh
JLINK_VM_OPTIONS="--add-exports jdk.compiler/com.sun.tools.javac.api=javacs --add-exports jdk.compiler/com.sun.tools.javac.code=javacs --add-exports jdk.compiler/com.sun.tools.javac.comp=javacs --add-exports jdk.compiler/com.sun.tools.javac.main=javacs --add-exports jdk.compiler/com.sun.tools.javac.tree=javacs --add-exports jdk.compiler/com.sun.tools.javac.model=javacs --add-exports jdk.compiler/com.sun.tools.javac.util=javacs"
DIR=`dirname $0`
$DIR/java $JLINK_VM_OPTIONS -m javacs/org.javacs.Main $@' > dist/windows/bin/launcher

echo '@echo off
set JLINK_VM_OPTIONS="--add-exports jdk.compiler/com.sun.tools.javac.api=javacs --add-exports jdk.compiler/com.sun.tools.javac.code=javacs --add-exports jdk.compiler/com.sun.tools.javac.comp=javacs --add-exports jdk.compiler/com.sun.tools.javac.main=javacs --add-exports jdk.compiler/com.sun.tools.javac.tree=javacs --add-exports jdk.compiler/com.sun.tools.javac.model=javacs --add-exports jdk.compiler/com.sun.tools.javac.util=javacs"
set DIR=%~dp0
"%DIR%\java" %JLINK_VM_OPTIONS% -m javacs/org.javacs.Main %*' > dist/windows/bin/launcher.bat