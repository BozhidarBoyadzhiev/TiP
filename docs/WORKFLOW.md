# How the TiP team works

## Roles

| Who | Role |
|---|---|
| Lead (Claude chat on the owner's account) | Tech lead. Makes the technical and design decisions, writes every brief and issue, reviews every report and pull request. Everything gets reported here. |
| Owner | Runs the lead chat and his own coding agent. Relays briefs and reports, reviews the working tree, pushes, merges, deploys. Does anything only a person can do (secrets, PayPal and Render dashboards, hardware, video). |
| Owner's agent (Claude Code) | Builds the core: payments, devices, AI, firmware, deployment. Works on `develop`. |
| Teammate | Owns one feature at a time, starting with the owner dashboard backend. Runs his own chat and coding agent. |
| Teammate's chat | His tutor. Explains the code, Java and Spring, helps him understand what his agent wrote and write his pull request. It does not set scope or make architecture decisions. |
| Teammate's agent (Claude Code) | Implements the teammate's issue on its feature branch. |

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

## The teammate's loop

1. The lead writes his issue on GitHub. It is a complete agent brief with the branch name, scope, acceptance criteria and tests.
2. He creates the branch from the latest `develop` and gives the issue to his agent.
3. His agent implements, runs `./gradlew build` and commits locally.
4. He reads the change with his chat until he can explain it.
5. He pushes his branch and opens a pull request into `develop` using the template. The "In my own words" section is written by him, not by an agent.
6. CI must be green.
7. The owner's agent lists the pull request in its next report. The lead reviews it (the owner pastes `gh pr diff <n>`).
8. The owner merges, or the lead's review comments go back to the teammate.

## Reporting

Everything flows to the lead.

- Agents end every task with the report format in `CLAUDE.md`.
- The owner's agent adds a "Teammate activity" section listing new or updated pull requests, their CI status and their open questions.
- The teammate's agent puts its report into the pull request description.

Scope questions from the teammate go to the owner, who brings them to the lead. Learning questions go to his own chat.

## Rules

- `main` and `develop` are protected by GitHub rulesets. Changes need a pull request with green CI. The owner can bypass for his own pushes to `develop` and the fast forward of `main`.
- The teammate never pushes to `develop` or `main`. One pull request per issue.
- Keep a feature branch current by merging `develop` into it, not rebasing a pushed branch.
- Nobody edits another feature's package without a brief that says so.
- Secrets never go in the repo, in chats, in issues or in pull requests. Each person keeps their own `.env`.
- Start a fresh agent session at the end of each brief. The new session reads `CLAUDE.md`, the docs and `CODEBASE.md`, so nothing is lost.

## Definition of done

- `./gradlew build` green locally and in CI
- Tests cover the behaviour and the failure paths in the brief
- `CODEBASE.md` and `README.md` updated when relevant
- Report written, open questions listed
- Nothing in the diff the brief did not ask for
