#!/usr/bin/env bash
# Install repo-tracked git hooks for this clone by pointing core.hooksPath at
# scripts/git-hooks. core.hooksPath is a repo-wide setting (lives in .git/config)
# so this install covers all worktrees automatically — no need to re-run per
# worktree.

set -e

REPO_ROOT=$(git rev-parse --show-toplevel)
cd "$REPO_ROOT"

HOOKS_DIR="scripts/git-hooks"
if [ ! -d "$HOOKS_DIR" ]; then
    echo "Error: $HOOKS_DIR not found. Run from a checkout of this repo." >&2
    exit 1
fi

chmod +x "$HOOKS_DIR"/*

git config core.hooksPath "$HOOKS_DIR"

echo "Git hooks installed: core.hooksPath = $HOOKS_DIR"
echo "Active hooks:"
ls -1 "$HOOKS_DIR"
