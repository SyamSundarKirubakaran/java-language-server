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

# Build using jlink
rm -rf dist/mac
$JAVA_HOME/bin/jlink \
  --module-path modules/gson.jar:target/classes \
  --add-modules gson,javacs \
  --launcher launcher=javacs/org.javacs.Main \
  --output dist/mac \
  --compress 2 

# Restore launcher
echo '#!/bin/sh
JLINK_VM_OPTIONS="--add-exports jdk.compiler/com.sun.tools.javac.api=javacs --add-exports jdk.compiler/com.sun.tools.javac.code=javacs --add-exports jdk.compiler/com.sun.tools.javac.comp=javacs --add-exports jdk.compiler/com.sun.tools.javac.main=javacs --add-exports jdk.compiler/com.sun.tools.javac.tree=javacs --add-exports jdk.compiler/com.sun.tools.javac.model=javacs --add-exports jdk.compiler/com.sun.tools.javac.util=javacs"
DIR=`dirname $0`
$DIR/java $JLINK_VM_OPTIONS -m javacs/org.javacs.Main $@' > dist/mac/bin/launcher