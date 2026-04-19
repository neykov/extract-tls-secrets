# Releasing extract-tls-secrets

This project ships major versions only. Each release bumps `X.0.0` and the next
development cycle is `(X+1).0.0-SNAPSHOT`.

Set the version once at the top of the shell session and reuse:

    VERSION=5.0.0
    NEXT_SNAPSHOT=6.0.0-SNAPSHOT

## Prerequisites

- GPG key available for artifact signing.
- Sonatype Central Portal credentials present in `~/.m2/settings.xml`.
- Working tree clean, on `master`, up to date with `origin/master`.

## Phase 1 — Release branch and verification

    git checkout -b release-${VERSION}

Edit `pom.xml`: bump the current `*-SNAPSHOT` to `${VERSION}`, then commit:

    git commit -am "Release version ${VERSION}"

Run the full verification (Docker integration tests across Java 6, 8, 11, 17,
21, 25 and all TLS providers; spotless and checkstyle run as part of the
default lifecycle):

    mvn verify

## Phase 2 — Deploy and publish

Upload to Sonatype Central Portal staging:

    mvn clean deploy -P Release

Open the Central Portal UI, validate the staged deployment, and publish it.
The artifact appears on Maven Central within a few hours at:

    https://repo1.maven.org/maven2/name/neykov/extract-tls-secrets/${VERSION}/

## Phase 3 — Tag and GitHub release

Write the release notes for this version to `release-notes-${VERSION}.md`
(gitignored, kept locally only), then:

    git tag v${VERSION}
    git push origin release-${VERSION} v${VERSION}
    gh release create v${VERSION} \
        --title "extract-tls-secrets-${VERSION}" \
        --notes-file release-notes-${VERSION}.md

## Phase 4 — Cleanup and next development cycle

    git push origin --delete release-${VERSION}
    git branch -D release-${VERSION}
    git checkout master

Update `README.md` to reference `${VERSION}` in the download link and usage
examples. Edit `pom.xml` and bump the version to `${NEXT_SNAPSHOT}`. Commit
and push:

    git commit -am "Bump version, released ${VERSION}"
    git push origin master
