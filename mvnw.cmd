@echo off
setlocal

set "EXEC_DIR=%CD%"
set "BASE_DIR=%~dp0"
if "%BASE_DIR:~-1%"=="\" set "BASE_DIR=%BASE_DIR:~0,-1%"

if exist "%BASE_DIR%\.mvn\wrapper\maven-wrapper.properties" goto baseResolved

set "SEARCH_DIR=%EXEC_DIR%"
:findBaseDir
if exist "%SEARCH_DIR%\.mvn\wrapper\maven-wrapper.properties" (
    set "BASE_DIR=%SEARCH_DIR%"
    goto baseResolved
)
cd ..
if "%SEARCH_DIR%"=="%CD%" goto baseNotFound
set "SEARCH_DIR=%CD%"
goto findBaseDir

:baseNotFound
cd "%EXEC_DIR%"
echo Error: Unable to locate .mvn\wrapper\maven-wrapper.properties. >&2
exit /b 1

:baseResolved
cd "%EXEC_DIR%"

if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

if not exist "%JAVA_EXE%" (
    for /f "delims=" %%I in ('where java.exe 2^>nul') do (
        set "JAVA_EXE=%%I"
        goto javaResolved
    )
)

:javaResolved
if not exist "%JAVA_EXE%" (
    echo Error: JAVA_HOME is not set and java.exe was not found on PATH. >&2
    exit /b 1
)

set "WRAPPER_DIR=%BASE_DIR%\.mvn\wrapper"
set "WRAPPER_JAR=%WRAPPER_DIR%\maven-wrapper.jar"
set "WRAPPER_PROPERTIES=%WRAPPER_DIR%\maven-wrapper.properties"
set "WRAPPER_URL=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.4/maven-wrapper-3.3.4.jar"

for /f "usebackq tokens=1,* delims==" %%A in ("%WRAPPER_PROPERTIES%") do (
    if /i "%%A"=="wrapperUrl" set "WRAPPER_URL=%%B"
)

if not exist "%WRAPPER_JAR%" (
    powershell -NoProfile -Command ^
        "$ProgressPreference='SilentlyContinue';" ^
        "New-Item -ItemType Directory -Force -Path '%WRAPPER_DIR%' | Out-Null;" ^
        "(New-Object System.Net.WebClient).DownloadFile('%WRAPPER_URL%', '%WRAPPER_JAR%')"
    if errorlevel 1 exit /b 1
)

"%JAVA_EXE%" ^
  -classpath "%WRAPPER_JAR%" ^
  "-Dmaven.multiModuleProjectDirectory=%BASE_DIR%" ^
  org.apache.maven.wrapper.MavenWrapperMain %*
set "ERROR_CODE=%ERRORLEVEL%"

exit /b %ERROR_CODE%
