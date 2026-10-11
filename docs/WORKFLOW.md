# How the TiP team works

## Roles

| Who | Role |
|---|---|
| Lead (Claude chat on the owner's account) | Tech lead. Makes the technical and design decisions, writes every brief and issue, reviews every report and pull request. Everything gets reported here. |
| Owner | Runs the lead chat and his own coding agent. Relays briefs and reports, reviews the working tree, pushes, merges, deploys. Does anything only a person can do (secrets, PayPal and Render dashboards, hardware, video). |
| Owner's agent (Claude Code) | Builds the core: payments, devices, AI, firmware, deployment. Works on `develop`. |

## The owner's loop

1. The lead writes a brief.
2. The owner pastes it into his agent.
3. The agent implements, runs `./gradlew build`, commits locally on `develop` and prints its report.
4. The owner pastes the report back to the lead.
5. The lead checks it and replies with fixes or the next brief.
6. The owner reviews the diff, pushes `develop` and waits for CI.
7. When `develop` is green and ready to ship, the owner fast forwards `main` to `develop` and pushes. CI runs again and deploys to Render.

```powershell
git checkout main
git merge --ff-only develop
git push origin main
git checkout develop
```

## Reporting

Everything flows to the lead.

- The agent ends every task with the report format in `CLAUDE.md`.

## Rules

- `main` and `develop` are protected by GitHub rulesets. Changes need a pull request with green CI. The owner can bypass for his own pushes to `develop` and the fast forward of `main`.
- Nobody edits another feature's package without a brief that says so.
- Secrets never go in the repo, in chats, in issues or in pull requests. `.env` is local only and never committed; `.env.example` lists the settings.
- Start a fresh agent session at the end of each brief. The new session reads `CLAUDE.md`, the docs and `CODEBASE.md`, so nothing is lost.

## Definition of done

- `./gradlew build` green locally and in CI
- Tests cover the behaviour and the failure paths in the brief
- `CODEBASE.md` and `README.md` updated when relevant
- Report written, open questions listed
- Nothing in the diff the brief did not ask for
