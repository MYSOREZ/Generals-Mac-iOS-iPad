#!/bin/bash
# Stage the fonts the iOS app bundles. The game asks for Windows font names
# (arial.ttf etc.); we use the metric-compatible Liberation fonts (SIL OFL)
# renamed accordingly. Pinned release for reproducibility.
set -euo pipefail

LIB_VERSION="2.1.5"
DEST="${GX_FONTS:-${HOME}/GeneralsX/ios-staging/fonts}"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

mkdir -p "${DEST}"
if [[ -f "${DEST}/arial.ttf" && -f "${DEST}/couriernew.ttf" && -f "${DEST}/timesnewroman.ttf" ]]; then
    echo "Fonts already staged at ${DEST}"
    exit 0
fi
echo "==> Downloading Liberation fonts ${LIB_VERSION}"
curl -fL -o "${TMP}/liberation.tar.gz" \
    "https://github.com/liberationfonts/liberation-fonts/files/7261482/liberation-fonts-ttf-${LIB_VERSION}.tar.gz" ||
curl -fL -o "${TMP}/liberation.tar.gz" \
    "https://github.com/liberationfonts/liberation-fonts/releases/download/${LIB_VERSION}/liberation-fonts-ttf-${LIB_VERSION}.tar.gz"
tar -xzf "${TMP}/liberation.tar.gz" -C "${TMP}"
SRC="$(find "${TMP}" -name "LiberationSans-Regular.ttf" -exec dirname {} \; | head -1)"
[[ -n "${SRC}" ]] || { echo "ERROR: Liberation fonts not found in archive"; exit 1; }
cp "${SRC}/LiberationSans-Regular.ttf"   "${DEST}/arial.ttf"
cp "${SRC}/LiberationSans-Bold.ttf"      "${DEST}/arialbold.ttf"
cp "${SRC}/LiberationMono-Regular.ttf"   "${DEST}/couriernew.ttf"
cp "${SRC}/LiberationSerif-Regular.ttf"  "${DEST}/timesnewroman.ttf"
echo "==> Staged $(ls "${DEST}" | wc -l | tr -d ' ') fonts at ${DEST}"
