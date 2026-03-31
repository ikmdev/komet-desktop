@REM Maven Wrapper launcher — installed by ike:init
@REM Downloads and caches the Maven version specified in
@REM .mvn/wrapper/maven-wrapper.properties
@echo off
setlocal

set "PROPS_FILE=%~dp0.mvn\wrapper\maven-wrapper.properties"
if not exist "%PROPS_FILE%" (
    echo Error: %PROPS_FILE% not found >&2
    exit /b 1
)

for /f "tokens=1,* delims==" %%a in ('findstr "^maven.version=" "%PROPS_FILE%"') do set "MAVEN_VERSION=%%b"
for /f "tokens=1,* delims==" %%a in ('findstr "^distributionUrl=" "%PROPS_FILE%"') do set "DIST_URL=%%b"

set "WRAPPER_HOME=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%"
set "MAVEN_HOME=%WRAPPER_HOME%\apache-maven-%MAVEN_VERSION%"

if not exist "%MAVEN_HOME%" (
    echo Downloading Maven %MAVEN_VERSION%...
    mkdir "%WRAPPER_HOME%" 2>nul
    set "ZIP_FILE=%WRAPPER_HOME%\apache-maven-%MAVEN_VERSION%-bin.zip"
    powershell -Command "Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%ZIP_FILE%'"
    powershell -Command "Expand-Archive -Path '%ZIP_FILE%' -DestinationPath '%WRAPPER_HOME%' -Force"
    del "%ZIP_FILE%"
    echo Maven %MAVEN_VERSION% installed to %MAVEN_HOME%
)

"%MAVEN_HOME%\bin\mvn.cmd" %*
