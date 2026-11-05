#!/usr/bin/env bash
set -e

echo "==================================================="
echo "          RikkaHub Agent Local Launcher            "
echo "==================================================="
echo ""

# 1. Locate Java
JAVA_CMD=""
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_CMD="java"
else
    echo "[ERROR] Java 17+ was not found on your system!"
    echo "Please install JDK/JRE 17 or higher and ensure java is in PATH."
    exit 1
fi

# 2. Check Java version >= 17
JAVA_VER=$("$JAVA_CMD" -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F '.' '{print $1}')
if [ "$JAVA_VER" -lt 17 ] 2>/dev/null; then
    echo "[ERROR] Java 17 or higher is required! Detected version: $JAVA_VER"
    exit 1
fi

echo "[OK] Using Java: $JAVA_CMD (major version $JAVA_VER)"

# 3. Locate JAR
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH=""

if [ -f "$SCRIPT_DIR/../lib/backend.jar" ]; then
    JAR_PATH="$SCRIPT_DIR/../lib/backend.jar"
elif [ -f "$SCRIPT_DIR/../../backend/target/backend-1.0-SNAPSHOT.jar" ]; then
    JAR_PATH="$SCRIPT_DIR/../../backend/target/backend-1.0-SNAPSHOT.jar"
fi

if [ -z "$JAR_PATH" ]; then
    echo "[ERROR] Backend JAR was not found!"
    echo "Please run 'mvn package' first."
    exit 1
fi

echo "[OK] Using JAR: $JAR_PATH"

PORT="${SERVER_PORT:-8081}"
echo "[INFO] Starting RikkaHub on http://127.0.0.1:$PORT ..."

# 4. Open browser in background
(
    sleep 2
    if command -v xdg-open >/dev/null 2>&1; then
        xdg-open "http://127.0.0.1:$PORT" >/dev/null 2>&1 &
    elif command -v open >/dev/null 2>&1; then
        open "http://127.0.0.1:$PORT" >/dev/null 2>&1 &
    fi
) &

# 5. Execute
exec "$JAVA_CMD" -Dfile.encoding=UTF-8 -jar "$JAR_PATH" --server.port="$PORT"
