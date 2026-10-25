#!/usr/bin/env bash
# Lists and downloads live timing recordings from the private R2 bucket.
#
# Segments are uploaded by LiveRecorder as
#   aks-v2/<yyyy-mm-dd>/<connection start>-<nnnn>.aks.gz          (Al Kamel)
#   imsa-telemetry/<yyyy-mm-dd>/<connection start>-<nnnn>.aks.gz  (IMSA telemetry)
# where the date is the UTC day the segment was opened. They download flat, as
# <feed>_<date>_<file> (aks-v2_2026-09-30_20260930T164827Z-0001.aks.gz).
#
# Recordings are licensed feed data: they go to ~/Downloads, outside the repo,
# by default.
#
# Needs the aws CLI and the backend's R2 settings in the environment:
#   R2_ENDPOINT, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, ALKAMELV2_RECORDING_BUCKET
# Put them in scripts/.r2.env (gitignored) to have them loaded automatically;
# anything still missing is prompted for, for that run only.
#
# Usage:
#   scripts/r2-recordings.sh list [aks|telemetry] [yyyy-mm-dd]
#   scripts/r2-recordings.sh get  aks|telemetry|all [yyyy-mm-dd] [options]
#
# all is both feeds; leaving out the date downloads every day in the bucket.
#
# get options:
#   --connection STAMP   only this connection (20260930T164827Z); also looks in
#                        the next day's folder, where a session past midnight
#                        UTC keeps going
#   --merge              also join each connection's segments into one
#                        <feed>_<date>_<stamp>.aks.gz, which replay-file takes
#                        (gzip members concatenate into one valid stream)
#   --out DIR            where to save (default ~/Downloads)
#
# Examples:
#   scripts/r2-recordings.sh list aks
#   scripts/r2-recordings.sh list telemetry 2026-10-01
#   scripts/r2-recordings.sh get aks 2026-09-30 --connection 20260930T164827Z --merge
#   scripts/r2-recordings.sh get all
set -euo pipefail

die() { echo "error: $*" >&2; exit 1; }
usage() { sed -n '2,/^set -euo pipefail/{ /^set -euo pipefail/d; s/^# \{0,1\}//; p; }' "$0"; }
case "${1:-}" in
    -h|--help) usage; exit 0 ;;
    list|get) ;;
    *) usage; exit 2 ;;
esac

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$here/.r2.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    source "$here/.r2.env"
    set +a
fi

# Temporary downloads and merges are never mistaken for finished recordings.
pending=""
trap 'if [[ -n "$pending" ]]; then rm -f -- "$pending"; fi' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

command -v aws >/dev/null || die "the aws CLI is not installed"
# Anything still missing is asked for, for this run only (nothing is saved).
for var in R2_ENDPOINT R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY ALKAMELV2_RECORDING_BUCKET; do
    [[ -n "${!var:-}" ]] && continue
    { exec 3<>/dev/tty; } 2>/dev/null || die "$var is not set and there is no terminal to ask on (see the top of this script)"
    if [[ $var == R2_SECRET_ACCESS_KEY ]]; then
        printf '%s (hidden): ' "$var" >&3
        IFS= read -rs "$var" <&3
        printf '\n' >&3
    else
        printf '%s: ' "$var" >&3
        IFS= read -r "$var" <&3
    fi
    exec 3>&-
    [[ -n "${!var}" ]] || die "$var is required"
done

bucket="$ALKAMELV2_RECORDING_BUCKET"

# R2 speaks S3; only these keys, never whatever ~/.aws holds.
export AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID"
export AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY"
export AWS_DEFAULT_REGION=auto
unset AWS_PROFILE AWS_SESSION_TOKEN
# aws CLI 2.23+ checksum defaults that R2 doesn't support.
export AWS_REQUEST_CHECKSUM_CALCULATION=when_required
export AWS_RESPONSE_CHECKSUM_VALIDATION=when_required

s3() { aws --endpoint-url "$R2_ENDPOINT" s3 "$@"; }

prefix_for() {
    case "$1" in
        aks|aks-v2) echo "aks-v2" ;;
        telemetry|imsa-telemetry) echo "imsa-telemetry" ;;
        *) die "feed must be aks or telemetry, not '$1'" ;;
    esac
}

valid_date() {
    [[ "$1" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "date must be yyyy-mm-dd, not '$1'"
    local normalized
    normalized="$(date -j -u -f "%Y-%m-%d" "$1" "+%Y-%m-%d" 2>/dev/null || date -u -d "$1" "+%Y-%m-%d" 2>/dev/null)" \
        || die "invalid calendar date '$1'"
    [[ "$normalized" = "$1" ]] || die "invalid calendar date '$1'"
}

next_day() { date -j -u -v+1d -f "%Y-%m-%d" "$1" "+%Y-%m-%d" 2>/dev/null || date -u -d "$1 + 1 day" "+%Y-%m-%d"; }

# One line per connection: date, stamp, segment count, total size.
summarise() {
    awk '{ split($4, parts, "/"); day = parts[2]; file = parts[3];
           stamp = substr(file, 1, index(file, "-") - 1);
           key = parts[1] "/" day " " stamp; n[key]++; bytes[key] += $3 }
         END { for (k in n) printf "%s  %3d segments  %8.1f MB\n", k, n[k], bytes[k] / 1048576 }' | sort
}

cmd_list() {
    [[ $# -le 2 ]] || die "list accepts only a feed and optional date"
    local feeds=("aks-v2" "imsa-telemetry")
    [[ $# -ge 1 ]] && feeds=("$(prefix_for "$1")")
    local day="${2:-}"
    [[ -n "$day" ]] && valid_date "$day"
    for feed in "${feeds[@]}"; do
        s3 ls --recursive "s3://$bucket/$feed/${day:+$day/}" | summarise
    done
}

cmd_get() {
    [[ $# -ge 1 ]] || die "get needs a feed (aks, telemetry or all)"
    local feeds day="" connection="" merge=false out="$HOME/Downloads"
    if [[ "$1" == all ]]; then
        feeds=("aks-v2" "imsa-telemetry")
    else
        feeds=("$(prefix_for "$1")")
    fi
    shift
    if [[ $# -gt 0 && "$1" != --* ]]; then
        day="$1"
        valid_date "$day"
        shift
    fi
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --connection)
                [[ $# -ge 2 && -n "$2" && "$2" != --* ]] || die "--connection needs a stamp"
                connection="$2"; shift 2 ;;
            --merge) merge=true; shift ;;
            --out)
                [[ $# -ge 2 && -n "$2" && "$2" != --* ]] || die "--out needs a directory"
                out="$2"; shift 2 ;;
            *) die "unknown option '$1'" ;;
        esac
    done

    # A connection is filed under the day it started, then maybe the next.
    local days=("$day")
    if [[ -n "$connection" ]]; then
        [[ "$connection" =~ ^([0-9]{4})([0-9]{2})([0-9]{2})T[0-9]{6}Z$ ]] \
            || die "connection must look like 20260930T164827Z, not '$connection'"
        [[ -z "$day" ]] && day="${BASH_REMATCH[1]}-${BASH_REMATCH[2]}-${BASH_REMATCH[3]}"
        valid_date "$day"
        days=("$day" "$(next_day "$day")")
    fi

    mkdir -p "$out"
    local found=0
    for feed in "${feeds[@]}"; do
        get_feed "$feed" "$connection" "$merge" "$out" "${days[@]}"
    done
    [[ $found -eq 1 ]] || die "nothing found${day:+ for $day}${connection:+ for connection $connection}"
}

# Downloads one feed's segments. The caller's dynamically scoped "found" flag
# records an empty result without putting this function in an errexit-disabled
# &&/if context. Listing and transfer failures always stop the whole command.
get_feed() {
    local feed="$1" connection="$2" merge="$3" out="$4"
    shift 4
    local keys=() listing d key stamp sequence
    local pattern="^${feed}/[0-9]{4}-[0-9]{2}-[0-9]{2}/[0-9]{8}T[0-9]{6}Z-[0-9]+\.aks\.gz$"
    for d in "$@"; do
        listing="$(s3 ls --recursive "s3://$bucket/$feed/${d:+$d/}")" \
            || die "could not list recordings for $feed${d:+ on $d}"
        while read -r _ _ _ key; do
            [[ -n "$key" && "$key" =~ $pattern ]] || continue
            [[ -n "$connection" && "${key##*/}" != "$connection"-* ]] && continue
            keys+=("$key")
        done <<< "$listing"
    done
    [[ ${#keys[@]} -gt 0 ]] || return 0
    found=1

    for key in "${keys[@]}"; do
        local target="$out/${key//\//_}"
        [[ ! -d "$target" ]] || die "download destination is a directory: $target"
        if [[ -s "$target" ]] && gzip -t -- "$target" 2>/dev/null; then
            echo "have  ${target##*/}"
        else
            pending="$(mktemp "$out/.r2-recording.XXXXXX")" || die "could not create a download file"
            s3 cp --only-show-errors "s3://$bucket/$key" "$pending" || die "could not download $key"
            gzip -t -- "$pending" || die "download is not a complete gzip recording: $key"
            mv -f -- "$pending" "$target" || die "could not save $target"
            pending=""
            echo "got   ${target##*/}"
        fi
    done

    if $merge; then
        local stamps parts first day merged
        stamps="$(for key in "${keys[@]}"; do stamp="${key##*/}"; printf '%s\n' "${stamp%-*}"; done | sort -u)"
        for stamp in $stamps; do
            # Sort by numeric sequence, even after 9999 or across midnight.
            # Only validated keys enter this text list; output paths stay quoted.
            parts="$(for key in "${keys[@]}"; do
                [[ "${key##*/}" = "$stamp"-* ]] || continue
                sequence="${key##*-}"; sequence="${sequence%.aks.gz}"
                printf '%s %s\n' "$sequence" "$key"
            done | LC_ALL=C sort -n -k1,1)"
            first="${parts%%$'\n'*}"; first="${first#* }"
            day="${first#*/}"; day="${day%%/*}"
            merged="$out/${feed}_${day}_${stamp}.aks.gz"
            [[ ! -d "$merged" ]] || die "merge destination is a directory: $merged"
            pending="$(mktemp "$out/.r2-merge.XXXXXX")" || die "could not create a merge file"
            while read -r sequence key; do
                cat -- "$out/${key//\//_}" || die "could not read segment $key"
            done <<< "$parts" > "$pending"
            gzip -t -- "$pending" || die "merged recording is not valid gzip"
            mv -f -- "$pending" "$merged" || die "could not save $merged"
            pending=""
            echo "merged ${merged##*/}"
        done
    fi
}

case "${1:-}" in
    list) shift; cmd_list "$@" ;;
    get) shift; cmd_get "$@" ;;
esac
