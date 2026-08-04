@rem Convenience wrapper: this machine's default JDK 17 install has a broken
@rem java.nio Selector/Pipe implementation on Windows (Selector.open() fails with
@rem "Unable to establish loopback connection" via AF_UNIX). That breaks Gradle's
@rem client<->daemon socket entirely, unrelated to this project's code.
@rem JDK 11 does not hit the bug, so this project pins org.gradle.java.home to
@rem JDK 11 in gradle.properties AND overrides JAVA_HOME here for gradlew.bat's
@rem own launcher process (which reads JAVA_HOME before gradle.properties is
@rem ever parsed). Use this script instead of gradlew.bat directly.
@rem
@rem Usage: gradlew-jdk11.bat assembleDebug
@echo off
set "JAVA_HOME=C:\Program Files\Java\jdk-11.0.15"
call "%~dp0gradlew.bat" %*
