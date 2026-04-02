Stage all changes, write a concise commit message that summarizes what changed and why, commit, then push to the current remote branch.

Steps:
1. Run `git status` and `git diff` to understand what has changed
2. Run `git log --oneline -5` to match the existing commit message style
3. Stage all modified and new files with `git add -A` (but warn and skip any `.env` or credential files)
4. Write a commit message: one short subject line (≤72 chars), then a blank line and a brief body if the changes warrant it. End with the standard co-authored-by trailer.
5. Commit using a HEREDOC so formatting is preserved
6. Push to the current branch's upstream with `git push`
7. Confirm success and show the commit SHA
