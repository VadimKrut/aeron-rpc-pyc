# Release Checklist

Use this checklist before publishing a new `rpc-core` release.

## 1. Versioning

- update `pom.xml`
- update dependency snippets in `README.md`
- confirm release branch name and intended tag

## 2. Validation

Run:

```bash
mvn -Dmaven.compiler.release=21 test
```

And, when benchmark packaging is part of the release:

```bash
mvn -Pbenchmarks -DskipTests package
```

## 3. Documentation Pass

Check:

- `README.md`
- `docs/JAVA_EXAMPLES.md`
- `docs/BENCHMARKS.md`
- `docs/PRODUCTION_GUIDE.md`
- feature-specific docs for the release

Make sure examples still read cleanly and code blocks did not get damaged by
formatting edits.

## 4. Startup-Time Layers

If the release changes startup-only tooling such as schema or metrics helpers:

- verify text report output
- verify JSON report output
- verify warnings are understandable
- confirm hot-path benchmark notes still describe the feature honestly

## 5. Performance Discipline

Before making latency claims:

- compare on the same OS and machine layout
- avoid mixing Windows and WSL numbers
- use the same benchmark command before and after
- look at `p50`, `p90`, `p99`, `p99.9`, and achieved rate
- prefer repeated clean runs over one dramatic number

## 6. Git / Release Flow

Typical flow:

1. commit release changes on the release branch
2. push the release branch
3. fast-forward merge into `master`
4. push `master`
5. create and push the release tag
6. publish release notes

## 7. Release Notes

Good release notes should answer:

- what was added
- what stayed optional
- what does not affect the hot path
- what was validated
- what operators or users should read next