@echo off
setlocal enabledelayedexpansion

title RikkaHub Agent Desktop Launcher

echo ===================================================
echo           RikkaHub Agent Local Launcher
echo ===================================================
echo.

:: 1. Locate Java runtime
set "JAVA_CMD=java"
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
    )
)

"%JAVA_CMD%" -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java 17+ was not found on your system!
    echo Please install JDK/JRE 17 or higher and set JAVA_HOME.
    echo Download: https://adoptium.net/
    echo.
    pause
    exit /b 1
)

:: 2. Check Java version >= 17
set "RAW_VER="
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set "RAW_VER=%%~v"
)
if not defined RAW_VER (
    for /f "tokens=2" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
        set "RAW_VER=%%~v"
    )
)

set "RAW_VER=%RAW_VER:"=%"
for /f "tokens=1 delims=." %%m in ("%RAW_VER%") do (
    set "MAJOR_VER=%%m"
)

if not defined MAJOR_VER (
    set "MAJOR_VER=17"
)

if %MAJOR_VER% LSS 17 (
    echo [ERROR] Found Java version %RAW_VER%, but RikkaHub requires Java 17 or higher!
    echo Please upgrade your Java runtime.
    echo.
    pause
    exit /b 1
)

echo [OK] Using Java: %JAVA_CMD% (version %RAW_VER%)

:: 3. Locate backend JAR
set "JAR_PATH="
if exist "%~dp0..\lib\backend.jar" (
    set "JAR_PATH=%~dp0..\lib\backend.jar"
) else if exist "%~dp0..\..\backend\target\backend-1.0-SNAPSHOT.jar" (
    set "JAR_PATH=%~dp0..\..\backend\target\backend-1.0-SNAPSHOT.jar"
)

if not defined JAR_PATH (
    echo [ERROR] Backend JAR was not found!
    echo Expected: %~dp0..\lib\backend.jar
    echo Or: %~dp0..\..\backend\target\backend-1.0-SNAPSHOT.jar
    echo Please run 'mvn package' first.
    echo.
    pause
    exit /b 1
)

echo [OK] Using JAR: %JAR_PATH%

:: 4. Port configuration
if not defined SERVER_PORT set "SERVER_PORT=8081"
echo [INFO] Starting RikkaHub on http://127.0.0.1:%SERVER_PORT% ...
echo [INFO] User data directory defaults to %%LOCALAPPDATA%%\RikkaHub
echo.

:: Open browser after 2 seconds
start "" cmd /c "timeout /t 2 /nobreak >nul && start http://127.0.0.1:%SERVER_PORT%"

:: Launch Application
"%JAVA_CMD%" -Dfile.encoding=UTF-8 -jar "%JAR_PATH%" --server.port=%SERVER_PORT%

if errorlevel 1 (
    echo.
    echo [ERROR] RikkaHub exited with error code %errorlevel%.
    pause
)
