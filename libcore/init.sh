#!/bin/bash

chmod -R u+rwX .build 2>/dev/null
rm -rf .build 2>/dev/null

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi

# Install the pinned gomobile fork, rebuilding it after a Go toolchain upgrade.
CURRENT_GO_VERSION=$(go version | awk '{print $3}')
GOMOBILE_GO_VERSION=""
if [ -x "$GOPATH/bin/gomobile-matsuri" ]; then
    GOMOBILE_GO_VERSION=$(go version -m "$GOPATH/bin/gomobile-matsuri" | head -n 1 | awk '{print $2}')
fi
if [ "$GOMOBILE_GO_VERSION" != "$CURRENT_GO_VERSION" ] || [ ! -x "$GOPATH/bin/gobind-matsuri" ]; then
    rm -rf .build/gomobile-tools
    git clone https://github.com/MatsuriDayo/gomobile.git .build/gomobile-tools
    pushd .build/gomobile-tools
	git checkout --detach 17d6af34f6bd6d7e1e428e0c652c8b54a46bda4f
    pushd cmd
    pushd gomobile
    go install -v
    popd
    pushd gobind
    go install -v
    popd
    popd
    mv "$GOPATH/bin/gomobile" "$GOPATH/bin/gomobile-matsuri"
    mv "$GOPATH/bin/gobind" "$GOPATH/bin/gobind-matsuri"
    popd
    rm -rf .build/gomobile-tools
fi

# The pinned forked gobind is installed above. `gomobile init` attempts to fetch
# upstream gobind@latest, which is both non-reproducible and unnecessary for bind.
test -x "$GOPATH/bin/gomobile-matsuri"
test -x "$GOPATH/bin/gobind-matsuri"
