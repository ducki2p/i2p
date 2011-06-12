#!/bin/bash
# When executed in Mingw: Produces a jbigi.dll
# When executed in Linux/FreeBSD: Produces a libjbigi.so
# When executed in OSX: Produces a libjbigi.jnilib
CC="gcc"

case `uname -sr` in
MINGW*)
	JAVA_HOME="c:/software/j2sdk1.4.2_05"
	COMPILEFLAGS="-Wall"
	INCLUDES="-I. -I../../jbigi/include -I$JAVA_HOME/include/win32/ -I$JAVA_HOME/include/"
	LINKFLAGS="-shared -Wl,--kill-at"
	LIBFILE="jbigi.dll";;
CYGWIN*)
	JAVA_HOME="c:/software/j2sdk1.4.2_05"
	COMPILEFLAGS="-Wall -mno-cygwin"
	INCLUDES="-I. -I../../jbigi/include -I$JAVA_HOME/include/win32/ -I$JAVA_HOME/include/"
	LINKFLAGS="-shared -Wl,--kill-at"
	LIBFILE="jbigi.dll";;
Darwin*)
	JAVA_HOME=$(/usr/libexec/java_home)
        COMPILEFLAGS="-fPIC -Wall"
        INCLUDES="-I. -I../../jbigi/include -I$JAVA_HOME/include"
        LINKFLAGS="-dynamiclib -framework JavaVM"
        LIBFILE="libjbigi.jnilib";;
SunOS*)
        COMPILEFLAGS="-fPIC -Wall"
        INCLUDES="-I. -I../../jbigi/include -I$JAVA_HOME/include -I$JAVA_HOME/include/solaris"
        LINKFLAGS="-shared -Wl,-soname,libjbigi.so"
        LIBFILE="libjbigi.so";;

*)
	COMPILEFLAGS="-fPIC -Wall"
	INCLUDES="-I. -I../../jbigi/include -I$JAVA_HOME/include -I$JAVA_HOME/include/linux"
	LINKFLAGS="-shared -Wl,-soname,libjbigi.so"
	LIBFILE="libjbigi.so";;
esac

if [ "$1" = "dynamic" ] ; then
	echo "Building a jbigi lib that is dynamically linked to GMP" 
	LIBPATH="-L.libs"
	INCLUDELIBS="-lgmp"
else
	echo "Building a jbigi lib that is statically linked to GMP"
	STATICLIBS=".libs/libgmp.a"
fi

echo "Compiling C code..."
rm -f jbigi.o $LIBFILE
$CC -c $COMPILEFLAGS $INCLUDES ../../jbigi/src/jbigi.c || exit 1
$CC $LINKFLAGS $INCLUDES $INCLUDELIBS -o $LIBFILE jbigi.o $STATICLIBS || exit 1

exit 0
