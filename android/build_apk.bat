@echo off
setlocal enabledelayedexpansion

echo =======================================================
echo          MediaPro Android APK Build Script
echo =======================================================

if "%ANDROID_HOME%"=="" (
    if exist "C:\Users\%USERNAME%\AppData\Local\Android\Sdk" (
        set "ANDROID_HOME=C:\Users\%USERNAME%\AppData\Local\Android\Sdk"
    ) else (
        echo [ERROR] ANDROID_HOME not set and default SDK path not found!
        exit /b 1
    )
)

set "BT=%ANDROID_HOME%\build-tools\33.0.1"
if not exist "%BT%\aapt2.exe" (
    for /d %%D in ("%ANDROID_HOME%\build-tools\*") do (
        if exist "%%D\aapt2.exe" set "BT=%%D"
    )
)

set "PLAT=%ANDROID_HOME%\platforms\android-34\android.jar"
if not exist "%PLAT%" (
    for /d %%P in ("%ANDROID_HOME%\platforms\android-*") do (
        if exist "%%P\android.jar" set "PLAT=%%P\android.jar"
    )
)

echo SDK Build Tools: %BT%
echo Android Platform: %PLAT%

set "ROOT=%~dp0"
set "WORK=%ROOT%build"
set "GEN=%WORK%\gen"
set "CLASSES=%WORK%\classes"
set "DEX=%WORK%\dex"
set "APP_DIR=%ROOT%"
set "WWW_DIR=%ROOT%assets\www"

if exist "%WORK%" rd /s /q "%WORK%"
if not exist "%WWW_DIR%" mkdir "%WWW_DIR%"
mkdir "%GEN%" "%CLASSES%" "%DEX%"

echo [1/7] Copying Web App assets...
copy /y "%ROOT%..\MediaPro.html" "%WWW_DIR%\MediaPro.html" >nul

echo [2/7] Compiling Android Resources (aapt2 compile)...
"%BT%\aapt2.exe" compile --dir "%APP_DIR%res" -o "%WORK%\res.zip"
if %errorlevel% neq 0 ( echo AAPT2 compile failed & exit /b 1 )

echo [3/7] Linking Resources (aapt2 link)...
"%BT%\aapt2.exe" link -o "%WORK%\app.unaligned.apk" ^
    -I "%PLAT%" ^
    --manifest "%APP_DIR%AndroidManifest.xml" ^
    -R "%WORK%\res.zip" ^
    -A "%APP_DIR%assets" ^
    --java "%GEN%" ^
    --auto-add-overlay
if %errorlevel% neq 0 ( echo AAPT2 link failed & exit /b 1 )

echo [4/7] Compiling Java Sources (javac)...
dir /s /b "%APP_DIR%java\*.java" "%GEN%\*.java" > "%WORK%\sources.txt"
javac -encoding UTF-8 ^
    -cp "%PLAT%" ^
    -d "%CLASSES%" ^
    @"%WORK%\sources.txt"
if %errorlevel% neq 0 ( echo Javac compilation failed & exit /b 1 )

echo [5/7] Converting to Dalvik Executable (R8/D8)...
if not exist "%ROOT%r8.jar" (
    echo Downloading official Google R8 compiler...
    curl -s -L -o "%ROOT%r8.jar" https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.33/r8-8.2.33.jar
)
dir /s /b "%CLASSES%\*.class" > "%WORK%\classes.txt"
java -cp "%ROOT%r8.jar" com.android.tools.r8.D8 --release --min-api 24 --lib "%PLAT%" --output "%DEX%" @"%WORK%\classes.txt"
if %errorlevel% neq 0 ( echo D8 dexing failed & exit /b 1 )

echo [6/7] Packaging APK...
copy "%WORK%\app.unaligned.apk" "%WORK%\app.withdex.apk" >nul
cd /d "%DEX%"
jar uf "%WORK%\app.withdex.apk" classes.dex
cd /d "%ROOT%"

"%BT%\zipalign.exe" -f -p 4 "%WORK%\app.withdex.apk" "%WORK%\app.aligned.apk"
if %errorlevel% neq 0 ( echo Zipalign failed & exit /b 1 )

echo [7/7] Signing APK...
set "KEYSTORE=%ROOT%mediapro.keystore"
if not exist "%KEYSTORE%" (
    keytool -genkeypair -v -keystore "%KEYSTORE%" -alias mediapro ^
        -keyalg RSA -keysize 2048 -validity 10000 ^
        -storepass mediapro2026 -keypass mediapro2026 ^
        -dname "CN=MediaPro, O=MediaPro, C=US" 2>nul
)

java -jar "%BT%\lib\apksigner.jar" sign --ks "%KEYSTORE%" ^
    --ks-key-alias mediapro --ks-pass pass:mediapro2026 --key-pass pass:mediapro2026 ^
    --out "%ROOT%..\MediaPro.apk" "%WORK%\app.aligned.apk"
if %errorlevel% neq 0 ( echo Apksigner failed & exit /b 1 )

echo.
echo =======================================================
echo  SUCCESS: Built signed APK at: %ROOT%..\MediaPro.apk
echo =======================================================
