#!/usr/bin/env bash
# Builds the three pinned source archives consumed through build.zig.zon and
# publishes them as one GitHub release:
#
#   artzig-aosp-<release>.tar.gz         artzig/build.zig.zon  .aosp
#   androlinzberg-aosp-<release>.tar.gz  build.zig.zon         .aosp
#   androlinzberg-webview-<ver>.tar.gz   build.zig.zon         .webview
#
# The release tag is derived from the packagers' inputs (file manifests,
# packager scripts and patches), so an unchanged input set maps to the same
# tag and the job skips work that is already published. Print it with
#
#   tools/aosp-release.sh --print-tag
#
# The tag is created on Forgejo and reaches GitHub through the push mirror.
# Creating it on GitHub directly would not survive: a push mirror is a
# `git push --mirror`, which deletes remote refs that Forgejo lacks.
#
# Run by .forgejo/workflows/aosp-tarballs.yml. Requires git, repo, python3,
# zig, curl, jq and flock, plus:
#
#   GH_TOKEN      GitHub token with contents:write on GH_REPO
#   GH_REPO       owner/name of the public GitHub mirror
#   AOSP_CACHE    persistent directory for the Repo checkout (default
#                 $HOME/aosp-cache); the first sync is the slow part
#   FORGEJO_URL, FORGEJO_REPO, FORGEJO_TOKEN
#                 optional; used to ask Forgejo to sync the push mirror
#                 right after the tag is pushed
#   FORCE=1       rebuild and replace an existing release for the tag

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

release=android-16.0.0_r1
webview_py=aosp/package-webview.py

# Everything the packagers read from this repository.
tag_inputs=(
	aosp/aosp-projects.txt
	aosp/artzig-files.txt
	aosp/androlinzberg-files.txt
	aosp/package-artzig-aosp.py
	aosp/package-androlinzberg-aosp.py
	"$webview_py"
	artzig/patches
)

release_tag() {
	local key
	key=$(git ls-tree -r HEAD -- "${tag_inputs[@]}" | sha256sum | cut -c1-12)
	echo "aosp-${release#android-}-$key"
}

if [[ ${1:-} == --print-tag ]]; then
	release_tag
	exit 0
fi

: "${GH_TOKEN:?GH_TOKEN is required}"
: "${GH_REPO:?GH_REPO is required}"

tag=$(release_tag)
cache=${AOSP_CACHE:-$HOME/aosp-cache}
aosp_root=$cache/aosp
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
out=$work/out
mkdir -p "$out" "$cache"

log() { printf '\n==> %s\n' "$*"; }

github() {
	local method=$1 path=$2
	shift 2
	curl -fsSL -X "$method" \
		-H "Authorization: Bearer $GH_TOKEN" \
		-H "Accept: application/vnd.github+json" \
		-H "X-GitHub-Api-Version: 2022-11-28" \
		"$@" "https://api.github.com/repos/$GH_REPO$path"
}

webview_const() {
	sed -n "s/^$1 = \"\(.*\)\"$/\1/p" "$webview_py"
}

webview_version=$(webview_const VERSION)
webview_revision=$(webview_const REVISION)
webview_path="$webview_version/x86_64/webview.apk"
assets=(
	"artzig-aosp-$release.tar.gz"
	"androlinzberg-aosp-$release.tar.gz"
	"androlinzberg-webview-$webview_version.tar.gz"
)

log "release tag $tag"

existing=$(github GET "/releases/tags/$tag" 2>/dev/null || true)
if [[ -n $existing ]]; then
	complete=$(jq --argjson want "$(printf '%s\n' "${assets[@]}" | jq -R . | jq -s .)" \
		'(.draft | not) and ([.assets[].name] | contains($want))' <<<"$existing")
	if [[ $complete == true && ${FORCE:-0} != 1 ]]; then
		echo "$(jq -r .html_url <<<"$existing") is already published"
		exit 0
	fi
	log "replacing incomplete or forced release $(jq -r .id <<<"$existing")"
	github DELETE "/releases/$(jq -r .id <<<"$existing")" >/dev/null
fi

# One packaging run at a time per cache: the packagers patch the shared
# checkout in place.
exec 9>"$cache/.lock"
flock 9

log "syncing the AOSP checkout in $aosp_root"
mapfile -t projects < <(grep -v -e '^#' -e '^$' aosp/aosp-projects.txt)
mkdir -p "$aosp_root"
(
	cd "$aosp_root"
	repo init \
		-u https://android.googlesource.com/platform/manifest \
		-b "$release" \
		--depth=1 \
		--partial-clone \
		--clone-filter=blob:none
	# An interrupted earlier run can leave patches applied or patch-created
	# files behind. A project whose checkout failed may not reset; the sync
	# below repairs it, and the packagers still refuse a dirty checkout.
	if [[ -f .repo/project.list ]]; then
		repo forall -c 'git reset -q --hard HEAD && git clean -fdq' || true
	fi
	# googlesource throttles heavy anonymous traffic, and repo resumes where
	# it stopped. The last attempt runs serially and verbosely so a lasting
	# failure shows git's own error instead of repo's summary.
	sync() { repo sync -c --force-sync "$@" "${projects[@]}"; }
	sync -j"${REPO_SYNC_JOBS:-8}" ||
		sync -j"${REPO_SYNC_JOBS:-8}" ||
		sync -j1 --fail-fast --verbose
	rm -f ./*-aosp-*.tar.gz
)

log "fetching WebView $webview_version from chromium-webview@$webview_revision"
webview_git=$cache/chromium-webview
if [[ ! -d $webview_git ]]; then
	git init -q --bare "$webview_git"
	git -C "$webview_git" remote add origin \
		https://android.googlesource.com/platform/external/chromium-webview
fi
git -C "$webview_git" fetch -q --depth=1 --filter=blob:none origin "$webview_revision"
git -C "$webview_git" cat-file blob "$webview_revision:$webview_path" >"$work/webview.apk"

log "packaging"
export ARTZIG_AOSP_ROOT=$aosp_root
python3 aosp/package-artzig-aosp.py
python3 aosp/package-androlinzberg-aosp.py
mv "$aosp_root/artzig-aosp-$release.tar.gz" "$aosp_root/androlinzberg-aosp-$release.tar.gz" "$out/"
python3 "$webview_py" --apk "$work/webview.apk" --output "$out/${assets[2]}"
rm -f "$work/webview.apk"

log "checking archives"
pinned_hash() {
	# pinned_hash <zon file> <dependency name>
	awk -v dep="$2" '
		index($0, "." dep " = .{") { inside = 1 }
		inside && /\.hash/ { match($0, /"[^"]+"/); print substr($0, RSTART + 1, RLENGTH - 2); exit }
	' "$1"
}
declare -A zon_file=(
	["${assets[0]}"]=artzig/build.zig.zon
	["${assets[1]}"]=build.zig.zon
	["${assets[2]}"]=build.zig.zon
)
declare -A zon_dep=(
	["${assets[0]}"]=aosp
	["${assets[1]}"]=aosp
	["${assets[2]}"]=webview
)
declare -A zig_hash
mkdir -p "$work/zig"
for asset in "${assets[@]}"; do
	size=$(stat -c %s "$out/$asset")
	if ((size >= 2 * 1024 * 1024 * 1024)); then
		echo "error: $asset is $size bytes; GitHub release assets must be under 2 GiB" >&2
		exit 1
	fi
	# A throwaway cache keeps the runner's Zig cache out of it.
	zig_hash[$asset]=$(cd "$work" && ZIG_GLOBAL_CACHE_DIR="$work/zig" zig fetch "$out/$asset")
	pinned=$(pinned_hash "${zon_file[$asset]}" "${zon_dep[$asset]}")
	if [[ ${zig_hash[$asset]} == "$pinned" ]]; then
		match="matches ${zon_file[$asset]}"
	else
		match="differs from ${zon_file[$asset]} ($pinned)"
	fi
	printf '%s  %s bytes  %s  %s\n' "$asset" "$size" "${zig_hash[$asset]}" "$match"
done
rm -rf "$work/zig"
(cd "$out" && sha256sum "${assets[@]}" >SHA256SUMS)
cat "$out/SHA256SUMS"

log "tagging $(git rev-parse HEAD) as $tag on Forgejo"
if [[ -z $(git ls-remote --tags origin "refs/tags/$tag") ]]; then
	git tag "$tag" HEAD
	git push origin "refs/tags/$tag"
fi
if [[ -n ${FORGEJO_TOKEN:-} && -n ${FORGEJO_URL:-} && -n ${FORGEJO_REPO:-} ]]; then
	curl -fsS -X POST -H "Authorization: token $FORGEJO_TOKEN" \
		"$FORGEJO_URL/api/v1/repos/$FORGEJO_REPO/push_mirrors-sync" >/dev/null ||
		echo "could not trigger the push mirror; waiting for its own sync"
fi

log "waiting for the push mirror to bring $tag to github.com/$GH_REPO"
for ((i = 0; ; i++)); do
	github GET "/git/ref/tags/$tag" >/dev/null 2>&1 && break
	if ((i >= 120)); then
		echo "error: $tag did not reach GitHub within an hour; check the push mirror" >&2
		exit 1
	fi
	sleep 30
done

log "publishing the GitHub release"
download=https://github.com/$GH_REPO/releases/download/$tag
body=$(
	cat <<EOF
Pinned AOSP $release source archives built by Forgejo Actions from $(git rev-parse HEAD).

\`build.zig.zon\`:

\`\`\`zig
.aosp = .{
    .url = "$download/${assets[1]}",
    .hash = "${zig_hash[${assets[1]}]}",
},
.webview = .{
    .url = "$download/${assets[2]}",
    .hash = "${zig_hash[${assets[2]}]}",
},
\`\`\`

\`artzig/build.zig.zon\`:

\`\`\`zig
.aosp = .{
    .url = "$download/${assets[0]}",
    .hash = "${zig_hash[${assets[0]}]}",
},
\`\`\`

SHA-256:

\`\`\`
$(cat "$out/SHA256SUMS")
\`\`\`
EOF
)
created=$(jq -n --arg tag "$tag" --arg body "$body" \
	'{tag_name: $tag, name: $tag, body: $body, draft: true}' |
	github POST /releases --data-binary @-)
id=$(jq -r .id <<<"$created")
for file in "${assets[@]}" SHA256SUMS; do
	echo "uploading $file"
	curl -fsSL -X POST \
		-H "Authorization: Bearer $GH_TOKEN" \
		-H "Content-Type: application/octet-stream" \
		--retry 3 \
		--upload-file "$out/$file" \
		"https://uploads.github.com/repos/$GH_REPO/releases/$id/assets?name=$file" >/dev/null
done
github PATCH "/releases/$id" --data-binary '{"draft": false}' | jq -r .html_url
echo "$body"
