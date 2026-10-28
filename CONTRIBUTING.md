# Contributing to Butterfly Atlas

Thank you for helping. A few rules keep the app simple, offline and reviewable:

- **Build and test** with the commands in the [README](README.md) ("Build and test"); a change comes with tests, and
  `./mvnw -B -ntp clean verify` passes before you open a pull request.
- **Keep pull requests small**, one topic each, with a description of what changes for someone using the app.
- **The app stays offline:** no network calls, no analytics, nothing sent anywhere.
- **All wording the app shows** lives in `ui/src/lib/messages.js`.
- **Licence:** contributions are accepted under the [Apache License 2.0](LICENSE), as its section 5 says; there is no
  separate agreement to sign.
- **`master` is protected:** every change arrives by pull request, its build must pass on Windows, macOS and Linux (it
  runs when the pull request is marked Ready for review), and the maintainer reviews and merges it.

Security problems are reported privately, see [SECURITY.md](SECURITY.md).
