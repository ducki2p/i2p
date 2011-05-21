#/bin/sh
#
#  Build the jbigi library for i2p
#
#  To build a static library:
#     Set $I2P to point to your I2P installation
#     Set $JAVA_HOME to point to your Java SDK
#     build.sh
#       This script downloads gmp-4.3.2.tar.bz2 to this directory
#       (if a different version, change the VER= line below)
#
#  To build a dynamic library (you must have a libgmp.so somewhere in your system)
#     Set $I2P to point to your I2P installation
#     Set $JAVA_HOME to point to your Java SDK
#     build.sh dynamic
#
#  The resulting library is lib/libjbigi.so
#

mkdir -p lib/
mkdir -p bin/local
VER=4.3.2

set -e

if [ "$1" != "dynamic" -a ! -d gmp-$VER ]
then
	TAR=gmp-$VER.tar.bz2
        if [ ! -f $TAR ]
        then
	    echo "Downloading ftp://ftp.gmplib.org/pub/gmp-${VER}/gmp-${VER}.tar.bz2"
	    wget ftp://ftp.gmplib.org/pub/gmp-${VER}/gmp-${VER}.tar.bz2
        fi

	echo "Building the jbigi library with GMP Version $VER"

	echo "Extracting GMP..."
	tar -xjf gmp-$VER.tar.bz2
fi

cd bin/local

echo "Building..."
if [ "$1" != "dynamic" ]
then
	case `uname -sr` in
		Darwin*)
			# --with-pic is required for static linking
			../../gmp-$VER/configure --with-pic;;
		*)
			# and it's required for ASLR
			../../gmp-$VER/configure --with-pic;;
	esac
	make
	sh ../../build_jbigi.sh static
else
	sh ../../build_jbigi.sh dynamic
fi

cp *jbigi???* ../../lib/
echo 'Library copied to lib/'
cd ../..

if [ ! -f $I2P/lib/i2p.jar ]
then
	echo "I2P installation not found"
    echo "We looked in '$I2P'"
    echo "Not running tests against I2P installation without knowing where it is"
    echo "Please set the environment variable I2P to the location of your I2P installation (so that \$I2P/lib/i2p.jar works)"
    echo "If you do so, this script will run two tests to compare your installed jbigi with the one here you just compiled (to see if there is a marked improvement)"
	exit 1
fi
echo 'Running test with standard I2P installation...'
java -cp $I2P/lib/i2p.jar:$I2P/lib/jbigi.jar net.i2p.util.NativeBigInteger
echo
echo 'Running test with new libjbigi...'
java -Djava.library.path=lib/ -cp $I2P/lib/i2p.jar:$I2P/lib/jbigi.jar net.i2p.util.NativeBigInteger
echo 'If the second is better performance, please use the jbigi you have compiled i2p will work better!'
echo '(You can do that just by copying lib/libjbigi.so over the existing libjbigi.so file in $I2P)'
