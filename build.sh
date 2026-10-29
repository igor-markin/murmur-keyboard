#!/usr/bin/env bash
# ==============================================================================
# Murmur - Build & Patch Automation Script (fork of PixelBoard)
# Maintainer: Akshay Kadam (@Akshayykadam)
# Repository: https://github.com/Akshayykadam/PixelBoard
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

REBUILD_PATCH=true
INPUT_APK="input/gboard.apk"
for arg in "$@"; do
    case "$arg" in
        -r|--rebuild|--compile|-c)
            REBUILD_PATCH=true
            ;;
        -h|--help)
            echo "Usage: ./build.sh [options] [input_apk]"
            echo "Options:"
            echo "  -r, --rebuild    Accepted for compatibility; patches are always built from source"
            echo "  -h, --help       Show this help message"
            echo ""
            echo "Signing key: \$MURMUR_KEYSTORE (default ~/.murmur/murmur.keystore), password from \$MURMUR_KEYSTORE_PASSWORD or prompt."
            exit 0
            ;;
        *)
            if [[ -f "$arg" ]]; then
                INPUT_APK="$arg"
            fi
            ;;
    esac
done

# Gradle needs JAVA_HOME / ANDROID_HOME; fall back to the Homebrew JDK 21 and the
# default macOS SDK location so a fresh terminal works without shell profile edits.
if [[ -z "${JAVA_HOME:-}" ]] && command -v brew >/dev/null 2>&1; then
    BREW_JDK="$(brew --prefix openjdk@21 2>/dev/null)/libexec/openjdk.jdk/Contents/Home"
    if [[ -x "$BREW_JDK/bin/java" ]]; then
        export JAVA_HOME="$BREW_JDK"
    fi
fi
if [[ -z "${ANDROID_HOME:-}" ]] && [[ -d "$HOME/Library/Android/sdk" ]]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
# Keystore password can come from the macOS keychain (see MURMUR.md).
if [[ -z "${MURMUR_KEYSTORE_PASSWORD:-}" ]] && command -v security >/dev/null 2>&1; then
    MURMUR_KEYSTORE_PASSWORD="$(security find-generic-password -s murmur-keystore -w 2>/dev/null || true)"
fi

PATCH_VERSION=$(grep -E "^version\s*=" pixelboard-patches/gradle.properties | cut -d'=' -f2 | tr -d ' ')
MPP_PATH="patches/PixelBoard.mpp"

if [[ "$REBUILD_PATCH" == true ]] || [[ ! -f "$MPP_PATH" ]]; then
    echo "=== 🎹 Compiling Murmur Patch Bundle from Source ==="
    cd pixelboard-patches
    ./gradlew :patches:buildAndroid
    cd "$SCRIPT_DIR"

    COMPILED_MPP="pixelboard-patches/patches/build/libs/patches-${PATCH_VERSION}.mpp"
    if [[ ! -f "$COMPILED_MPP" ]]; then
        COMPILED_MPP=$(ls pixelboard-patches/patches/build/libs/patches-*.mpp 2>/dev/null | grep -v 'javadoc' | grep -v 'sources' | head -n 1)
    fi
    if [[ -z "${COMPILED_MPP:-}" ]] || [[ ! -f "$COMPILED_MPP" ]]; then
        echo "❌ Error: Patch bundle failed to build at pixelboard-patches/patches/build/libs/"
        exit 1
    fi
    mkdir -p patches
    cp "$COMPILED_MPP" "$MPP_PATH"
    if [[ -n "${PATCH_VERSION:-}" ]]; then
        cp "$COMPILED_MPP" "patches/patches-${PATCH_VERSION}.mpp"
    fi
    echo "✅ Patch bundle compiled & synced to: $MPP_PATH"
else
    echo "📦 Using local patch bundle: $MPP_PATH (100% offline)"
fi

# Check for Java 21 to run patcher.jar
JAVA_BIN=""
for candidate in \
    "${JAVA_HOME:-}/bin/java" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" \
    "/opt/homebrew/opt/openjdk@21/bin/java" \
    /opt/homebrew/Cellar/openjdk@21/*/libexec/openjdk.jdk/Contents/Home/bin/java \
    "/opt/homebrew/opt/openjdk/bin/java" \
    "$(/usr/libexec/java_home -v 21 2>/dev/null)/bin/java" \
    "$(which java 2>/dev/null)"; do
    if [[ -x "$candidate" ]]; then
        candidate_ver=$("$candidate" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
        if [[ "$candidate_ver" -ge 21 ]]; then
            JAVA_BIN="$candidate"
            break
        fi
    fi
done

if [[ -z "$JAVA_BIN" ]]; then
    echo "⚠️ Note: patcher.jar requires Java 21+."
    echo "   Please install Java 21 (e.g. 'brew install openjdk@21') or specify JAVA_HOME."
    exit 1
fi
echo "☕ Using Java 21+: $JAVA_BIN"

if [[ ! -f "$INPUT_APK" ]]; then
    echo "⚠️ Note: Place stock Gboard APK at $INPUT_APK to generate output APK."
    exit 0
fi

echo "=== 🔨 Applying Patches to $INPUT_APK ==="
mkdir -p output
mkdir -p tools/patcher-data

# Signing key lives outside the repo and is never committed. Losing it means
# reinstalling the app (Android only accepts updates signed with the same key).
KEYSTORE_PATH="${MURMUR_KEYSTORE:-$HOME/.murmur/murmur.keystore}"
KEY_ALIAS="murmur"
KEYTOOL_BIN="$(dirname "$JAVA_BIN")/keytool"
if [[ -z "${MURMUR_KEYSTORE_PASSWORD:-}" ]]; then
    read -r -s -p "🔑 Keystore password: " MURMUR_KEYSTORE_PASSWORD
    echo
fi
if [[ ${#MURMUR_KEYSTORE_PASSWORD} -lt 12 ]]; then
    echo "❌ Keystore password must be at least 12 characters."
    exit 1
fi
if [[ ! -f "$KEYSTORE_PATH" ]]; then
    echo "🔑 Generating Murmur signing key at $KEYSTORE_PATH..."
    mkdir -p "$(dirname "$KEYSTORE_PATH")"
    chmod 700 "$(dirname "$KEYSTORE_PATH")"
    "$KEYTOOL_BIN" -genkeypair \
        -keystore "$KEYSTORE_PATH" \
        -storetype PKCS12 \
        -alias "$KEY_ALIAS" \
        -keyalg RSA \
        -keysize 4096 \
        -validity 10000 \
        -storepass:env MURMUR_KEYSTORE_PASSWORD \
        -dname "CN=Murmur" \
        >/dev/null
    chmod 600 "$KEYSTORE_PATH"
fi
export MURMUR_KEYSTORE_PASSWORD

"$JAVA_BIN" -jar tools/patcher.jar patch \
    --patches="$MPP_PATH" \
    --options-file=options.json \
    --striplibs=arm64-v8a \
    --signer="Murmur" \
    --keystore="$KEYSTORE_PATH" \
    --keystore-password="$MURMUR_KEYSTORE_PASSWORD" \
    --keystore-entry-alias="$KEY_ALIAS" \
    --keystore-entry-password="$MURMUR_KEYSTORE_PASSWORD" \
    --temporary-files-path="tools/patcher-data/tmp" \
    -o=output/Murmur.apk \
    -r=output/patching-result.json \
    "$INPUT_APK"

if [[ -f "output/patching-result.json" ]]; then
    GBOARD_VER=$(grep -o '"packageVersion":"[^"]*"' output/patching-result.json | cut -d'"' -f4 | cut -d'-' -f1)
    GBOARD_SHORT_VER=$(echo "$GBOARD_VER" | cut -d'.' -f1-3)
    if [[ -n "$GBOARD_VER" ]]; then
        cp output/Murmur.apk "output/Murmur-${GBOARD_VER}.apk"
        echo "📦 Versioned APK saved to: output/Murmur-${GBOARD_VER}.apk"
    fi
    if [[ -n "$GBOARD_SHORT_VER" ]] && [[ "$GBOARD_SHORT_VER" != "$GBOARD_VER" ]]; then
        cp output/Murmur.apk "output/Murmur-${GBOARD_SHORT_VER}.apk"
        echo "📦 Short-versioned APK saved to: output/Murmur-${GBOARD_SHORT_VER}.apk"
    fi
fi
if [[ -n "${PATCH_VERSION:-}" ]]; then
    cp output/Murmur.apk "output/Murmur-${PATCH_VERSION}.apk"
fi

echo "🎉 Successfully built output/Murmur.apk!"
