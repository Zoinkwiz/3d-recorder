#!/bin/sh
# Double-click on a Mac: a RuneLite development client with this plugin, on JDK 11.
cd "$(dirname "$0")" && JAVA_HOME=$(/usr/libexec/java_home -v 11) exec ./gradlew run --no-daemon
