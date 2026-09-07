# Local work artifacts

This rule governs where AI session working artifacts live in Arc.Kotlin. Plans, handover documents,
session notes, continuation prompts, status boards, scratch analyses, and research dumps are **work
records, not documentation**. They are local to the session that produced them and must never enter
git history, on any branch.

## The rule

- Create every work record inside `.ai-work/` at the repository root. Never at the repository root
  itself, never under `Documentation/`, never under `.ai/`, never anywhere else.
- `.ai-work/` is gitignored (`.gitignore` line 30) and must stay untracked. Never commit anything
  inside it, never `git add -f` anything inside it, and never remove the ignore entry.
- `.pi/tasks/` and `.pi/delegate/` are local execution artifacts. `.pi/` is gitignored
  (`.gitignore` line 31) and must also stay untracked, under the same terms.
- Scratch scripts, captured command output, and downloaded fixtures used only to reason about a change
  belong in `.ai-work/` too, not beside the source they were investigating.

## What is not a work record

`Documentation/`, `README.md`, `AGENTS.md`, module `README.md` files, and the `.ai/` corpus are
tracked repository content. They are reviewed, versioned, and read by people who were not in the
session. Do not move them into `.ai-work/`, and do not disguise a work record as one of them by giving
it a documentation-sounding name.

## Follow-ups outlive the session as issues

A genuine follow-up that must survive the session is not a work record. Open a GitHub issue for it —
or suggest one when you are not authorized to — so the work is tracked where everyone can see it,
instead of leaving a planning file behind for the next session to rediscover.

## CI enforces this

`.github/workflows/verify-no-work-records.yml` runs on every pull request and every push to `main`,
calling the shared `Cratis/Workflows/.github/workflows/verify-no-work-records.yml` guard. It fails the
build when:

1. Any path under `.ai-work/` is tracked by git.
2. A root-level `SCREAMING-CASE.md` file exists outside its allowlist — `README`, `LICENSE`, `AGENTS`,
   `CLAUDE`, `CONTRIBUTING`, `SECURITY`, `CHANGELOG` and similar are allowed; `PLAN.md`,
   `HANDOVER.md`, and `IMPLEMENTATION_STATUS.md` are not.
3. Any tracked `*.md` anywhere outside `.ai/`, `.claude/`, `.github/`, `.pi/`, `.agents/`, and
   `.ai-work/` matches a session-artifact naming pattern: `*HANDOVER*.md`, `PROMPT-*.md`,
   `*NEXT-SESSION*.md`, `SESSION-PROMPT*.md`, or `*SESSION[-_]HANDOVER*.md` (uppercase only).

If you find such a file already tracked, move it into `.ai-work/` and remove it from tracking in a
dedicated commit. See [git-and-pull-requests.md](./git-and-pull-requests.md) for the commit and merge
rules that apply to that cleanup.
