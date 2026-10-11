#!/bin/sh
# Counts the plugin's tracked text and estimates tokens at 3.3 characters a token (conservative for Java).
# Binary files (the Gradle wrapper jar, icon.png) are left out; every text file, wrapper scripts included, counts.
# Usage: scripts/size-budget.sh [ref] [base branch]  (with base and branch, also ref plus what branch adds over base)
set -eu
cd "$(git rev-parse --show-toplevel)"
ref=${1:-HEAD}
empty=$(git hash-object -t tree /dev/null)

# Characters of the text files in a tree, per part: main, test, other.
count() {
	git diff --numstat "$empty" "$1" | awk -F '\t' '$1 == "-" { print $3 }' > "$tmp.binary"
	git ls-tree -r -l "$1" | awk -v binary="$tmp.binary" '
		BEGIN { while ((getline line < binary) > 0) { skip[line] = 1 } }
		{ path = $0; sub(/^[^\t]*\t/, "", path); if (path in skip) { next }
		  part = path ~ /^src\/main\// ? "main" : path ~ /^src\/test\// ? "test" : "other"; chars[part] += $4; total += $4 }
		END { printf "%d %d %d %d\n", chars["main"], chars["test"], chars["other"], total }'
}
tokens() { awk -v c="$1" 'BEGIN { printf "%d", (c + 3.29) / 3.3 }'; }
row() { printf '%-34s %9s chars %8s tokens\n' "$1" "$2" "$(tokens "$2")"; }

base=${2:-}
branch=${3:-}
tmp=$(mktemp)
trap 'rm -f "$tmp" "$tmp.binary"' EXIT
set -- $(count "$ref")
row "$ref src/main" "$1"
row "$ref src/test" "$2"
row "$ref docs, build, wrapper" "$3"
row "$ref total" "$4"
if [ -n "$base" ] && [ -n "$branch" ]; then
	added=$(($(count "$branch" | awk '{ print $4 }') - $(count "$base" | awk '{ print $4 }')))
	row "$ref + $branch over $base" "$(($4 + added))"
fi
