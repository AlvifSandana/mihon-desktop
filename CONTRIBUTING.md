# Contributing to Mihon Desktop

Thank you for your interest in contributing! This document provides guidelines and instructions for contributing.

## Getting Started

### Prerequisites

- **JDK 21+** (no Android SDK required)
- **Git**

### Development Setup

1. Fork the repository
2. Clone your fork:
   ```bash
   git clone https://github.com/your-username/mihon-desktop.git
   cd mihon-desktop
   ```
3. Build the project:
   ```bash
   ./gradlew build
   ```
4. Run the app:
   ```bash
   ./gradlew :app:run
   ```

## Development Guidelines

### Code Style

- Follow existing code style in the project
- Use Kotlin idioms and conventions
- Keep functions focused and small
- Add KDoc comments for public APIs

### Module Constraints

#### `platform-compat`

- **Never rename or repackage classes** — FQCNs are load-bearing
- Extension bytecode references these types by exact name
- Renaming breaks all extensions at runtime (not compile time)

#### `source-api`

- **Keep close to upstream Mihon** — this is the contract extension JARs were compiled against
- Only strip things that don't affect behavior:
  - Compose annotations (`@Stable`, `@androidx.compose.runtime`)
  - Kotlin context receivers (replace with explicit parameters)

#### `extension-loader`

- Mirror Mihon's own extension loading structure
- Document design decisions in KDoc
- Test with real extensions from keiyoushi

#### `app`

- Use Compose Desktop idioms
- Keep screens focused on single responsibilities
- Use `sealed interface Screen` for navigation

### Testing

- Write unit tests for new functionality
- Test with real extensions from keiyoushi
- Read stack traces for missing symbols when testing new extensions
- Add stubs to `platform-compat` as needed

```bash
./gradlew :extension-loader:test  # run unit tests
```

### Commit Messages

- Use clear, descriptive commit messages
- Start with a verb in imperative mood (e.g., "Add", "Fix", "Update")
- Reference issues when applicable (e.g., "Fix #123")

### Pull Requests

1. Create a feature branch from `main`
2. Make your changes
3. Add tests if applicable
4. Ensure all tests pass:
   ```bash
   ./gradlew build
   ```
5. Submit your pull request

## What to Contribute

### High Priority

- Missing Android stubs (when testing new extensions)
- Bug fixes
- Documentation improvements

### Medium Priority

- New features from the roadmap
- Performance improvements
- Test coverage

### Low Priority

- UI polish
- Refactoring
- Tooling improvements

## Reporting Issues

When reporting issues, please include:

1. Steps to reproduce
2. Expected behavior
3. Actual behavior
4. Stack trace (if applicable)
5. Extension being tested (if applicable)

## Code of Conduct

- Be respectful and inclusive
- Focus on constructive feedback
- Help others learn and grow

## Questions?

If you have questions about contributing, feel free to open an issue or reach out to the maintainers.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
