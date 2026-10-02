@echo off
chcp 65001 >nul
rem ============================================================
rem  一键切换"手表息屏是否保持 Wi-Fi"
rem  普通第三方 App 无法把开关加进手表系统下拉面板（需要系统权限），
rem  所以做成 PC 侧一键脚本 —— 双击即可，无需命令行知识。
rem ============================================================

set ADB=D:\Android\android-sdk\platform-tools\adb.exe
set TARGET=192.168.3.213:5555
set PS=powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0wifi-stay-awake.ps1" -Adb "%ADB%" -Target "%TARGET%"

if not exist "%ADB%" (
  echo [错误] 找不到 adb：%ADB%
  echo 请修改本文件里的 ADB 路径。
  pause
  exit /b 1
)

:MENU
cls
echo ============================================
echo   手表息屏 Wi-Fi 开关
echo ============================================
echo.
echo   当前状态：
%PS% -Verify 2>nul | findstr /C:"当前策略" /C:"Wi-Fi is"
echo.
echo   [1] 息屏保持 Wi-Fi（推荐，费电一些）
echo   [2] 恢复系统默认（息屏关 Wi-Fi，省电）
echo   [3] 只看当前状态
echo   [0] 退出
echo.
set /p CH=请输入数字并回车： 

if "%CH%"=="1" ( %PS% -Policy 2 & echo. & pause & goto MENU )
if "%CH%"=="2" ( %PS% -Policy 1 & echo. & pause & goto MENU )
if "%CH%"=="3" ( %PS% -Verify & echo. & pause & goto MENU )
if "%CH%"=="0" exit /b 0
goto MENU
