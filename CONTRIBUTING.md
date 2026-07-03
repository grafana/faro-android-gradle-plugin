# Contributing to Faro Android Gradle Plugin

Thank you for your interest in contributing to the Faro Android Gradle Plugin!
This guide will help you get started with contributing to the project.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Making Changes](#making-changes)
- [Testing](#testing)
- [Pull Request Process](#pull-request-process)
- [Release Process](#release-process)

## Code of Conduct

This project follows the [Grafana Labs Code of Conduct](./CODE_OF_CONDUCT.md). By
participating, you are expected to uphold this code.

## Getting Started

1. Fork the repository on GitHub
2. Clone your fork locally
3. Set up the development environment
4. Create a branch for your changes
5. Make your changes
6. Submit a pull request

## Development Setup

### Prerequisites

- JDK 17+
- Gradle 8.10+ (or use the Gradle version pinned in [CI](.github/workflows/ci.yml))

### Initial Setup

```bash
# Clone your fork
git clone https://github.com/YOUR_USERNAME/faro-android-gradle-plugin.git
cd faro-android-gradle-plugin

# Add upstream remote
git remote add upstream https://github.com/grafana/faro-android-gradle-plugin.git

# Build and run tests
gradle build
```

To publish locally for integration testing in a consumer app:

```bash
gradle publishToMavenLocal -Pversion=0.1.0-SNAPSHOT
```

See [README.md](./README.md) for consumer `mavenLocal()` wiring.

## Making Changes

### Workflow

1. Create a new branch from `main`:

   ```bash
   git checkout -b feature/your-feature-name
   ```

2. Make your changes under `src/main/` and `src/test/`

3. Write tests for your changes

4. Ensure the build passes:

   ```bash
   gradle build
   ```

5. Commit your changes with a descriptive message

### Commit Message Guidelines

Follow conventional commit format:

```text
type(scope): subject

body (optional)

footer (optional)
```

Types:

- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, etc.)
- `refactor`: Code refactoring
- `test`: Adding or updating tests
- `chore`: Maintenance tasks

Examples:

```text
feat: pack native symbols when AGP omits debug-symbols zip

fix: skip upload when apiKey is missing

docs: clarify release build configuration
```

## Testing

### Running Tests

```bash
# Build and run all unit tests
gradle build

# Run tests only
gradle test
```

### Writing Tests

- Write unit tests for all new functionality
- Place test files under `src/test/kotlin/` mirroring the main source layout
- Use descriptive test names that explain what is being tested
- Follow the existing test patterns in the codebase

## Pull Request Process

1. Update your branch with the latest upstream changes:

   ```bash
   git fetch upstream
   git rebase upstream/main
   ```

2. Ensure the build passes locally

3. Push your changes to your fork:

   ```bash
   git push origin feature/your-feature-name
   ```

4. Create a pull request on GitHub:
   - Use a clear, descriptive title
   - Reference any related issues
   - Describe your changes and why they're needed
   - List any breaking changes

5. Wait for review:
   - Address any feedback from reviewers
   - Keep the PR updated with the main branch
   - Be patient and responsive

6. Once approved, a maintainer will merge your PR

### PR Checklist

- [ ] Code follows existing project patterns
- [ ] Tests added/updated and passing
- [ ] Documentation updated (if needed)
- [ ] Commit messages follow guidelines
- [ ] PR description is clear and complete
- [ ] No secrets committed (`apiKey`, tokens, or real stack credentials)

## Release Process

Releases publish to the [Gradle Plugin Portal](https://plugins.gradle.org) via
`.github/workflows/publish.yml` when a `v*` tag is pushed.

Maintainers tag releases after changes on `main` are ready to ship. The publish
workflow requires repository secrets `GRADLE_PUBLISH_KEY` and
`GRADLE_PUBLISH_SECRET`.

## Need Help?

- Check existing [issues](https://github.com/grafana/faro-android-gradle-plugin/issues)
- Review the [documentation](./README.md)
- For Grafana Cloud Frontend Observability setup, see the
  [product documentation](https://grafana.com/docs/grafana-cloud/monitor-applications/frontend-observability/)

## License

By contributing, you agree that your contributions will be licensed under the Apache
License 2.0.
