#!/usr/bin/env bash
# Release Trefoil: bump version, gate, verify signature, commit, tag, push, gh release.
# Usage: scripts/release.sh [--check] [--notes FILE] <version>   (e.g. 0.15.2)
#   --check  dry run: bump + gate + signature check, then revert; no commit/tag/push/publish.
# Write the docs/RELEASING.md paragraph and the notes file BEFORE running (tree must be clean).
set -euo pipefail
export ADB_MDNS=0 ADB_MDNS_OPENSCREEN=0

die() { echo "release.sh: $*" >&2; exit 1; }

CHECK=0; NOTES=""; VERSION=""
while [ $# -gt 0 ]; do
  case "$1" in
    --check) CHECK=1 ;;
    --notes)
      [ $# -ge 2 ] && [ -n "$2" ] || die "--notes requires a FILE argument"
      shift; NOTES="$1" ;;
    -*) die "unknown option $1" ;;
    *) VERSION="$1" ;;
  esac
  shift
done
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "usage: scripts/release.sh [--check] [--notes FILE] <x.y.z>"

# Resolve the notes file now: the script changes directory later.
if [ -n "$NOTES" ] && [ -f "$NOTES" ]; then
  NOTES="$(cd "$(dirname "$NOTES")" && pwd)/$(basename "$NOTES")"
fi

ROOT="$(git rev-parse --show-toplevel)"
ANDROID="$ROOT/android"
GRADLE_FILE="$ANDROID/app/build.gradle.kts"
TAG="v$VERSION"
REPO="tajchert/trefoil"

# Preconditions
[ -f "$ANDROID/keystore.properties" ] || die "android/keystore.properties is missing (copy it from the main checkout; see .worktreeinclude)"
if [ -z "${ANDROID_HOME:-}" ] && [ -f "$ANDROID/local.properties" ]; then
  ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$ANDROID/local.properties")"
fi
ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
[ -d "$ANDROID_HOME" ] || die "Android SDK not found at $ANDROID_HOME (set ANDROID_HOME or android/local.properties sdk.dir)"
export ANDROID_HOME
APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1 || true)"
[ -x "$APKSIGNER" ] || die "apksigner not found under $ANDROID_HOME/build-tools"
if [ "$CHECK" = 0 ]; then
  command -v gh >/dev/null || die "gh CLI is required"
  [ -n "$NOTES" ] && [ -f "$NOTES" ] || die "--notes FILE is required for a real release"
  [ "$(git rev-parse --abbrev-ref HEAD)" = main ] || die "real releases run from branch main"
fi
[ -z "$(git status --porcelain --untracked-files=no)" ] || die "working tree has tracked changes; commit or stash them first"

# Remote state: a real release must start from exactly origin/main with a free tag.
# A dry run only warns when the remote is unreachable and may run on any branch.
if git fetch origin main --tags; then
  if [ "$CHECK" = 0 ]; then
    [ "$(git rev-parse HEAD)" = "$(git rev-parse refs/remotes/origin/main)" ] || die "HEAD is not origin/main; pull or push first"
    git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1 && die "tag $TAG already exists on origin"
  fi
elif [ "$CHECK" = 0 ]; then
  die "git fetch origin failed; cannot verify the remote state"
else
  echo "warning: git fetch origin failed; remote checks skipped in --check mode" >&2
fi
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "tag $TAG already exists"

CUR_CODE="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$GRADLE_FILE")"
CUR_NAME="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$GRADLE_FILE")"
[ -n "$CUR_CODE" ] && [ -n "$CUR_NAME" ] || die "cannot parse version from $GRADLE_FILE"
[[ "$CUR_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "current versionName '$CUR_NAME' is not x.y.z"
# The new version must be strictly greater than the current one (numeric, per component).
IFS=. read -r NEW_MAJOR NEW_MINOR NEW_PATCH <<<"$VERSION"
IFS=. read -r CUR_MAJOR CUR_MINOR CUR_PATCH <<<"$CUR_NAME"
version_newer() {
  [ "$((10#$NEW_MAJOR))" -ne "$((10#$CUR_MAJOR))" ] && { [ "$((10#$NEW_MAJOR))" -gt "$((10#$CUR_MAJOR))" ]; return; }
  [ "$((10#$NEW_MINOR))" -ne "$((10#$CUR_MINOR))" ] && { [ "$((10#$NEW_MINOR))" -gt "$((10#$CUR_MINOR))" ]; return; }
  [ "$((10#$NEW_PATCH))" -gt "$((10#$CUR_PATCH))" ]
}
version_newer || die "version $VERSION must be greater than the current $CUR_NAME"
NEW_CODE=$((CUR_CODE + 1))
echo "Version $CUR_NAME ($CUR_CODE) -> $VERSION ($NEW_CODE)"

# Revert the bump on any exit (dry run, failed gate, Ctrl-C) until the release commit exists.
COMMITTED=0
revert() {
  [ "$COMMITTED" = 0 ] || return 0
  # From HEAD, not the index: a failed `git commit` leaves the bump staged.
  git -C "$ROOT" checkout HEAD -- android/app/build.gradle.kts 2>/dev/null || true
}
trap revert EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Version bump
sed -i.bak "s/versionCode = $CUR_CODE;/versionCode = $NEW_CODE;/; s/versionName = \"$CUR_NAME\"/versionName = \"$VERSION\"/" "$GRADLE_FILE"
rm -f "$GRADLE_FILE.bak"
grep -q "versionName = \"$VERSION\"" "$GRADLE_FILE" || die "version bump failed: versionName"
grep -q "versionCode = $NEW_CODE;" "$GRADLE_FILE" || die "version bump failed: versionCode"

# Gate (run from android/)
cd "$ANDROID"
./gradlew :core:model:test :core:ssh:test :core:protocol:test :core:session:test :app:testDebugUnitTest :app:lintRelease :app:assembleRelease

# Signature check
APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || die "release APK missing: android/$APK (is keystore.properties valid?)"
CERTS="$("$APKSIGNER" verify --verbose --print-certs "$APK")"
if grep -qi 'Android Debug' <<<"$CERTS"; then die "APK is signed with the debug certificate"; fi
grep -q 'Verified using v[0-9]* scheme.*: true' <<<"$CERTS" || die "apksigner did not verify the APK"
SIGNER="$(grep -m1 'certificate SHA-256 digest' <<<"$CERTS" || true)"
echo "Signature OK: $SIGNER"

if [ "$CHECK" = 1 ]; then
  echo "--check passed: nothing committed, tagged, pushed or published."
  exit 0
fi

# Commit, tag, push, publish
cd "$ROOT"
OUT="$ANDROID/app/build/outputs/apk/release"
cp "$OUT/app-release.apk" "$OUT/trefoil-$VERSION.apk"
(cd "$OUT" && shasum -a 256 "trefoil-$VERSION.apk" > "trefoil-$VERSION.apk.sha256")
git add android/app/build.gradle.kts
git commit -m "chore(release): $VERSION" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
COMMITTED=1
git tag "$TAG"
git push --atomic origin main "$TAG"
RELEASE_CMD=(gh release create "$TAG" "$OUT/trefoil-$VERSION.apk" "$OUT/trefoil-$VERSION.apk.sha256"
  --repo "$REPO" --verify-tag --title "Trefoil $VERSION" --notes-file "$NOTES")
if ! "${RELEASE_CMD[@]}"; then
  echo "release.sh: main and $TAG are pushed but the GitHub release failed. Re-run:" >&2
  printf ' %q' "${RELEASE_CMD[@]}" >&2; echo >&2
  exit 1
fi
echo "Released $TAG"
