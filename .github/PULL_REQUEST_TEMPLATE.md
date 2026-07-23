## Summary

<!-- What does this PR do, and why? Link the issue if one exists. -->

## Milestone

<!-- Which milestone (docs/MILESTONES.md) does this belong to? -->

## Definition of Done

Per [docs/ENGINEERING_STANDARDS.md](../docs/ENGINEERING_STANDARDS.md):

- [ ] Code is idiomatic for the stack; no dead paths, no commented-out code
- [ ] Unit/integration tests added or updated and green (`./mvnw verify`)
- [ ] Docs updated in the same PR (README section, OpenAPI, ADR if an architectural decision)
- [ ] Formatted (`./mvnw spotless:apply`); CI green
- [ ] Works in the local `docker compose` setup
- [ ] No secrets committed; `.env.example` still complete
- [ ] Conventional Commit messages, small and thematic
- [ ] Validator messages (if touched): German first, English second
