@ECHO OFF
SETLOCAL ENABLEEXTENSIONS

SET DIR=%~dp0
SET WRAPPER_JAR=%DIR%gradle\wrapper\gradle-wrapper.jar
SET WRAPPER_PROPS=%DIR%gradle\wrapper\gradle-wrapper.properties

IF EXIST "%WRAPPER_JAR%" (
  IF "%JAVA_HOME%"=="" (
    SET JAVA_EXE=java.exe
  ) ELSE (
    SET JAVA_EXE=%JAVA_HOME%\bin\java.exe
  )
  "%JAVA_EXE%" -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
  EXIT /B %ERRORLEVEL%
)

IF NOT EXIST "%WRAPPER_PROPS%" (
  ECHO Missing gradle-wrapper.properties
  EXIT /B 1
)

FOR /F "tokens=1,* delims==" %%A IN (%WRAPPER_PROPS%) DO (
  IF "%%A"=="distributionUrl" SET DIST_URL=%%B
)

IF "%DIST_URL%"=="" (
  ECHO distributionUrl not found in gradle-wrapper.properties
  EXIT /B 1
)

SET DIST_URL=%DIST_URL:\=%
FOR %%I IN (%DIST_URL%) DO SET DIST_FILE=%%~nxI
SET DIST_NAME=%DIST_FILE:.zip=%
SET DIST_NAME=%DIST_NAME:-bin=%
SET DIST_NAME=%DIST_NAME:-all=%
SET CACHE_DIR=%DIR%.gradle\wrapper\fallback
SET ZIP_PATH=%CACHE_DIR%\%DIST_FILE%
SET GRADLE_HOME=%CACHE_DIR%\%DIST_NAME%
SET GRADLE_BIN=%GRADLE_HOME%\bin\gradle.bat

IF NOT EXIST "%CACHE_DIR%" MKDIR "%CACHE_DIR%"

IF NOT EXIST "%ZIP_PATH%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%ZIP_PATH%'"
  IF ERRORLEVEL 1 EXIT /B 1
)

IF NOT EXIST "%GRADLE_BIN%" (
  IF EXIST "%GRADLE_HOME%" RMDIR /S /Q "%GRADLE_HOME%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%ZIP_PATH%' -DestinationPath '%CACHE_DIR%' -Force"
  IF ERRORLEVEL 1 EXIT /B 1
)

CALL "%GRADLE_BIN%" %*
EXIT /B %ERRORLEVEL%
