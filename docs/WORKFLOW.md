# How TiP was built

TiP has one developer and a teammate, working with two Claude roles.

## Roles

| Who | Role |
|---|---|
| Owner | Has the idea and the vision. Decides what TiP is, how it should look and feel, and what matters. Brings his view to every decision and has the final say. Does everything only a person can do: secrets, the PayPal and Render dashboards, the hardware, the video. Reviews and ships every change. |
| Lead (a Claude chat) | Turns the owner's vision into a technical design. Proposes approaches with an opinion, builds design previews, writes the instructions for the agent and reviews every report. |
| Agent (Claude Code) | Writes the code, tests and docs, and reports back. |
| Teammate | Contributed the tip history API early on and made the panda's cardboard template. |

## How a piece of work goes

1. The owner says what he wants and how he sees it.
2. The lead works out the design and gives its opinion. They agree on the approach.
3. For anything visual, the lead builds a preview from the real pages. The owner approves it before any code is written.
4. The lead writes the instructions. Larger pieces were written as briefs (`briefs/`), smaller ones as short prompts.
5. The agent builds it on `develop`, runs the full build, commits locally and reports.
6. The lead reviews the report. Questions that are the owner's call go to him.
7. The owner reviews the diff, pushes and releases.

Releasing, once `develop` is green:

```powershell
git checkout main
git merge --ff-only develop
git push origin main
git checkout develop
```

A push to `main` runs CI and deploys to Render.

## Reviews before submission

- A security and correctness review of the whole codebase, with every finding fixed or decided (`briefs/reviews/09-findings.md`).
- A test review that broke 64 important guards on purpose to prove the tests catch them (`briefs/reviews/10-tests.md`).
- A search for unused files and code (`briefs/reviews/11-unused.md`).

## Rules

- Every commit builds on its own.
- Tests cover the failure paths, not just the happy path. A flaky test is fixed, never rerun.
- Secrets never go in the repo, a chat, an issue or a pull request.
- Pushed history is never rewritten.
- Each piece of work starts a fresh agent session, which reads `CLAUDE.md`, the docs and `CODEBASE.md` first.

## Definition of done

- `./gradlew build` green locally and in CI.
- Tests cover the behaviour and its failure paths.
- `CODEBASE.md` and the docs match the code.
- A report with any open questions.
- Nothing in the diff that wasn't asked for.
