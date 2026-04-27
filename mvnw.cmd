@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF)
@REM Maven Wrapper startup batch script, version 3.2.0
@REM ----------------------------------------------------------------------------
@ECHO OFF

SET MAVEN_PROJECTBASEDIR=%~dp0

SET WRAPPER_JAR="%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.jar"
SET WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain

SET DOWNLOAD_URL=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar

IF NOT EXIST %WRAPPER_JAR% (
    ECHO Downloading Maven Wrapper...
    powershell -Command "Invoke-WebRequest -Uri '%DOWNLOAD_URL%' -OutFile %WRAPPER_JAR% -UseBasicParsing"
)

SET JAVA_HOME_CANDIDATE=C:\Program Files\Java\jdk-24
IF EXIST "%JAVA_HOME_CANDIDATE%\bin\java.exe" SET JAVA_HOME=%JAVA_HOME_CANDIDATE%

java -Dmaven.multiModuleProjectDirectory="%MAVEN_PROJECTBASEDIR%." -cp %WRAPPER_JAR% %WRAPPER_LAUNCHER% %*
