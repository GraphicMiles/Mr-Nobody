# Blocking Tests

The filter engine is covered by JVM unit tests at
`android/app/src/test/java/com/mrnobody/browser/blocking/BlocklistTest.java`,
run via `./gradlew testDebugUnitTest` (and in CI).

Covered behaviors:
- exact-domain and subdomain matching
- look-alike / unrelated domains do NOT match
- path rules (`||domain/path^`)
- wildcard rules
- comments, allowlist exceptions, and element-hiding rules are ignored
- Adblock-Plus `$options` are stripped (rule still blocks)
- case-insensitivity
