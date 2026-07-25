# Minification is disabled for release (see app/build.gradle.kts).
# JGit and Apache MINA SSHD resolve implementations via reflection and
# ServiceLoader, so shrinking would remove classes that are only referenced
# by name at runtime. This file is intentionally empty.
