#!/usr/bin/env bash
# Publica uma versão nova da GoatTV: as TVs recebem-na em Definições → Atualizar.
#   scripts/release.sh 0.3.0 "O que mudou (aparece nas Definições)"
# Precisa de: keystore/itv-release.jks + keystore.properties (a mesma chave de sempre) e o `gh` com acesso aos dois repositórios.
set -euo pipefail

VERSION="${1:?Uso: scripts/release.sh <versão> [notas]}"
NOTES="${2:-}"
# As versões são releases do próprio repositório do código (é aí que a app procura atualizações).
RELEASES_REPO="GOAThings/GoatTV"
cd "$(dirname "$0")/.."

if [ -n "$(git status --porcelain)" ]; then
    echo "Há alterações por gravar: faz commit primeiro." >&2
    exit 1
fi
[ -f keystore.properties ] || { echo "Falta o keystore.properties (a chave de assinatura)." >&2; exit 1; }

# versionCode sobe sempre 1; versionName é o que se pediu.
code=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)
sed -i "s/versionCode = $code/versionCode = $((code + 1))/; s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/" app/build.gradle.kts

./gradlew -q :app:testDebugUnitTest :app:assembleRelease
python3 -I scripts/check_access.py app/build/outputs/apk/release/app-release.apk

apk="build/goattv-$VERSION.apk"
mkdir -p build
cp app/build/outputs/apk/release/app-release.apk "$apk"
# Cópia com nome fixo: https://github.com/$RELEASES_REPO/releases/latest/download/goattv.apk dá sempre
# a versão mais recente (é para aí que aponta o código do Downloader).
latest="build/goattv.apk"
cp "$apk" "$latest"

git commit -qam "Versão $VERSION"
git tag -a "v$VERSION" -m "Versão $VERSION"  # anotada: o --follow-tags só envia tags anotadas
git push -q --follow-tags

gh release create "v$VERSION" "$apk" "$latest" --repo "$RELEASES_REPO" --title "GoatTV $VERSION" --notes "${NOTES:-Versão $VERSION}"
echo "Publicada a $VERSION: as TVs veem-na em Definições → Atualizar (ou sozinhas, até 12 h depois de abrir)."
