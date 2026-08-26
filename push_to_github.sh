#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# push_to_github.sh
# Commit any pending changes and push to GitHub using a Personal Access Token.
#
# Usage:
#   bash push_to_github.sh
#
# Requires:
#   GITHUB_PERSONAL_ACCESS_TOKEN  — set as a Replit Secret
# ──────────────────────────────────────────────────────────────────────────────
set -e

if [ -z "$GITHUB_PERSONAL_ACCESS_TOKEN" ]; then
  echo "ERROR: GITHUB_PERSONAL_ACCESS_TOKEN is not set."
  echo "Add it as a Replit Secret and re-run this script."
  exit 1
fi

REPO="https://${GITHUB_PERSONAL_ACCESS_TOKEN}@github.com/TITANICBHAI/FocusFlow-PC.git"

# Stage and commit any pending changes
export GIT_AUTHOR_NAME="FocusFlow Bot"
export GIT_AUTHOR_EMAIL="focusflow-bot@tbtechs.app"
export GIT_COMMITTER_NAME="FocusFlow Bot"
export GIT_COMMITTER_EMAIL="focusflow-bot@tbtechs.app"

# Remove any stale lock file left by a previous interrupted process
rm -f .git/index.lock .git/MERGE_HEAD .git/CHERRY_PICK_HEAD 2>/dev/null || true

if ! git diff --quiet || ! git diff --staged --quiet || [ -n "$(git ls-files --others --exclude-standard)" ]; then
  echo "Staging pending changes..."
  git add -A

  # Build a dynamic commit message based on what changed
  CHANGED_FILES=$(git diff --cached --name-only | head -10 | tr '\n' ', ' | sed 's/,$//')
  FILE_COUNT=$(git diff --cached --name-only | wc -l | tr -d ' ')
  TIMESTAMP=$(date -u '+%Y-%m-%d %H:%M UTC')

  if [ -n "$1" ]; then
    COMMIT_MSG="$1"
  elif [ "$FILE_COUNT" -le 3 ]; then
    COMMIT_MSG="Update ${CHANGED_FILES} [${TIMESTAMP}]"
  else
    COMMIT_MSG="Update ${FILE_COUNT} files — ${TIMESTAMP}"
  fi

  git commit -m "$COMMIT_MSG"
  echo "Committed: $COMMIT_MSG"
else
  echo "Nothing to commit — working tree clean."
fi

echo "Pushing HEAD → github.com/TITANICBHAI/FocusFlow-PC (main)..."
git push "$REPO" HEAD:main
echo "Done. Watch CI at: https://github.com/TITANICBHAI/FocusFlow-PC/actions"
