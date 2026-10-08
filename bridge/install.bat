@echo off
set /p CRAFTIFY_EXT_ID="Paste the extension ID from chrome://extensions: "
setlocal enabledelayedexpansion
"C:\Users\dogdu\AppData\Local\Python\bin\python.exe" "%~dp0install.py"
pause
