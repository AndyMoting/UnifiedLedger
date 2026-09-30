#!/usr/bin/env bash
# CI-side implementation of the harness verify-project.ps1 trace scope.
#
# Synchronization obligation: the harness script `verify-project.ps1` lives
# in an untracked, local-only skill directory (CI cannot read it), so this
# file is the in-repo CI equivalent of its trace scope. The TRACE_PATTERN
# below and every AGENT_PATH_PATTERN segment must stay verbatim identical to
# that script's trace scope ($tracePattern and $agentPathPattern). Changing
# one side requires changing the other in the same change.
#
# Usage:
#   CI:    bash tools/ci/trace-scan.sh
#   Local: the same command from the repository root; Git Bash on Windows
#          behaves identically. The script is invoked through `bash`, so no
#          executable bit or chmod is required.
#   Optional: set ALLOWED_TRACE_PATH to a colon- or newline-separated list of
#          exact repository-relative paths to exempt from the path-based
#          scans (default: empty). CI runs with the default empty allowlist.
#
# Scans, mirroring the harness trace scope:
#   1. Tracked files: `git grep` over the current worktree/index content.
#   2. History messages: every commit subject and body across `--all`.
#   3. History paths: every path ever touched, filtered against the agent
#      path pattern (with allowlist exemption).
#   4. History trees: `git grep` over every commit in `--all`, batched 200
#      commits at a time to stay inside the Windows 32K argv limit; Linux CI
#      keeps the same batching for parity. Only the path part of the
#      `SHA:path` output is judged, and the allowlist applies.
#
# Exit codes: 0 = all scans clean; 1 = forbidden trace found (hits are
# printed); 2 = unexpected git failure (exit code outside {0, 1}) or an
# invalid ALLOWED_TRACE_PATH entry.

set -u

# Verbatim from verify-project.ps1 trace scope. Do not edit here alone.
TRACE_PATTERN='Codex|Codex Cloud|Claude|Claude Code|ChatGPT|OpenAI|Copilot|Gemini|Qwen|ZCode|Z\.ai|GLM|DeepSeek|Co-authored-by|Generated-by|Assisted-by'

# Verbatim from verify-project.ps1 trace scope ($agentPathPattern, segments
# joined by '|'). Do not edit here alone.
AGENT_PATH_PATTERN='^(AGENTS?\.md|CLAUDE[^/]*\.md|GEMINI\.md|QWEN\.md)$|^(\.cursorrules|\.cursorignore|\.cursorindexingignore|\.windsurfrules|\.clineignore|\.rooignore|\.roomodes|\.roorules[^/]*|\.augment-guidelines|\.geminiignore|\.aiexclude|\.mcp\.json|opencode\.jsonc?|\.aider[^/]*)$|^(\.worktrees|\.agents|\.agent|\.codex|\.claude|\.gemini|\.qwen|\.copilot|\.cursor|\.windsurf|\.devin|\.cline|\.clinerules|\.roo|\.continue|\.kiro|\.opencode|\.trae|\.qoder|\.augment|\.junie|\.amazonq)/|(^|/)(prompt|transcript|conversation)([-_.][^/]*)?\.(md|txt|jsonl?|ya?ml)$|(^|/)session([-_.](notes?|log|transcript|context|handoff))?\.(md|txt|jsonl?|ya?ml)$|^\.github/(copilot-instructions\.md$|(instructions|prompts|agents|skills)/)|^\.vscode/mcp\.json$|^docs/(SOURCE_REFERENCES\.md|WORK_PLAN\.local\.md|[^/]+\.local\.md)$'

BATCH_SIZE=200

fatal() {
    echo "trace-scan: $1" >&2
    exit 2
}

# Normalize a repository-relative path: backslashes to forward slashes, drop
# a leading "./", lowercase (harness compares OrdinalIgnoreCase).
normalize_path() {
    local p="${1//\\//}"
    p="${p#./}"
    printf '%s' "${p,,}"
}

# Parse ALLOWED_TRACE_PATH (colon- or newline-separated) into a normalized,
# lowercased set. Invalid entries abort with exit code 2, mirroring the
# harness validation of exact repository-relative paths.
load_allowlist() {
    ALLOWED_SET=""
    local raw="${ALLOWED_TRACE_PATH:-}"
    [ -n "$raw" ] || return 0
    local entry normalized
    raw="${raw//$'\n'/:}"
    while IFS= read -r entry; do
        [ -n "$entry" ] || continue
        normalized="$(normalize_path "$entry")"
        case "$normalized" in
            ''|/*|*/|..|*/..|../*|*/../*)
                fatal "ALLOWED_TRACE_PATH entry must be an exact repository-relative path: $entry"
                ;;
        esac
        ALLOWED_SET="${ALLOWED_SET:+$ALLOWED_SET:}$normalized"
    done < <(printf '%s\n' "$raw" | tr ':' '\n')
}

is_allowed_path() {
    local needle
    [ -n "$ALLOWED_SET" ] || return 1
    needle="$(normalize_path "$1")"
    case ":$ALLOWED_SET:" in
        *":$needle:"*) return 0 ;;
    esac
    return 1
}

# Shared runner for a git command whose {0,1} exit codes both mean "the scan
# ran" (0 = matches found, 1 = no matches); anything else is an unexpected
# failure. The git command's stdout is captured in $TMP_OUT.
run_git_scan() {
    # $1: label used in the failure message; the git command follows as "$@".
    local label="$1" rc
    shift
    "$@" > "$TMP_OUT" 2> "$TMP_ERR"
    rc=$?
    if [ "$rc" -ne 0 ] && [ "$rc" -ne 1 ]; then
        cat "$TMP_ERR" >&2
        fatal "$label failed with unexpected exit code $rc."
    fi
}

report_hits() {
    echo "Forbidden Agent trace found: $1" >&2
    cat "$TMP_HITS" >&2
    exit 1
}

scan_tracked_files() {
    run_git_scan "Tracked Agent trace scan" \
        git grep -l -i -I -E "$TRACE_PATTERN" -- .
    : > "$TMP_HITS"
    while IFS= read -r p; do
        [ -n "$p" ] || continue
        if ! is_allowed_path "$p"; then
            printf '%s\n' "$p" >> "$TMP_HITS"
        fi
    done < "$TMP_OUT"
    if [ -s "$TMP_HITS" ]; then
        report_hits "tracked paths"
    fi
}

scan_history_messages() {
    # The allowlist does not apply to commit messages.
    run_git_scan "Git history message scan" \
        git log --all --format='%H%x09%s%x09%b'
    grep -i -I -E "$TRACE_PATTERN" "$TMP_OUT" > "$TMP_HITS"
    case $? in
        0) report_hits "Git history messages" ;;
        1) : ;;
        *) fatal "Grep failed during Git history message scan." ;;
    esac
}

scan_history_paths() {
    run_git_scan "Git history path scan" \
        git log --all --name-only --pretty=format:
    # Drop blank lines, drop allowlisted paths, match the agent path pattern.
    awk 'NF' "$TMP_OUT" > "$TMP_ERR"
    : > "$TMP_HITS"
    while IFS= read -r p; do
        if ! is_allowed_path "$p"; then
            printf '%s\n' "$p" >> "$TMP_HITS"
        fi
    done < "$TMP_ERR"
    grep -i -E "$AGENT_PATH_PATTERN" "$TMP_HITS" > "$TMP_ERR"
    case $? in
        0)
            head -n 10 "$TMP_ERR" > "$TMP_HITS"
            report_hits "Agent-specific paths in Git history (first 10 shown)"
            ;;
        1) : ;;
        *) fatal "Grep failed during Git history path scan." ;;
    esac
}

scan_history_trees() {
    local shas rc
    shas="$(git rev-list --all)"
    rc=$?
    if [ "$rc" -ne 0 ]; then
        fatal "Git commit enumeration failed."
    fi
    : > "$TMP_HITS"
    local batch=() sha
    for sha in $shas; do
        batch+=("$sha")
        if [ "${#batch[@]}" -ge "$BATCH_SIZE" ]; then
            run_git_scan "Historical tree trace scan" \
                git grep -l -i -I -E "$TRACE_PATTERN" "${batch[@]}" -- .
            [ -s "$TMP_OUT" ] &&
                sed -E 's/^[0-9a-fA-F]{40}://' "$TMP_OUT" >> "$TMP_HITS"
            batch=()
        fi
    done
    if [ "${#batch[@]}" -gt 0 ]; then
        run_git_scan "Historical tree trace scan" \
            git grep -l -i -I -E "$TRACE_PATTERN" "${batch[@]}" -- .
        [ -s "$TMP_OUT" ] &&
            sed -E 's/^[0-9a-fA-F]{40}://' "$TMP_OUT" >> "$TMP_HITS"
    fi
    # Deduplicate: one file can appear in many commits; judge paths only.
    sort -u "$TMP_HITS" > "$TMP_OUT"
    : > "$TMP_ERR"
    while IFS= read -r p; do
        [ -n "$p" ] || continue
        if ! is_allowed_path "$p"; then
            printf '%s\n' "$p" >> "$TMP_ERR"
        fi
    done < "$TMP_OUT"
    if [ -s "$TMP_ERR" ]; then
        head -n 10 "$TMP_ERR" > "$TMP_HITS"
        report_hits "historical trees (first 10 unique paths shown)"
    fi
}

main() {
    git rev-parse --is-inside-work-tree >/dev/null 2>&1 ||
        fatal "Not a Git repository; run from the repository root."
    # Per-process scratch files: concurrent invocations must not interfere.
    TMP_OUT="$(mktemp)" || fatal "mktemp failed."
    TMP_ERR="$(mktemp)" || fatal "mktemp failed."
    TMP_HITS="$(mktemp)" || fatal "mktemp failed."
    trap 'rm -f "$TMP_OUT" "$TMP_ERR" "$TMP_HITS"' EXIT
    load_allowlist
    scan_tracked_files
    scan_history_messages
    scan_history_paths
    scan_history_trees
    echo "trace-scan: all scans clean."
    exit 0
}

main "$@"
