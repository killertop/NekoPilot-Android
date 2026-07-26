#!/bin/bash

if [ -n "${ZSH_VERSION:-}" ]; then
  _env_script_path="${(%):-%N}"
else
  _env_script_path=${BASH_SOURCE[0]:-$0}
fi
_env_init_dir=$(cd -P "$(dirname "$_env_script_path")" && pwd) || {
  echo "Error: unable to resolve env.sh location." >&2
  return 1 2>/dev/null || exit 1
}
source "$_env_init_dir/env_ndk.sh" || return 1 2>/dev/null || exit 1

if [[ "$OSTYPE" =~ ^darwin ]]; then
  export SRC_ROOT=$PWD
else
  export SRC_ROOT=$(realpath .)
fi

DEPS=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin

export ANDROID_ARM_CC=$DEPS/armv7a-linux-androideabi21-clang
export ANDROID_ARM_CXX=$DEPS/armv7a-linux-androideabi21-clang++
export ANDROID_ARM_CC_21=$DEPS/armv7a-linux-androideabi21-clang
export ANDROID_ARM_CXX_21=$DEPS/armv7a-linux-androideabi21-clang++
export ANDROID_ARM_STRIP=$DEPS/arm-linux-androideabi-strip

export ANDROID_ARM64_CC=$DEPS/aarch64-linux-android21-clang
export ANDROID_ARM64_CXX=$DEPS/aarch64-linux-android21-clang++
export ANDROID_ARM64_STRIP=$DEPS/aarch64-linux-android-strip

export ANDROID_X86_CC=$DEPS/i686-linux-android21-clang
export ANDROID_X86_CXX=$DEPS/i686-linux-android21-clang++
export ANDROID_X86_CC_21=$DEPS/i686-linux-android21-clang
export ANDROID_X86_CXX_21=$DEPS/i686-linux-android21-clang++
export ANDROID_X86_STRIP=$DEPS/i686-linux-android-strip

export ANDROID_X86_64_CC=$DEPS/x86_64-linux-android21-clang
export ANDROID_X86_64_CXX=$DEPS/x86_64-linux-android21-clang++
export ANDROID_X86_64_STRIP=$DEPS/x86_64-linux-android-strip
