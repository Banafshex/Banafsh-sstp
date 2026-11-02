#!/usr/bin/env bash
# Downloads OpenSSL (the tag that matches the prebuilt libssl.a / libcrypto.a in src/main/jniLibs)
# and generates its headers. Only the headers are used: the libraries are prebuilt.
# Gradle runs this automatically before the native build (task fetchOpenSslHeaders).
set -euo pipefail

OPENSSL_TAG="${OPENSSL_TAG:-openssl-3.6.5}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/src/main/cpp/openssl"

if [ -f "$DIR/include/openssl/configuration.h" ] && [ -f "$DIR/include/openssl/evp.h" ]; then
    echo "OpenSSL headers are already there: $DIR/include/openssl"
    exit 0
fi

rm -rf "$DIR"
git clone --depth 1 --branch "$OPENSSL_TAG" https://github.com/openssl/openssl.git "$DIR"
cd "$DIR"

# Same header configuration as build-openssl-android.sh (generate_headers).
./Configure gcc -DOPENSSL_NO_ASM --prefix=/tmp/openssl-headers
make build_generated

test -f include/openssl/configuration.h
test -f include/openssl/evp.h
echo "OpenSSL headers ready: $DIR/include/openssl"
