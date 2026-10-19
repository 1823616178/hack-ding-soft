@echo off
setlocal

cd /d "%~dp0"
set "ROOT=%~dp0"

echo ===================================================
echo   eFaceGo Android Thin Client Build Tool
echo ===================================================

REM 1. Set JDK 17
set "JAVA_HOME="
if exist "C:\Program Files\Java\jdk-17\bin\javac.exe" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-17"
) else if exist "C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot\bin\javac.exe" (
    set "JAVA_HOME=C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot"
) else if exist "C:\Program Files\Eclipse Adoptium\jdk-17\bin\javac.exe" (
    set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17"
) else if exist "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)

if "%JAVA_HOME%"=="" (
    echo [ERROR] JDK 17 not found. Please install JDK 17 to C:\Program Files\Java\jdk-17
    exit /b 1
)

echo [*] JAVA_HOME: %JAVA_HOME%

REM 2. Set Gradle user home to avoid global cache pollution
set "GRADLE_USER_HOME=%ROOT%.gradle-home"
echo [*] GRADLE_USER_HOME: %GRADLE_USER_HOME%

REM 3. Resolve gradle.bat
set "GRADLE_BAT=C:\Program Files\gradle\bin\gradle.bat"
if not exist "%GRADLE_BAT%" (
    set "GRADLE_BAT=gradle.bat"
)
echo [*] Gradle: %GRADLE_BAT%

REM 4. Parse argument mode
set "MODE=%~1"
if "%MODE%"=="" set "MODE=apk"

echo [*] Target mode: %MODE%
echo ---------------------------------------------------

if /i "%MODE%"=="apk" goto :build_apk
if /i "%MODE%"=="test" goto :run_test
if /i "%MODE%"=="all" goto :build_all
if /i "%MODE%"=="clean" goto :run_clean

echo [ERROR] Unknown mode: %MODE%
echo.
echo Usage:
echo   build.bat         - Build debug APK (default)
echo   build.bat apk     - Build debug APK
echo   build.bat test    - Run unit tests
echo   build.bat all     - Run unit tests and build APK
echo   build.bat clean   - Clean build cache
exit /b 1

:build_apk
call "%GRADLE_BAT%" --no-daemon :app:assembleDebug
if errorlevel 1 goto :error
goto :show_apk

:run_test
call "%GRADLE_BAT%" --no-daemon :app:testDebugUnitTest
if errorlevel 1 goto :error
echo.
echo [SUCCESS] All unit tests passed!
exit /b 0

:build_all
call "%GRADLE_BAT%" --no-daemon :app:testDebugUnitTest :app:assembleDebug
if errorlevel 1 goto :error
goto :show_apk

:run_clean
call "%GRADLE_BAT%" --no-daemon clean
if errorlevel 1 goto :error
echo.
echo [SUCCESS] Clean completed.
exit /b 0

:show_apk
set "APK_PATH=%ROOT%app\build\outputs\apk\debug\app-debug.apk"
if exist "%APK_PATH%" (
    echo.
    echo ===================================================
    echo   BUILD SUCCESSFUL!
    echo ===================================================
    echo APK: %APK_PATH%
    for %%F in ("%APK_PATH%") do echo Size: %%~zF bytes
    echo SHA-256:
    certutil -hashfile "%APK_PATH%" SHA256 | findstr /v ":" | findstr /v "CertUtil"
    echo ===================================================
) else (
    echo [WARN] APK not found at %APK_PATH%
)
exit /b 0

:error
echo.
echo [FAILED] Build failed. Please inspect the log above.
exit /b 1