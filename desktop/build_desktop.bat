@echo off
setlocal enabledelayedexpansion

echo =======================================================
echo          MediaPro Desktop Windows Build Script
echo =======================================================

set "ROOT=%~dp0"
cd /d "%ROOT%"

if not exist "app" mkdir "app"
copy /y "%ROOT%..\MediaPro.html" "%ROOT%app\MediaPro.html" >nul

echo [1/3] Checking dependencies...
if not exist "node_modules" (
    echo Installing electron and electron-builder...
    call npm install
)

echo [2/3] Building portable Windows executable...
call npx electron-builder --win portable

echo [3/3] Copying output...
if exist "dist\MediaPro-Windows-Portable.exe" (
    copy /y "dist\MediaPro-Windows-Portable.exe" "%ROOT%..\MediaPro-Windows-Portable.exe" >nul
    echo.
    echo =======================================================
    echo  SUCCESS: Output at: %ROOT%..\MediaPro-Windows-Portable.exe
    echo =======================================================
) else (
    echo [WARNING] Portable executable not found in dist/
)
