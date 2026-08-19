#!/usr/bin/env bash
set -e

NDK_VERSION="29.0.13113456"
REQUIRED_JDK_MAJOR="21"

declare -A ABI_TRIPLE_MAP=(
    ["arm64-v8a"]="aarch64-linux-android"
    ["armeabi-v7a"]="armv7-linux-androideabi"
)

PLATFORM_VERSION=21
PROJECT_ROOT=$(pwd)
LIBRESPOT_DIR="$PROJECT_ROOT/rust/librespot-ffi"
OUTPUT_DIR="$PROJECT_ROOT/app/src/main/jniLibs"

_java_major() {
    "$1" -version 2>&1 \
        | awk -F[\"\.] '/version/ { print ($2 == "1" ? $3 : $2); exit }'
}

find_jdk21() {
		# Already used
    if command -v java &>/dev/null; then
        if [ "$(_java_major java)" = "$REQUIRED_JDK_MAJOR" ]; then
            echo ""
            return 0
        fi
    fi

    # update-java-alternatives (Debian/Ubuntu)
    if command -v update-java-alternatives &>/dev/null; then
        local candidate
        candidate=$(update-java-alternatives -l 2>/dev/null \
            | awk '$1 ~ /-21(-|$)/ || $2 == 21 { print $3; exit }')
        if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
            if [ "$(_java_major "$candidate/bin/java")" = "$REQUIRED_JDK_MAJOR" ]; then
                echo "$candidate"
                return 0
            fi
        fi
    fi

    # 3. update-alternatives
    if command -v update-alternatives &>/dev/null; then
        local candidate
        candidate=$(update-alternatives --list java 2>/dev/null \
            | grep -- "-21" | head -n1 | sed 's|/bin/java||')
        if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
            if [ "$(_java_major "$candidate/bin/java")" = "$REQUIRED_JDK_MAJOR" ]; then
                echo "$candidate"
                return 0
            fi
        fi
    fi

    # 4. scan /usr/lib/jvm for java-21 / temurin-21 / zulu-21 / etc.
    local jvm_dir
    for jvm_dir in /usr/lib/jvm/java-21* /usr/lib/jvm/temurin-21* \
                   /usr/lib/jvm/zulu-21*  /usr/lib/jvm/jdk-21*; do
        if [ -x "$jvm_dir/bin/java" ]; then
            if [ "$(_java_major "$jvm_dir/bin/java")" = "$REQUIRED_JDK_MAJOR" ]; then
                echo "$jvm_dir"
                return 0
            fi
        fi
    done

    return 1
}

JDK21_HOME=$(find_jdk21) || {
    echo "ERROR: JDK $REQUIRED_JDK_MAJOR not found."
    echo "       Install with: sudo apt install openjdk-21-jdk"
    echo "       Or set JAVA_HOME manually before running this script."
    exit 1
}

if [ -n "$JDK21_HOME" ]; then
    export JAVA_HOME="$JDK21_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "JDK: switched to $JAVA_HOME"
else
    echo "JDK: already on PATH ($(java -version 2>&1 | head -n1))"
fi

# Validating rust-toolchain.toml
TOOLCHAIN_TOML="$PROJECT_ROOT/rust/rust-toolchain.toml"
if [ ! -f "$TOOLCHAIN_TOML" ]; then
    echo "ERROR: rust-toolchain.toml not found – Rust toolchain must be pinned"
    echo "       for reproducible builds. See CONTRIBUTING.md."
    exit 1
fi
echo "Rust toolchain (rust-toolchain.toml):"
rustup show active-toolchain

# Resolving Android NDK
if [ -z "$ANDROID_SDK_ROOT" ]; then
    echo "ERROR: ANDROID_SDK_ROOT is not set"
    exit 1
fi

if [ -z "$ANDROID_NDK" ]; then
    ANDROID_NDK="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
fi

if [ ! -d "$ANDROID_NDK" ]; then
    echo "ERROR: NDK $NDK_VERSION not found at $ANDROID_NDK"
    echo "       Install with: sdkmanager \"ndk;$NDK_VERSION\""
    exit 1
fi

# Toolchain env
TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
export PATH="$TOOLCHAIN:$PATH"

export CC_aarch64_linux_android="$TOOLCHAIN/aarch64-linux-android${PLATFORM_VERSION}-clang"
export CC_armv7_linux_androideabi="$TOOLCHAIN/armv7a-linux-androideabi${PLATFORM_VERSION}-clang"
export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
export AR_armv7_linux_androideabi="$TOOLCHAIN/llvm-ar"

export RUSTFLAGS="\
	--remap-path-prefix $PROJECT_ROOT=/build/outify \
	--remap-path-prefix $HOME/.cargo=/cargo"

echo "Using JDK	: $JDK21_HOME"
echo "Using ANDROID_NDK : $ANDROID_NDK  (NDK $NDK_VERSION)"
echo "Using CC (arm64)  : $CC_aarch64_linux_android"
echo "Using CC (armv7)  : $CC_armv7_linux_androideabi"

# Building ABIs
for ABI in "${!ABI_TRIPLE_MAP[@]}"; do
    TRIPLE=${ABI_TRIPLE_MAP[$ABI]}
    echo ""
    echo "▶ Building librespot for $ABI ($TRIPLE) ..."
    cd "$LIBRESPOT_DIR"
    cargo ndk \
        -t "$ABI" \
        --platform "$PLATFORM_VERSION" \
        build --release
    cd "$PROJECT_ROOT"
    mkdir -p "$OUTPUT_DIR/$ABI"
    cp "$LIBRESPOT_DIR/../target/$TRIPLE/release/liblibrespot_ffi.so" \
       "$OUTPUT_DIR/$ABI/"
    echo "✔ $ABI → $OUTPUT_DIR/$ABI/liblibrespot_ffi.so"
done
