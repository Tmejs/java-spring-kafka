# Git checkpoints

## Recommended working rule

One coherent change, verified, committed, and pushed before the next checkpoint.

- Keep commits small enough to explain with one clear commit message.
- A checkpoint can contain a few related commits when they form one working step.
- Review the diff and run the checks relevant to the change before committing.
- Once a Maven build exists, run `./mvnw verify` for code checkpoints. Run a
  Docker Compose smoke test when container wiring or the end-to-end flow changes.
- Run verification locally with the SDKMAN-managed Java 25 installation. This
  repository intentionally has no GitHub Actions pipeline.
- For documentation-only checkpoints, review the content and run `git diff --check`.
- Update the relevant documents in `codex/` with decisions and verification results.
- Push completed checkpoints to the current working branch and verify remote sync.
- Never force-push or publish credentials, local secrets, or generated build output.
- If checks or pushing fail, report the blocker instead of calling the checkpoint complete.

Examples: approved design, build skeleton, API generation, database migrations,
order creation, inventory reservation, Kafka failure handling, and a complete demo.

## Repository connection

`origin` uses `git@github.com:Tmejs/java-spring-kafka.git`.
The existing SSH key authenticated as `Tmejs` and the initial documentation
checkpoint was successfully pushed on 2026-09-23. HTTPS was replaced because
no usable HTTPS credentials were available to Git.

Keys and credentials remain outside the repository.
