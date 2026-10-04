#!/usr/bin/env bash
# Neutrality guard for the public chino-mobile tree (CI's neutrality job).
#
# This client is a neutral, bring-your-own-server app for the zaentrum
# platform. Three rules, everywhere in the tracked tree, prose included:
#   1. No internal hostnames: nothing under the operator's own .cloud domain.
#      The bare brand token and the cloud.nalet.chino package ids are stable
#      identifiers and stay allowed; only the host suffix is banned.
#   2. No other media products by name. The app describes itself, not what it
#      might replace.
#   3. No content-acquisition vocabulary. User-facing text and code describe
#      the client on its own terms.
#
# Before it scans, the guard checks its own patterns against lines it must and
# must not flag (self_test, below); a pattern that has gone blind fails the
# run. Ported from zaentrum-operator's scripts/check-neutrality.sh.
#
# Run: scripts/check-neutrality.sh [path]   (defaults to the repo root)
#      scripts/check-neutrality.sh --self-test   (the patterns only)
set -uo pipefail

# ── the tools ────────────────────────────────────────────────────────────────
# Missing, or without POSIX classes, any of these would fail the self-test
# below as if a pattern had gone blind, and send the reader looking at the
# patterns. Name the real cause instead — and still fail (exit 2).
need() { command -v "$1" >/dev/null 2>&1 || { echo "neutrality guard: needs $1 on PATH"; exit 2; }; }
for tool in grep sed cut tr; do need "$tool"; done
printf 'a_b\n' | grep -qiE '(^|[^[:alnum:]])b([^[:alnum:]]|$)' 2>/dev/null ||
  { echo "neutrality guard: needs a grep whose -E reads POSIX classes ([[:alnum:]])"; exit 2; }
[[ "$(printf 'aB\n' | sed -E 's/([[:lower:]])([[:upper:]])/\1 \2/g' 2>/dev/null)" == "a B" ]] ||
  { echo "neutrality guard: needs a sed whose -E reads POSIX classes ([[:lower:]])"; exit 2; }

# ── the patterns ─────────────────────────────────────────────────────────────
# A name counts where what stands on either side of it is not a letter or a
# digit. \b would not do: it counts "_" as part of a word, so TORRENT_DIR or an
# indexer_url went through \b(...)\b unseen. A letter or a digit on either
# side still keeps a name out of longer words — "complex" and "multiplex" are
# not a product. Plain POSIX classes: BSD grep on macOS reads them as GNU grep
# on the CI runner does.
word() { printf '(^|[^[:alnum:]])(%s)([^[:alnum:]]|$)' "$1"; }

host_re='nalet\.cloud'
vocab_re=$(word 'indexer|tracker|usenet|arr|torrent')
# Other media products, put together from pieces so that this file, which the
# scan skips, does not name them either.
p1='jelly''fin' p2='pl''ex' p3='em''by' p4='ko''di' p5='nalet''flix'
product_re=$(word "$p1|$p2|$p3|$p4|$p5")

# A product name run into an identifier in camelCase (fooUrl, myFoo) is a
# word of its own there. The rule matched product names anywhere in a line
# before it matched them as words, so the case change still counts as a
# separator for them: lines are split at it before the product names are
# looked for. ("ComplexType" splits into "Complex Type" and still passes.)
camel_split() { sed -E 's/([[:lower:][:digit:]])([[:upper:]])/\1 \2/g'; }

# ── self-test ────────────────────────────────────────────────────────────────
self_test() {
  local bad=0 upper cap
  expect() { # expect <name> <pattern> <hit|miss> <line> [camel]
    local got=miss line=$4
    [[ "${5:-}" == camel ]] && line=$(printf '%s\n' "$line" | camel_split)
    printf '%s\n' "$line" | grep -qiE "$2" && got=hit
    if [[ "$got" != "$3" ]]; then
      echo "SELF-TEST FAIL: the $1 pattern should $([[ $3 == hit ]] && echo flag || echo pass) this line: $4"
      bad=1
    fi
  }
  # Joined by "_" — the names \b let through.
  expect vocab "$vocab_re" hit 'TORRENT_DIR=/data'
  expect vocab "$vocab_re" hit 'indexer_url: http://x'
  expect vocab "$vocab_re" hit 'USENET_HOST'
  expect vocab "$vocab_re" hit 'val tracker_id = 1'
  expect vocab "$vocab_re" hit 'the_arr_stack'
  # The forms \b caught, still caught.
  expect vocab "$vocab_re" hit 'an indexer'
  expect vocab "$vocab_re" hit 'type Tracker struct'
  expect vocab "$vocab_re" hit 'the *arr apps'
  expect vocab "$vocab_re" hit 'torrent'
  # Longer words are other words.
  expect vocab "$vocab_re" miss 'torrential'
  expect vocab "$vocab_re" miss 'reindexers'
  expect vocab "$vocab_re" miss 'val array = IntArray(3)'
  expect vocab "$vocab_re" miss 'carry on'
  upper=$(printf '%s' "$p2" | tr '[:lower:]' '[:upper:]')
  cap=$(printf '%s' "$p2" | cut -c1 | tr '[:lower:]' '[:upper:]')$(printf '%s' "$p2" | cut -c2-)
  expect product "$product_re" hit "${upper}_TOKEN" camel
  expect product "$product_re" hit "url: http://${p1}:8096" camel
  expect product "$product_re" hit "the ${p3} client" camel
  expect product "$product_re" hit "${p4}_addon" camel
  expect product "$product_re" hit "${p5}" camel
  # camelCase: still a name of its own.
  expect product "$product_re" hit "val ${p1}Url = x" camel
  expect product "$product_re" hit "my${cap}Token" camel
  expect product "$product_re" miss 'complex' camel
  expect product "$product_re" miss 'multiplex' camel
  expect product "$product_re" miss 'COMPLEX_QUERY=1' camel
  expect product "$product_re" miss 'ComplexType' camel
  expect product "$product_re" miss 'perplexed' camel
  expect product "$product_re" miss 'kodiak' camel
  expect host "$host_re" hit "db.nalet"".cloud"
  expect host "$host_re" hit "https://sso.NALET"".CLOUD/realms/x"
  expect host "$host_re" miss 'media.example.org'
  expect host "$host_re" miss 'package cloud.nalet.chino.mobile'
  return $bad
}

if ! self_test; then
  echo "neutrality guard: its own patterns are wrong; fix them before trusting a scan"
  exit 2
fi
if [[ "${1:-}" == "--self-test" ]]; then
  echo "neutrality guard: self-test passed"
  exit 0
fi

need git # the scan reads the tracked files from git
root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
fail=0

# ── skipped ──────────────────────────────────────────────────────────────────
# Keep this list short and justified.
skip_files=(
  'LICENSE'                    # the MPL-2.0 text as published
  'scripts/check-neutrality.sh'  # this file lists the patterns it bans
)

is_skipped() {
  local f="$1" s
  for s in "${skip_files[@]}"; do
    [[ "$f" == "$s" ]] && return 0
  done
  return 1
}

# Tracked files only: build output is not ours to police. Read with a
# while-loop rather than mapfile — macOS ships bash 3.2, where mapfile does
# not exist, and this script runs for a human locally as well as on CI.
files=()
while IFS= read -r f; do files+=("$f"); done < <(cd "$root" && git ls-files)

echo "neutrality guard: scanning ${#files[@]} tracked files"

# scan <label> <pattern> [camel]: every tracked text file but the skipped
# ones; binary files (images, fonts, the wrapper jar) are left out, as
# ripgrep left them out before.
scan() {
  local label=$1 re=$2 split=${3:-} f path hits
  for f in "${files[@]}"; do
    is_skipped "$f" && continue
    path="$root/$f"
    [[ -f "$path" ]] || continue
    grep -Iq . "$path" 2>/dev/null || continue
    if [[ "$split" == camel ]]; then
      hits=$(camel_split < "$path" | grep -niE "$re" | cut -c1-130)
    else
      hits=$(grep -niE "$re" "$path" | cut -c1-130)
    fi
    if [[ -n "$hits" ]]; then
      echo "FAIL $label in $f"
      echo "$hits" | sed 's/^/        /'
      fail=1
    fi
  done
}

# ── rule 1: internal hostnames ───────────────────────────────────────────────
scan "internal hostname" "$host_re"

# ── rule 2: other media products ─────────────────────────────────────────────
scan "another media product's name" "$product_re" camel

# ── rule 3: content-acquisition vocabulary ───────────────────────────────────
scan "acquisition vocabulary" "$vocab_re"

if [[ $fail -eq 0 ]]; then
  echo "neutrality guard: clean"
else
  echo ""
  echo "The public tree must not carry internal hostnames, other media"
  echo "products' names or acquisition vocabulary. Use example.org / a neutral"
  echo "placeholder. If an exception is genuinely correct, add it to skip_files"
  echo "in this script WITH a reason."
fi
exit $fail
