#!/bin/sh
APP_BASE_NAME=`basename "$0"`
DIRNAME=`dirname "$0"`
if [ "$DIRNAME" = "" ]; then
    DIRNAME=.
fi
CLASSPATH=$DIRNAME/gradle/wrapper/gradle-wrapper.jar
JAVA_EXE="java"
exec "$JAVA_EXE" -Dorg.gradle.appname="$APP_BASE_NAME" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
