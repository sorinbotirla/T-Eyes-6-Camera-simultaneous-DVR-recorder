@echo off
setlocal
cd /d "%~dp0"
if not defined JAVA_HOME if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
call gradlew.bat assembleDebug lintDebug
exit /b %errorlevel%