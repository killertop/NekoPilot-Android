#!/bin/bash

buildScript/init/action/gradle.sh

# The official libbox AAR is the only supported runtime. CI must provide the
# pinned AAR (or build it with scripts/build-official-libbox.sh) before Gradle.
