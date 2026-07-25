# Strohhalm Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Android app that keeps bare mirror clones of remote git repositories on device storage as offline backups, refreshed on a configurable schedule over SSH with a self-generated Ed25519 key.

**Architecture:** Layered as in the sibling project `stromschnelle` — `data/` (Room + DataStore), `domain/` (interfaces plus one implementation each), `work/` (WorkManager), `ui/` (Compose + Material 3). Three interfaces isolate all risk: `GitMirror` is the only file importing JGit, `SshKeyStore` the only file performing crypto, `HostKeyVerifier` is pure logic with no I/O. Dependency wiring is a manual `AppContainer` service locator, no DI framework.

**Tech Stack:** Kotlin 2.0, Jetpack Compose + Material 3, Room (KSP), DataStore Preferences, WorkManager, JGit + Apache MINA SSHD, net.i2p.crypto EdDSA.

## Global Constraints

- Package / namespace / applicationId: `de.nereide.strohhalm`
- `minSdk = 26`, `compileSdk = 35`, `targetSdk = 35`
- Java 17 source/target, `jvmTarget = "17"`
- `isMinifyEnabled = false` for release — JGit and MINA SSHD rely on reflection and `ServiceLoader`, so this avoids all ProGuard keep rules. Do not enable minification.
- `versionName` lives only in `version.properties`; `versionCode` is derived as `major * 10000 + minor * 100 + patch`, with minor and patch each `< 100`.
- Release signing reads optional `app/keystore.properties`; when absent the release build must still succeed, producing an unsigned APK.
- `MIN_FREE_BYTES = 250L * 1024 * 1024` (250 MB) — the free-space floor before a sync is attempted.
- `SyncInterval` values are exactly: `MANUAL, M15, M30, H1, H3, H6, H12, D1`. WorkManager's periodic floor is 15 minutes; no value below it may exist.
- The `SyncWorker` is registered with `Constraints.NONE`. Constraints defer work silently, which contradicts the requirement to notify the user when a sync cannot run. All condition checks happen inside the worker.
- Data flows remote → device only. No code may push, commit, or otherwise write to a remote.
- The private key is stored in **internal** storage (`filesDir`), never in the user-chosen mirror directory.
- UI strings live in `res/values/strings.xml`, in English. No hardcoded user-facing strings in Compose code.
- Unit tests are pure JVM (`app/src/test`) unless they need the Android Keystore, Room, or the framework, in which case they are instrumented (`app/src/androidTest`).

---

## File Structure

| File | Responsibility |
| --- | --- |
| `app/src/main/java/de/nereide/strohhalm/StrohhalmApp.kt` | Application, builds `AppContainer`, schedules sync |
| `.../AppContainer.kt` | Manual service locator |
| `.../MainActivity.kt` | Single activity, Compose host |
| `.../data/Repo.kt` | Room entity + `SyncStatus` enum |
| `.../data/RepoDao.kt` | Room DAO |
| `.../data/StrohhalmDatabase.kt` | Room database singleton |
| `.../data/RepoSlug.kt` | Pure slug derivation and collision suffixing |
| `.../data/SettingsRepository.kt` | DataStore-backed settings |
| `.../data/SyncInterval.kt` | Interval enum ↔ `Duration` |
| `.../domain/RepoRepository.kt`, `DefaultRepoRepository.kt` | Repo CRUD + sync bookkeeping |
| `.../domain/GitMirror.kt`, `JGitMirror.kt` | Clone/fetch/prune/measure; the only JGit importer |
| `.../domain/SyncError.kt` | Sealed error taxonomy |
| `.../domain/SshKeyStore.kt`, `EncryptedSshKeyStore.kt` | Key generation and encrypted persistence |
| `.../domain/KeystoreCipher.kt` | AES-256-GCM under an AndroidKeyStore key |
| `.../domain/OpenSshPublicKey.kt` | Pure OpenSSH public-key line encoding |
| `.../domain/HostKeyVerifier.kt` | Pure TOFU decision logic |
| `.../domain/AndroidSystemReader.kt` | JGit `SystemReader` shim (no user/system gitconfig) |
| `.../domain/StorageRootResolver.kt` | Tree-URI document id → real path, pure |
| `.../work/SyncWorker.kt` | The periodic worker |
| `.../work/SyncScheduler.kt` | Enqueue / cancel periodic work |
| `.../work/SyncNotifier.kt` | Channels, progress and failure notifications |
| `.../ui/**` | Theme, nav, onboarding, list, add, detail, settings |

---

## Task 1: Project scaffold

A buildable, launchable app with no features. Copied from `stromschnelle` so every later task lands in a familiar structure.

> **Sequencing note:** the spec lists the spike first. It is second here because the spike needs a buildable app to run on a device. Scaffolding is low-risk boilerplate, so this ordering costs nothing and makes the spike executable as an instrumented test.

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `version.properties`, `.gitignore`
- Create: `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/de/nereide/strohhalm/{StrohhalmApp,AppContainer,MainActivity}.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/ui/theme/{Color,Type,Theme}.kt`
- Create: `app/src/main/res/values/{strings,colors,themes}.xml`
- Create: `app/src/main/res/xml/{backup_rules,data_extraction_rules}.xml`

**Interfaces:**
- Consumes: nothing.
- Produces: `StrohhalmApp` with `val container: AppContainer`, reachable as `(context.applicationContext as StrohhalmApp).container`. `StrohhalmTheme(content: @Composable () -> Unit)`.

- [ ] **Step 1: Copy the wrapper and root build files from stromschnelle**

```bash
cd /home/christoph/Projects/strohhalm
cp -r /home/christoph/Projects/stromschnelle/gradle .
cp /home/christoph/Projects/stromschnelle/gradlew /home/christoph/Projects/stromschnelle/gradlew.bat .
cp /home/christoph/Projects/stromschnelle/gradle.properties .
cp /home/christoph/Projects/stromschnelle/.gitignore .
cp /home/christoph/Projects/stromschnelle/build.gradle.kts .
chmod +x gradlew
rm -rf gradle/../.gradle
```

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Strohhalm"
include(":app")
```

- [ ] **Step 3: Write `version.properties`**

```properties
# Single source of truth for the app version.
# versionName is the only value maintained by hand.
# The integer versionCode is DERIVED in app/build.gradle.kts as:
#   major*10000 + minor*100 + patch   (minor and patch must stay < 100)
versionName=0.1.0
```

- [ ] **Step 4: Trim `gradle/libs.versions.toml` to what Strohhalm needs**

Remove the Glance and sqlite-jdbc entries inherited from `stromschnelle`; keep the rest. JGit and EdDSA are added in Task 2 once the spike fixes their versions.

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
composeBom = "2024.12.01"
room = "2.6.1"
work = "2.9.1"
datastore = "1.1.1"
navigationCompose = "2.8.4"
lifecycleViewmodelCompose = "2.8.7"
activityCompose = "1.9.3"
coreKtx = "1.15.0"
material = "1.12.0"
turbine = "1.1.0"
junit = "4.13.2"
coroutinesTest = "1.9.0"
androidxTestRunner = "1.6.2"
androidxTestExtJunit = "1.2.1"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
material = { group = "com.google.android.material", name = "material", version.ref = "material" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleViewmodelCompose" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }

androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }

androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }

androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }

turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutinesTest" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExtJunit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 5: Write `app/build.gradle.kts`**

Identical in structure to `stromschnelle`'s, with the namespace changed and Glance dropped.

```kotlin
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Single source of truth: version.properties holds only versionName=x.y.z.
// versionCode is derived so it always increases as long as x.y.z increases.
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersionName: String = versionProps.getProperty("versionName")
    ?: error("versionName missing from version.properties")
val appVersionCode: Int = appVersionName.trim().split(".").let { parts ->
    require(parts.size == 3) { "versionName must be x.y.z, got '$appVersionName'" }
    val (major, minor, patch) = parts.map {
        it.toIntOrNull() ?: error("versionName component '$it' is not an integer")
    }
    require(minor in 0..99 && patch in 0..99) {
        "minor and patch must each be < 100 (got $appVersionName)"
    }
    major * 10000 + minor * 100 + patch
}

// Optional release signing: present only when app/keystore.properties exists
// (kept out of git). Without it, assembleRelease produces an unsigned APK.
val keystoreProps = Properties().apply {
    val f = rootProject.file("app/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "de.nereide.strohhalm"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.nereide.strohhalm"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Must stay false: JGit and MINA SSHD rely on reflection and
            // ServiceLoader, which R8 would strip.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // JGit and MINA SSHD ship duplicate service/licence metadata.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

// Room exports the schema JSON here so MigrationTestHelper can read it.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
```

- [ ] **Step 6: Write `app/proguard-rules.pro`**

```proguard
# Minification is disabled for release (see app/build.gradle.kts).
# JGit and Apache MINA SSHD resolve implementations via reflection and
# ServiceLoader, so shrinking would remove classes that are only referenced
# by name at runtime. This file is intentionally empty.
```

- [ ] **Step 7: Write `app/src/main/AndroidManifest.xml`**

Permissions are declared now so later tasks need not revisit the manifest.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission
        android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
        tools:ignore="ScopedStorage" />

    <application
        android:name=".StrohhalmApp"
        android:allowBackup="false"
        android:fullBackupContent="@xml/backup_rules"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Strohhalm">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:theme="@style/Theme.Strohhalm">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
```

`android:allowBackup="false"` is deliberate: the encrypted private key lives in `filesDir`, and its AndroidKeyStore key cannot leave the device, so a restored backup would contain an undecryptable blob.

- [ ] **Step 8: Copy the icon, theme and backup-rule resources**

```bash
cd /home/christoph/Projects/strohhalm
mkdir -p app/src/main/res
cp -r /home/christoph/Projects/stromschnelle/app/src/main/res/mipmap-anydpi-v26 app/src/main/res/
cp -r /home/christoph/Projects/stromschnelle/app/src/main/res/drawable app/src/main/res/
mkdir -p app/src/main/res/values app/src/main/res/xml
cp /home/christoph/Projects/stromschnelle/app/src/main/res/values/colors.xml app/src/main/res/values/
cp /home/christoph/Projects/stromschnelle/app/src/main/res/xml/backup_rules.xml app/src/main/res/xml/
cp /home/christoph/Projects/stromschnelle/app/src/main/res/xml/data_extraction_rules.xml app/src/main/res/xml/
```

Then in `app/src/main/res/values/themes.xml`, replace every occurrence of `Theme.Stromschnelle` with `Theme.Strohhalm`:

```bash
sed -e 's/Stromschnelle/Strohhalm/g' \
    /home/christoph/Projects/stromschnelle/app/src/main/res/values/themes.xml \
    > app/src/main/res/values/themes.xml
```

- [ ] **Step 9: Write `app/src/main/res/values/strings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Strohhalm</string>

    <!-- Common -->
    <string name="cancel">Cancel</string>
    <string name="save">Save</string>
    <string name="delete">Delete</string>
    <string name="ok">OK</string>
    <string name="back">Back</string>
    <string name="close">Close</string>
</resources>
```

- [ ] **Step 10: Copy the theme sources and rename**

```bash
cd /home/christoph/Projects/strohhalm
mkdir -p app/src/main/java/de/nereide/strohhalm/ui/theme
for f in Color Type Theme; do
  sed -e 's/stromschnelle/strohhalm/g' -e 's/Stromschnelle/Strohhalm/g' \
    /home/christoph/Projects/stromschnelle/app/src/main/java/de/nereide/stromschnelle/ui/theme/$f.kt \
    > app/src/main/java/de/nereide/strohhalm/ui/theme/$f.kt
done
```

This yields `StrohhalmTheme` in package `de.nereide.strohhalm.ui.theme`.

- [ ] **Step 11: Write `AppContainer.kt`**

Starts nearly empty; each later task adds one property.

```kotlin
package de.nereide.strohhalm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Service-locator container exposing the app's singletons. No Hilt. */
interface AppContainer {
    /**
     * Scope living as long as the process — used for fire-and-forget work that
     * must not die with a ViewModel or screen.
     */
    val applicationScope: CoroutineScope
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings"
)

class DefaultAppContainer(context: Context) : AppContainer {

    private val appContext = context.applicationContext

    override val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
```

- [ ] **Step 12: Write `StrohhalmApp.kt`**

```kotlin
package de.nereide.strohhalm

import android.app.Application

/**
 * Application entry point. Builds the [AppContainer] which workers and screens
 * reach via `(context.applicationContext as StrohhalmApp).container`.
 */
class StrohhalmApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
```

- [ ] **Step 13: Write `MainActivity.kt`**

```kotlin
package de.nereide.strohhalm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import de.nereide.strohhalm.ui.theme.StrohhalmTheme

/** Single-activity host for the Compose navigation graph. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StrohhalmTheme {
                Surface {
                    Text("Strohhalm")
                }
            }
        }
    }
}
```

- [ ] **Step 14: Build and verify it assembles**

Run: `cd /home/christoph/Projects/strohhalm && ./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. If the SDK location is unknown, create `local.properties` with `sdk.dir=/home/christoph/Android/Sdk`.

- [ ] **Step 15: Install and confirm it launches**

Run: `./gradlew installDebug && adb shell am start -n de.nereide.strohhalm/.MainActivity`
Expected: the device shows a screen reading "Strohhalm".

- [ ] **Step 16: Commit**

```bash
cd /home/christoph/Projects/strohhalm
git add -A
git commit -m "feat: project scaffold copied from stromschnelle conventions"
```

---

## Task 2: Spike — JGit + MINA SSHD on Android

Throwaway verification that the chosen engine works on device, before anything is built on top of it. Two artefacts survive: the pinned dependency versions, and `AndroidSystemReader`.

**Files:**
- Modify: `gradle/libs.versions.toml` (add jgit, sshd, eddsa)
- Modify: `app/build.gradle.kts` (add the dependencies)
- Create: `app/src/main/java/de/nereide/strohhalm/domain/AndroidSystemReader.kt`
- Create (throwaway): `app/src/androidTest/java/de/nereide/strohhalm/SpikeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `AndroidSystemReader.install()` — idempotent, must be called once before any JGit use. Confirmed version strings in `libs.versions.toml`. Confirmed API shapes for `SshdSessionFactoryBuilder`, which Task 5 depends on.

- [ ] **Step 1: Resolve the available JGit versions**

Do not guess a version. List what actually exists:

```bash
curl -s https://repo1.maven.org/maven2/org/eclipse/jgit/org.eclipse.jgit/maven-metadata.xml \
  | grep -o '<version>[^<]*</version>' | tail -30
```

Pick the newest `6.x` release (not a `.202xxx-m` milestone, not `7.x` — JGit 7 targets Java 17 bytecode features that AGP 8.7 desugaring on `minSdk 26` has not been verified against here). Record the exact string.

Do the same for the SSH transport, which must match the JGit version exactly:

```bash
curl -s https://repo1.maven.org/maven2/org/eclipse/jgit/org.eclipse.jgit.ssh.apache/maven-metadata.xml \
  | grep -o '<version>[^<]*</version>' | tail -30
```

- [ ] **Step 2: Add the dependencies to `libs.versions.toml`**

Substitute the version resolved in Step 1 for `<JGIT_VERSION>`.

```toml
# add to [versions]
jgit = "<JGIT_VERSION>"
eddsa = "0.3.0"

# add to [libraries]
jgit = { group = "org.eclipse.jgit", name = "org.eclipse.jgit", version.ref = "jgit" }
jgit-ssh-apache = { group = "org.eclipse.jgit", name = "org.eclipse.jgit.ssh.apache", version.ref = "jgit" }
eddsa = { group = "net.i2p.crypto", name = "eddsa", version.ref = "eddsa" }
```

And in `app/build.gradle.kts` dependencies:

```kotlin
    implementation(libs.jgit)
    implementation(libs.jgit.ssh.apache)
    implementation(libs.eddsa)
```

- [ ] **Step 3: Verify it builds and the method count survives**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. If it fails with a duplicate `META-INF` resource, extend the `packaging.resources.excludes` block from Task 1. If it fails on missing `java.*` classes, drop to the next-oldest JGit 6.x and repeat.

- [ ] **Step 4: Write `AndroidSystemReader.kt`**

JGit reads `~/.gitconfig`, `/etc/gitconfig` and a JGit-specific config at startup. On Android there is no user home, and those lookups throw or hang. This replaces them with empty, never-outdated configs.

```kotlin
package de.nereide.strohhalm.domain

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader

/**
 * JGit consults `~/.gitconfig`, `/etc/gitconfig` and a JGit-wide config on first
 * use. Android has no user home and no such files, and the default lookups fail
 * or block. This reader delegates everything else to the platform default but
 * serves empty, permanently up-to-date configs instead.
 *
 * [install] must run once before any JGit API is touched.
 */
class AndroidSystemReader(private val delegate: SystemReader) : SystemReader() {

    override fun getHostname(): String = "android"

    override fun getenv(variable: String?): String? = delegate.getenv(variable)

    override fun getProperty(key: String?): String? = delegate.getProperty(key)

    override fun getCurrentTime(): Long = delegate.currentTime

    override fun getTimezone(whenMillis: Long): Int = delegate.getTimezone(whenMillis)

    override fun openUserConfig(parent: Config?, fs: FS?): FileBasedConfig =
        EmptyConfig(parent, fs)

    override fun openSystemConfig(parent: Config?, fs: FS?): FileBasedConfig =
        EmptyConfig(parent, fs)

    override fun openJGitConfig(parent: Config?, fs: FS?): FileBasedConfig =
        EmptyConfig(parent, fs)

    private class EmptyConfig(parent: Config?, fs: FS?) : FileBasedConfig(parent, null, fs) {
        override fun load() = Unit
        override fun isOutdated(): Boolean = false
        override fun save() = Unit
    }

    companion object {
        @Volatile
        private var installed = false

        /** Idempotent; safe to call from both the worker and the UI process path. */
        @Synchronized
        fun install() {
            if (installed) return
            SystemReader.setInstance(AndroidSystemReader(SystemReader.getInstance()))
            installed = true
        }
    }
}
```

If the compiler reports further abstract members (the `SystemReader` API gained methods across JGit versions), implement each by delegating to `delegate`. Do not leave any of them throwing.

- [ ] **Step 5: Write the throwaway spike test**

Replace `SPIKE_REMOTE` with a real repository you control, and add the public key the test prints to that server before the second run.

```kotlin
package de.nereide.strohhalm

import androidx.test.platform.app.InstrumentationRegistry
import de.nereide.strohhalm.domain.AndroidSystemReader
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.KeyPairGenerator
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.Base64

class SpikeTest {

    private val remote = "ssh://git@example.invalid:22/srv/git/spike.git" // SPIKE_REMOTE

    @Test
    fun mirrorClonesOverSsh() {
        AndroidSystemReader.install()

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(ctx.cacheDir, "spike").apply { deleteRecursively(); mkdirs() }

        // 1. Generate an Ed25519 keypair and print the public key in OpenSSH form.
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val gen = KeyPairGenerator().apply { initialize(spec, SecureRandom()) }
        val kp = gen.generateKeyPair()
        val raw = (kp.public as EdDSAPublicKey).abyte
        val blob = sshString("ssh-ed25519".toByteArray()) + sshString(raw)
        println("SPIKE PUBKEY: ssh-ed25519 ${Base64.getEncoder().encodeToString(blob)} spike")
        assertTrue((kp.private as EdDSAPrivateKey).seed.size == 32)

        // 2. Build a session factory that offers only that key and trusts any host.
        val factory = SshdSessionFactoryBuilder()
            .setPreferredAuthentications("publickey")
            .setHomeDirectory(work)
            .setSshDirectory(File(work, "ssh").apply { mkdirs() })
            .setDefaultKeysProvider { listOf(kp) }
            .setServerKeyDatabase { _, _ ->
                object : org.eclipse.jgit.transport.sshd.ServerKeyDatabase {
                    override fun lookup(
                        connectAddress: String?,
                        remoteAddress: java.net.InetSocketAddress?,
                        config: org.eclipse.jgit.transport.sshd.ServerKeyDatabase.Configuration?
                    ): List<java.security.PublicKey> = emptyList()

                    override fun accept(
                        connectAddress: String?,
                        remoteAddress: java.net.InetSocketAddress?,
                        serverKey: java.security.PublicKey?,
                        config: org.eclipse.jgit.transport.sshd.ServerKeyDatabase.Configuration?,
                        provider: org.eclipse.jgit.transport.CredentialsProvider?
                    ): Boolean {
                        println("SPIKE HOSTKEY: ${serverKey?.algorithm}")
                        return true
                    }
                }
            }
            .build(null)
        SshSessionFactory.setInstance(factory)

        // 3. Mirror clone.
        val dest = File(work, "spike.git")
        Git.cloneRepository()
            .setURI(remote)
            .setDirectory(dest)
            .setBare(true)
            .setMirror(true)
            .call()
            .close()

        assertTrue(File(dest, "HEAD").isFile)
        assertTrue(File(dest, "objects").isDirectory)
    }

    private fun sshString(b: ByteArray): ByteArray {
        val len = byteArrayOf(
            (b.size ushr 24).toByte(), (b.size ushr 16).toByte(),
            (b.size ushr 8).toByte(), b.size.toByte()
        )
        return len + b
    }
}
```

- [ ] **Step 6: Run the spike and read the log**

Run: `./gradlew connectedDebugAndroidTest --tests '*SpikeTest*'`

First run is expected to FAIL at authentication. Copy the printed key:

```bash
adb logcat -d | grep 'SPIKE PUBKEY'
```

Add that line to the server's `authorized_keys`, then re-run.
Expected on the second run: PASS, with `SPIKE HOSTKEY: EdDSA` (or `RSA`) in the log.

- [ ] **Step 7: Record the findings**

Append to `docs/superpowers/specs/2026-07-25-strohhalm-design.md` under a new `## Spike results` heading: the JGit version, the EdDSA version, whether `setMirror(true)` was accepted, and the exact `ServerKeyDatabase` method signatures the compiler required. Task 5 depends on these being written down.

- [ ] **Step 8: Delete the spike test and commit**

```bash
cd /home/christoph/Projects/strohhalm
rm app/src/androidTest/java/de/nereide/strohhalm/SpikeTest.kt
git add -A
git commit -m "feat(domain): pin JGit/SSHD versions and add Android SystemReader shim"
```

The spike has done its job; keeping it would leave a test that needs a live server and a hand-installed key.

---

## Task 3: SSH key generation and encrypted storage

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/OpenSshPublicKey.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/domain/KeystoreCipher.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/domain/SshKeyStore.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/domain/EncryptedSshKeyStore.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/OpenSshPublicKeyTest.kt`
- Test: `app/src/androidTest/java/de/nereide/strohhalm/domain/EncryptedSshKeyStoreTest.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/AppContainer.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `OpenSshPublicKey.encode(rawEd25519: ByteArray, comment: String): String`
  - `KeystoreCipher(alias: String)` with `encrypt(ByteArray): ByteArray`, `decrypt(ByteArray): ByteArray`
  - `interface SshKeyStore` with `suspend fun keyPair(): KeyPair`, `suspend fun publicKeyLine(): String`, `suspend fun regenerate(): KeyPair`, `fun hasKey(): Boolean`
  - `AppContainer.sshKeyStore: SshKeyStore`

Only the 32-byte Ed25519 seed is persisted, not an OpenSSH PEM. The seed fully determines the key pair, we are its only consumer, and this removes the need to implement OpenSSH's bcrypt-KDF private key container. The consequence — the private key cannot be exported to another device — is accepted; the spec does not require it.

- [ ] **Step 1: Write the failing test for the public key encoder**

```kotlin
package de.nereide.strohhalm.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class OpenSshPublicKeyTest {

    private val raw = ByteArray(32) { it.toByte() }

    @Test
    fun `starts with the algorithm name and ends with the comment`() {
        val line = OpenSshPublicKey.encode(raw, "strohhalm@pixel")

        assertEquals(3, line.split(" ").size)
        assertEquals("ssh-ed25519", line.split(" ")[0])
        assertEquals("strohhalm@pixel", line.split(" ")[2])
    }

    @Test
    fun `body decodes to the ssh wire format of the key`() {
        val body = OpenSshPublicKey.encode(raw, "c").split(" ")[1]
        val blob = Base64.getDecoder().decode(body)

        // uint32 len | "ssh-ed25519" | uint32 32 | 32 raw bytes
        assertEquals(4 + 11 + 4 + 32, blob.size)
        assertEquals(11, readUint32(blob, 0))
        assertEquals("ssh-ed25519", String(blob, 4, 11))
        assertEquals(32, readUint32(blob, 15))
        assertArrayEquals(raw, blob.copyOfRange(19, 51))
    }

    @Test
    fun `rejects a key that is not 32 bytes`() {
        val e = runCatching { OpenSshPublicKey.encode(ByteArray(31), "c") }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, e!!::class.java)
    }

    private fun readUint32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or
            ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or
            (b[off + 3].toInt() and 0xff)
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*OpenSshPublicKeyTest*'`
Expected: FAIL — unresolved reference `OpenSshPublicKey`.

- [ ] **Step 3: Implement the encoder**

```kotlin
package de.nereide.strohhalm.domain

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Encodes a raw Ed25519 public key as a single OpenSSH `authorized_keys` line.
 *
 * The wire format is a sequence of length-prefixed strings — a big-endian
 * uint32 length followed by that many bytes — here the algorithm name followed
 * by the 32-byte key. This is the only external format Strohhalm must produce,
 * which is why it is hand-rolled and unit-tested rather than delegated.
 */
object OpenSshPublicKey {

    private const val ALGORITHM = "ssh-ed25519"
    private const val ED25519_KEY_BYTES = 32

    fun encode(rawEd25519: ByteArray, comment: String): String {
        require(rawEd25519.size == ED25519_KEY_BYTES) {
            "an Ed25519 public key is $ED25519_KEY_BYTES bytes, got ${rawEd25519.size}"
        }
        val blob = ByteArrayOutputStream().apply {
            writeSshString(ALGORITHM.toByteArray(Charsets.US_ASCII))
            writeSshString(rawEd25519)
        }.toByteArray()
        return "$ALGORITHM ${Base64.getEncoder().encodeToString(blob)} $comment"
    }

    private fun ByteArrayOutputStream.writeSshString(value: ByteArray) {
        write(value.size ushr 24)
        write(value.size ushr 16)
        write(value.size ushr 8)
        write(value.size)
        write(value)
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*OpenSshPublicKeyTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/OpenSshPublicKey.kt \
        app/src/test/java/de/nereide/strohhalm/domain/OpenSshPublicKeyTest.kt
git commit -m "feat(domain): encode Ed25519 public keys in OpenSSH format"
```

- [ ] **Step 6: Write `KeystoreCipher.kt`**

```kotlin
package de.nereide.strohhalm.domain

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption under a key held in the AndroidKeyStore, which never
 * leaves the device. The ciphertext layout is `IV (12 bytes) || ciphertext+tag`.
 *
 * Written by hand rather than using `androidx.security:security-crypto`, which
 * has remained in alpha and unmaintained. This is ~40 lines with no dependency
 * and no deprecation risk.
 */
class KeystoreCipher(private val alias: String) {

    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(plaintext)
        return cipher.iv + body
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "ciphertext too short to contain an IV" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES)
        )
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
```

- [ ] **Step 7: Write `SshKeyStore.kt`**

```kotlin
package de.nereide.strohhalm.domain

import java.security.KeyPair

/**
 * Owns the app's single SSH identity. Implementations are the only place in the
 * app that touch key material.
 */
interface SshKeyStore {

    /** True when a key already exists, without generating one as a side effect. */
    fun hasKey(): Boolean

    /** Returns the key pair, generating and persisting one on first call. */
    suspend fun keyPair(): KeyPair

    /** The OpenSSH `authorized_keys` line for the public key. */
    suspend fun publicKeyLine(): String

    /** Discards the existing key and generates a fresh one. */
    suspend fun regenerate(): KeyPair
}
```

- [ ] **Step 8: Write `EncryptedSshKeyStore.kt`**

```kotlin
package de.nereide.strohhalm.domain

import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.KeyPairGenerator
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.io.File
import java.security.KeyPair
import java.security.SecureRandom

/**
 * Stores the Ed25519 *seed* — 32 bytes that fully determine the key pair —
 * encrypted with [KeystoreCipher], inside internal storage.
 *
 * Internal storage is deliberate: the mirror directory the user picks is by
 * design browsable, copied off-device and swept up by other backup tools, which
 * makes it the worst possible home for a private key.
 */
class EncryptedSshKeyStore(
    filesDir: File,
    private val cipher: KeystoreCipher = KeystoreCipher(KEY_ALIAS),
    private val comment: String = "strohhalm@${Build.MODEL ?: "android"}",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SshKeyStore {

    private val keyFile = File(File(filesDir, "ssh").apply { mkdirs() }, "id_ed25519.seed.enc")
    private val mutex = Mutex()

    override fun hasKey(): Boolean = keyFile.isFile

    override suspend fun keyPair(): KeyPair = mutex.withLock {
        withContext(io) { loadOrCreate() }
    }

    override suspend fun publicKeyLine(): String {
        val pair = keyPair()
        return OpenSshPublicKey.encode((pair.public as EdDSAPublicKey).abyte, comment)
    }

    override suspend fun regenerate(): KeyPair = mutex.withLock {
        withContext(io) {
            keyFile.delete()
            loadOrCreate()
        }
    }

    private fun loadOrCreate(): KeyPair {
        if (keyFile.isFile) {
            return fromSeed(cipher.decrypt(keyFile.readBytes()))
        }
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val generated = KeyPairGenerator()
            .apply { initialize(spec, SecureRandom()) }
            .generateKeyPair()
        val seed = (generated.private as EdDSAPrivateKey).seed
        // Write via a temp file so an interrupted write cannot leave a truncated
        // seed that would decrypt to a key the server has never seen.
        val tmp = File(keyFile.parentFile, keyFile.name + ".tmp")
        tmp.writeBytes(cipher.encrypt(seed))
        check(tmp.renameTo(keyFile)) { "could not persist the SSH key" }
        return generated
    }

    private fun fromSeed(seed: ByteArray): KeyPair {
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val privateSpec = EdDSAPrivateKeySpec(seed, spec)
        return KeyPair(
            EdDSAPublicKey(EdDSAPublicKeySpec(privateSpec.a, spec)),
            EdDSAPrivateKey(privateSpec)
        )
    }

    private companion object {
        const val KEY_ALIAS = "strohhalm.sshkey.v1"
    }
}
```

- [ ] **Step 9: Write the instrumented test**

The AndroidKeyStore is unavailable on the JVM, so this test must be instrumented.

```kotlin
package de.nereide.strohhalm.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EncryptedSshKeyStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(ctx.cacheDir, "keystore-test").apply { deleteRecursively(); mkdirs() }
    }

    @Test
    fun generatesOnFirstUseAndReloadsTheSameKey() = runBlocking {
        val store = EncryptedSshKeyStore(dir)
        assertFalse(store.hasKey())

        val first = store.keyPair()
        assertTrue(store.hasKey())

        // A fresh instance must decrypt the persisted seed to an identical key.
        val second = EncryptedSshKeyStore(dir).keyPair()
        assertArrayEquals(
            (first.private as EdDSAPrivateKey).seed,
            (second.private as EdDSAPrivateKey).seed
        )
    }

    @Test
    fun publicKeyLineIsStableAcrossInstances() = runBlocking {
        val line = EncryptedSshKeyStore(dir).publicKeyLine()
        assertTrue(line.startsWith("ssh-ed25519 "))
        assertEquals(line, EncryptedSshKeyStore(dir).publicKeyLine())
    }

    @Test
    fun regenerateProducesADifferentKey() = runBlocking {
        val store = EncryptedSshKeyStore(dir)
        val before = store.publicKeyLine()
        val after = store.regenerate()

        assertTrue(before != OpenSshPublicKey.encode(
            (after.public as net.i2p.crypto.eddsa.EdDSAPublicKey).abyte,
            "strohhalm@${android.os.Build.MODEL ?: "android"}"
        ))
    }

    @Test
    fun storedSeedIsNotPlaintext() = runBlocking {
        val pair = EncryptedSshKeyStore(dir).keyPair()
        val onDisk = File(dir, "ssh/id_ed25519.seed.enc").readBytes()
        val seed = (pair.private as EdDSAPrivateKey).seed

        // The raw seed must not appear anywhere in the file.
        val hit = (0..onDisk.size - seed.size).any { i ->
            onDisk.copyOfRange(i, i + seed.size).contentEquals(seed)
        }
        assertFalse("the seed was written in plaintext", hit)
    }
}
```

- [ ] **Step 10: Run the instrumented tests**

Run: `./gradlew connectedDebugAndroidTest --tests '*EncryptedSshKeyStoreTest*'`
Expected: PASS, 4 tests. A connected device or emulator is required.

- [ ] **Step 11: Expose it on the container**

In `AppContainer.kt`, add to the interface:

```kotlin
    val sshKeyStore: SshKeyStore
```

and to `DefaultAppContainer`:

```kotlin
    override val sshKeyStore: SshKeyStore by lazy {
        EncryptedSshKeyStore(appContext.filesDir)
    }
```

with imports `de.nereide.strohhalm.domain.EncryptedSshKeyStore` and `de.nereide.strohhalm.domain.SshKeyStore`.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "feat(domain): generate and store an Ed25519 identity encrypted at rest"
```

---

## Task 4: Host key verification (TOFU)

Pure decision logic, no I/O and no framework, so every branch — including the mismatch that matters most — is a plain JVM test.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/HostKeyVerifier.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/HostKeyVerifierTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `HostKeyVerifier.verify(stored: String?, presented: String): HostKeyDecision`, where `HostKeyDecision` is a sealed interface with objects `FirstUse`, `Trusted` and `Mismatch(val stored: String, val presented: String)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostKeyVerifierTest {

    private val a = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val b = "SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"

    @Test
    fun `no pinned fingerprint means first use`() {
        assertEquals(HostKeyDecision.FirstUse, HostKeyVerifier.verify(stored = null, presented = a))
    }

    @Test
    fun `a blank pinned fingerprint is also first use`() {
        assertEquals(HostKeyDecision.FirstUse, HostKeyVerifier.verify(stored = "  ", presented = a))
    }

    @Test
    fun `matching fingerprints are trusted`() {
        assertEquals(HostKeyDecision.Trusted, HostKeyVerifier.verify(stored = a, presented = a))
    }

    @Test
    fun `surrounding whitespace does not defeat a match`() {
        assertEquals(HostKeyDecision.Trusted, HostKeyVerifier.verify(stored = " $a ", presented = a))
    }

    @Test
    fun `a different fingerprint is a mismatch carrying both values`() {
        val decision = HostKeyVerifier.verify(stored = a, presented = b)

        assertTrue(decision is HostKeyDecision.Mismatch)
        assertEquals(a, (decision as HostKeyDecision.Mismatch).stored)
        assertEquals(b, decision.presented)
    }

    @Test
    fun `comparison is case sensitive because base64 is`() {
        val lower = a.lowercase()
        assertTrue(HostKeyVerifier.verify(stored = a, presented = lower) is HostKeyDecision.Mismatch)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*HostKeyVerifierTest*'`
Expected: FAIL — unresolved reference `HostKeyVerifier`.

- [ ] **Step 3: Implement it**

```kotlin
package de.nereide.strohhalm.domain

/** The outcome of comparing a server's key against what was pinned for it. */
sealed interface HostKeyDecision {

    /** Nothing pinned yet — the user must be shown the fingerprint and asked. */
    data object FirstUse : HostKeyDecision

    /** The presented key matches the pinned one. */
    data object Trusted : HostKeyDecision

    /**
     * The server presented a different key than the one pinned. This may mean the
     * server was rebuilt, or that something is intercepting the connection, so it
     * is surfaced distinctly rather than as a generic failure.
     */
    data class Mismatch(val stored: String, val presented: String) : HostKeyDecision
}

/**
 * Trust-on-first-use host key policy, reduced to a pure comparison so that every
 * branch is unit-testable without a network or a server.
 *
 * Fingerprints are OpenSSH-style `SHA256:<base64>` strings. Comparison is exact
 * apart from surrounding whitespace: base64 is case-sensitive, so lowercasing
 * would make distinct keys compare equal.
 */
object HostKeyVerifier {

    fun verify(stored: String?, presented: String): HostKeyDecision {
        val pinned = stored?.trim()
        if (pinned.isNullOrEmpty()) return HostKeyDecision.FirstUse
        return if (pinned == presented.trim()) {
            HostKeyDecision.Trusted
        } else {
            HostKeyDecision.Mismatch(stored = pinned, presented = presented.trim())
        }
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*HostKeyVerifierTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/HostKeyVerifier.kt \
        app/src/test/java/de/nereide/strohhalm/domain/HostKeyVerifierTest.kt
git commit -m "feat(domain): trust-on-first-use host key policy"
```

---

## Task 5: Error taxonomy and the mirror engine

The only task that imports JGit into production code. Mirror semantics are verified against a `file://` remote, so the backup guarantee is tested without a server, a network or SSH.

> **Two refinements of the spec, both deliberate:**
> 1. The spec stores a single `lastError: String?`. This task splits it into
>    `lastErrorCode` and `lastErrorDetail` so the domain layer stays free of
>    Android string resources and the UI can localise messages. Task 6 uses the
>    split fields.
> 2. The spec describes `SyncError` as a sealed class with one case per failure.
>    It is implemented as an `enum` code plus a `data class` carrying an optional
>    detail. No case needs its own typed payload — the mismatch fingerprints fit
>    the detail string — and an enum is directly persistable, exhaustively
>    iterable for notification ids, and stable across a Room round-trip.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/SyncError.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/domain/GitMirror.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/domain/JGitMirror.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/SyncErrorsTest.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/JGitMirrorTest.kt`
- Modify: `app/build.gradle.kts` (JGit on the unit-test classpath)

**Interfaces:**
- Consumes: `HostKeyVerifier.verify`, `HostKeyDecision` (Task 4); `SshKeyStore.keyPair()` (Task 3); `AndroidSystemReader.install()` (Task 2).
- Produces:
  - `enum class SyncErrorCode { NO_NETWORK, LOW_STORAGE, PERMISSION_LOST, AUTH_FAILED, HOST_KEY_MISMATCH, HOST_UNREACHABLE, REMOTE_ERROR, LOCAL_CORRUPT, UNKNOWN }`
  - `data class SyncError(val code: SyncErrorCode, val detail: String? = null)`
  - `object SyncErrors { fun fromException(t: Throwable): SyncError }`
  - `class HostKeyMismatchException(val stored: String, val presented: String) : Exception`
  - `sealed interface MirrorOutcome { data class Success(val sizeBytes: Long); data class Failure(val error: SyncError) }`
  - `interface GitMirror` with `suspend fun sync(remoteUrl: String, destination: File, pinnedFingerprint: String?): MirrorOutcome`, `suspend fun probeHostKey(remoteUrl: String): Result<String>`, `fun sizeBytes(destination: File): Long`
  - `AppContainer.gitMirror: GitMirror`

- [ ] **Step 1: Put JGit on the unit-test classpath**

In `app/build.gradle.kts`, add to `dependencies`:

```kotlin
    testImplementation(libs.jgit)
```

The production rule "only `JGitMirror` imports JGit" governs `src/main`. Tests must build fixture repositories, and doing so with JGit rather than a shelled-out `git` keeps them hermetic.

- [ ] **Step 2: Write the failing test for exception mapping**

```kotlin
package de.nereide.strohhalm.domain

import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SyncErrorsTest {

    @Test
    fun `a rejected key maps to AUTH_FAILED`() {
        val e = TransportException("git@host:repo.git: Auth fail")
        assertEquals(SyncErrorCode.AUTH_FAILED, SyncErrors.fromException(e).code)
    }

    @Test
    fun `permission denied also maps to AUTH_FAILED`() {
        val e = TransportException("ssh://host/repo: Permission denied (publickey)")
        assertEquals(SyncErrorCode.AUTH_FAILED, SyncErrors.fromException(e).code)
    }

    @Test
    fun `a host key mismatch keeps both fingerprints in the detail`() {
        val e = HostKeyMismatchException(stored = "SHA256:aaa", presented = "SHA256:bbb")
        val mapped = SyncErrors.fromException(e)

        assertEquals(SyncErrorCode.HOST_KEY_MISMATCH, mapped.code)
        assertEquals("expected SHA256:aaa, got SHA256:bbb", mapped.detail)
    }

    @Test
    fun `an unresolvable host maps to HOST_UNREACHABLE`() {
        assertEquals(
            SyncErrorCode.HOST_UNREACHABLE,
            SyncErrors.fromException(UnknownHostException("nope.invalid")).code
        )
    }

    @Test
    fun `a timeout maps to HOST_UNREACHABLE`() {
        assertEquals(
            SyncErrorCode.HOST_UNREACHABLE,
            SyncErrors.fromException(SocketTimeoutException("timed out")).code
        )
    }

    @Test
    fun `a missing remote repository maps to REMOTE_ERROR`() {
        val e = NoRemoteRepositoryException(URIish("ssh://host/gone.git"), "not found")
        assertEquals(SyncErrorCode.REMOTE_ERROR, SyncErrors.fromException(e).code)
    }

    @Test
    fun `a wrapped cause is unwrapped before matching`() {
        val e = RuntimeException("outer", TransportException("Auth fail"))
        assertEquals(SyncErrorCode.AUTH_FAILED, SyncErrors.fromException(e).code)
    }

    @Test
    fun `an unrecognised failure maps to UNKNOWN but keeps the message`() {
        val mapped = SyncErrors.fromException(IOException("disk went sideways"))
        assertEquals(SyncErrorCode.UNKNOWN, mapped.code)
        assertEquals("disk went sideways", mapped.detail)
    }
}
```

- [ ] **Step 3: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*SyncErrorsTest*'`
Expected: FAIL — unresolved reference `SyncErrors`.

- [ ] **Step 4: Implement `SyncError.kt`**

```kotlin
package de.nereide.strohhalm.domain

import org.eclipse.jgit.errors.NoRemoteRepositoryException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Why a sync could not complete. Stored on the repository row and rendered by
 * the UI, which maps each code to a string resource — the domain layer stays
 * free of Android resource ids.
 */
enum class SyncErrorCode {
    NO_NETWORK,
    LOW_STORAGE,
    PERMISSION_LOST,
    AUTH_FAILED,
    HOST_KEY_MISMATCH,
    HOST_UNREACHABLE,
    REMOTE_ERROR,
    LOCAL_CORRUPT,
    UNKNOWN,
}

data class SyncError(val code: SyncErrorCode, val detail: String? = null)

/**
 * Raised by the server key database when a host presents a key other than the
 * pinned one. Distinct from every other failure because it may indicate an
 * interception rather than an outage.
 */
class HostKeyMismatchException(
    val stored: String,
    val presented: String,
) : Exception("host key mismatch: expected $stored, got $presented")

/**
 * Translates library exceptions into [SyncError]. This is the single boundary
 * where JGit and MINA SSHD exception types are allowed to be inspected; nothing
 * above the domain layer ever sees a `TransportException`.
 */
object SyncErrors {

    fun fromException(t: Throwable): SyncError {
        for (cause in causeChain(t)) {
            classify(cause)?.let { return it }
        }
        return SyncError(SyncErrorCode.UNKNOWN, t.message)
    }

    private fun classify(t: Throwable): SyncError? = when {
        t is HostKeyMismatchException ->
            SyncError(
                SyncErrorCode.HOST_KEY_MISMATCH,
                "expected ${t.stored}, got ${t.presented}"
            )

        t is UnknownHostException ->
            SyncError(SyncErrorCode.HOST_UNREACHABLE, t.message)

        t is SocketTimeoutException ->
            SyncError(SyncErrorCode.HOST_UNREACHABLE, t.message)

        t is NoRemoteRepositoryException ->
            SyncError(SyncErrorCode.REMOTE_ERROR, t.message)

        else -> classifyByMessage(t)
    }

    private fun classifyByMessage(t: Throwable): SyncError? {
        val message = t.message ?: return null
        val lower = message.lowercase()
        return when {
            "auth fail" in lower ||
                "permission denied" in lower ||
                "publickey" in lower ->
                SyncError(SyncErrorCode.AUTH_FAILED, message)

            "connection refused" in lower ||
                "unreachable" in lower ||
                "connection reset" in lower ->
                SyncError(SyncErrorCode.HOST_UNREACHABLE, message)

            "not found" in lower ||
                "does not appear to be a git repository" in lower ->
                SyncError(SyncErrorCode.REMOTE_ERROR, message)

            "corrupt" in lower || "invalid object" in lower ->
                SyncError(SyncErrorCode.LOCAL_CORRUPT, message)

            else -> null
        }
    }

    private fun causeChain(t: Throwable): Sequence<Throwable> =
        generateSequence(t) { current -> current.cause.takeIf { it !== current } }
}
```

- [ ] **Step 5: Run and confirm the mapping tests pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*SyncErrorsTest*'`
Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(domain): map library exceptions to a sync error taxonomy"
```

- [ ] **Step 7: Write `GitMirror.kt`**

```kotlin
package de.nereide.strohhalm.domain

import java.io.File

/** The result of one repository sync. */
sealed interface MirrorOutcome {
    data class Success(val sizeBytes: Long) : MirrorOutcome
    data class Failure(val error: SyncError) : MirrorOutcome
}

/**
 * Maintains bare mirror clones. Implementations are the only place in the app
 * that import JGit, so replacing the engine touches exactly one file.
 *
 * Every method is read-only with respect to the remote: nothing here pushes,
 * commits or otherwise writes upstream.
 */
interface GitMirror {

    /**
     * Mirrors [remoteUrl] into [destination] — a `clone --mirror` when the
     * directory does not yet exist, otherwise a pruning fetch of every ref.
     *
     * [pinnedFingerprint] is the previously accepted host key; a null value means
     * nothing is pinned yet and the connection is refused rather than trusted
     * blindly. Failures are returned as [MirrorOutcome.Failure], not thrown.
     */
    suspend fun sync(
        remoteUrl: String,
        destination: File,
        pinnedFingerprint: String?,
    ): MirrorOutcome

    /**
     * Connects far enough to read the server's host key and returns its OpenSSH
     * `SHA256:` fingerprint, running no git operation. Used when adding a repo,
     * so the user can confirm the fingerprint before anything is trusted.
     */
    suspend fun probeHostKey(remoteUrl: String): Result<String>

    /** On-disk size of a mirror, or 0 when it does not exist. */
    fun sizeBytes(destination: File): Long
}
```

- [ ] **Step 8: Write the failing mirror test**

This is the test that proves the backup guarantee.

```kotlin
package de.nereide.strohhalm.domain

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Drives [JGitMirror] against a `file://` remote. No network, no SSH, no server —
 * but the clone, fetch and prune paths exercised are exactly the ones used in
 * production, which is what makes the mirror guarantees testable at all.
 */
class JGitMirrorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var origin: File
    private lateinit var mirror: JGitMirror

    @Before
    fun setUp() {
        AndroidSystemReader.install()
        mirror = JGitMirror(keyPairProvider = { error("file:// needs no key") })
        origin = tmp.newFolder("origin")

        Git.init().setDirectory(origin).call().use { git ->
            File(origin, "a.txt").writeText("one")
            git.add().addFilepattern("a.txt").call()
            git.commit().setMessage("first").setSign(false).call()
            git.tag().setName("v1").setAnnotated(false).call()

            // Captured rather than assumed: JGit's default branch name has
            // changed between versions, so hardcoding "master" or "main" would
            // make this test version-dependent.
            val defaultBranch = git.repository.branch

            // A branch that is never checked out again — a plain clone would
            // silently omit it, which is the failure mode this guards against.
            git.checkout().setCreateBranch(true).setName("feature").call()
            File(origin, "b.txt").writeText("two")
            git.add().addFilepattern("b.txt").call()
            git.commit().setMessage("second").setSign(false).call()
            git.checkout().setName(defaultBranch).call()
        }
    }

    @Test
    fun `mirrors every branch and tag`() = runBlocking {
        val dest = File(tmp.root, "mirror.git")

        val outcome = mirror.sync(origin.toURI().toString(), dest, pinnedFingerprint = null)

        assertTrue("sync failed: $outcome", outcome is MirrorOutcome.Success)
        val refs = refsIn(dest)
        assertTrue("feature branch missing: $refs", refs.contains("refs/heads/feature"))
        assertTrue("tag missing: $refs", refs.contains("refs/tags/v1"))
    }

    @Test
    fun `clones bare - there is no working tree`() = runBlocking {
        val dest = File(tmp.root, "mirror.git")
        mirror.sync(origin.toURI().toString(), dest, null)

        assertTrue(File(dest, "objects").isDirectory)
        assertFalse("a working tree was checked out", File(dest, "a.txt").exists())
        assertFalse(File(dest, ".git").exists())
    }

    @Test
    fun `a second sync picks up new commits`() = runBlocking {
        val dest = File(tmp.root, "mirror.git")
        mirror.sync(origin.toURI().toString(), dest, null)
        val before = headOf(dest, "refs/heads/feature")

        Git.open(origin).use { git ->
            git.checkout().setName("feature").call()
            File(origin, "c.txt").writeText("three")
            git.add().addFilepattern("c.txt").call()
            git.commit().setMessage("third").setSign(false).call()
        }

        val outcome = mirror.sync(origin.toURI().toString(), dest, null)

        assertTrue(outcome is MirrorOutcome.Success)
        assertTrue("the mirror did not advance", before != headOf(dest, "refs/heads/feature"))
    }

    @Test
    fun `a branch deleted upstream is pruned locally`() = runBlocking {
        val dest = File(tmp.root, "mirror.git")
        mirror.sync(origin.toURI().toString(), dest, null)
        assertTrue(refsIn(dest).contains("refs/heads/feature"))

        Git.open(origin).use { git -> git.branchDelete().setBranchNames("feature").setForce(true).call() }
        mirror.sync(origin.toURI().toString(), dest, null)

        assertFalse("a deleted branch survived", refsIn(dest).contains("refs/heads/feature"))
    }

    @Test
    fun `an unreachable remote returns a failure rather than throwing`() = runBlocking {
        val dest = File(tmp.root, "missing.git")

        val outcome = mirror.sync(File(tmp.root, "nope").toURI().toString(), dest, null)

        assertTrue(outcome is MirrorOutcome.Failure)
    }

    @Test
    fun `size is reported for an existing mirror and zero otherwise`() = runBlocking {
        val dest = File(tmp.root, "mirror.git")
        assertEquals(0L, mirror.sizeBytes(dest))

        mirror.sync(origin.toURI().toString(), dest, null)

        assertTrue(mirror.sizeBytes(dest) > 0L)
    }

    private fun refsIn(dir: File): Set<String> =
        Git.open(dir).use { git -> git.repository.refDatabase.refs.map { it.name }.toSet() }

    private fun headOf(dir: File, ref: String): String =
        Git.open(dir).use { git -> git.repository.exactRef(ref).objectId.name }
}
```

- [ ] **Step 9: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*JGitMirrorTest*'`
Expected: FAIL — unresolved reference `JGitMirror`.

- [ ] **Step 10: Implement `JGitMirror.kt`**

Adjust the `ServerKeyDatabase` overrides to the exact signatures recorded in the Task 2 spike notes if they differ.

```kotlin
package de.nereide.strohhalm.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicReference

/**
 * JGit-backed [GitMirror]. The single file in `src/main` allowed to import JGit
 * or MINA SSHD, so swapping the engine is a one-file change.
 *
 * Mirroring uses `+refs/*:refs/*` so every ref — branches, tags, notes — maps
 * 1:1 into the local repository, and `setRemoveDeletedRefs(true)` propagates
 * upstream deletions. A plain clone would track only `refs/heads/*`, which is
 * how backups end up quietly incomplete.
 */
class JGitMirror(
    private val keyPairProvider: suspend () -> KeyPair,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : GitMirror {

    override suspend fun sync(
        remoteUrl: String,
        destination: File,
        pinnedFingerprint: String?,
    ): MirrorOutcome = withContext(io) {
        AndroidSystemReader.install()
        runCatching {
            if (requiresSsh(remoteUrl)) {
                installSessionFactory(pinnedFingerprint, observed = null)
            }
            if (File(destination, "HEAD").isFile) {
                fetchInto(destination, remoteUrl)
            } else {
                cloneMirror(remoteUrl, destination)
            }
            MirrorOutcome.Success(sizeBytes(destination))
        }.getOrElse { t ->
            MirrorOutcome.Failure(SyncErrors.fromException(t))
        }
    }

    override suspend fun probeHostKey(remoteUrl: String): Result<String> = withContext(io) {
        AndroidSystemReader.install()
        val observed = AtomicReference<String?>(null)
        runCatching {
            installSessionFactory(pinnedFingerprint = null, observed = observed)
            // ls-remote is the cheapest operation that completes a handshake.
            Git.lsRemoteRepository().setRemote(remoteUrl).setHeads(true).call()
            observed.get() ?: error("the server presented no host key")
        }.recoverCatching { t ->
            // Authentication may fail before we care; the key is read first, so a
            // captured fingerprint is still a successful probe.
            observed.get() ?: throw t
        }
    }

    override fun sizeBytes(destination: File): Long {
        if (!destination.isDirectory) return 0L
        return destination.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    private fun cloneMirror(remoteUrl: String, destination: File) {
        destination.parentFile?.mkdirs()
        Git.cloneRepository()
            .setURI(remoteUrl)
            .setDirectory(destination)
            .setBare(true)
            .setMirror(true)
            .call()
            .close()
    }

    private fun fetchInto(destination: File, remoteUrl: String) {
        openRepository(destination).use { repo ->
            Git(repo).use { git ->
                git.fetch()
                    .setRemote(remoteUrl)
                    .setRefSpecs(RefSpec("+refs/*:refs/*"))
                    .setRemoveDeletedRefs(true)
                    .setTagOpt(org.eclipse.jgit.transport.TagOpt.FETCH_TAGS)
                    .call()
            }
        }
    }

    private fun openRepository(destination: File): Repository =
        FileRepositoryBuilder()
            .setGitDir(destination)
            .setMustExist(true)
            .build()

    private fun requiresSsh(remoteUrl: String): Boolean =
        remoteUrl.startsWith("ssh://") || (!remoteUrl.contains("://") && remoteUrl.contains(":"))

    /**
     * Installs a process-wide session factory offering only Strohhalm's key and
     * enforcing the pinned host key. JGit's SSH transport is configured
     * globally, so this is set immediately before each operation.
     */
    private suspend fun installSessionFactory(
        pinnedFingerprint: String?,
        observed: AtomicReference<String?>?,
    ) {
        val keyPair = keyPairProvider()
        val factory: SshdSessionFactory = SshdSessionFactoryBuilder()
            .setPreferredAuthentications("publickey")
            .setHomeDirectory(NO_HOME)
            .setSshDirectory(NO_HOME)
            .setDefaultKeysProvider { listOf(keyPair) }
            .setServerKeyDatabase { _, _ -> pinningDatabase(pinnedFingerprint, observed) }
            .build(null)
        SshSessionFactory.setInstance(factory)
    }

    private fun pinningDatabase(
        pinnedFingerprint: String?,
        observed: AtomicReference<String?>?,
    ) = object : ServerKeyDatabase {

        override fun lookup(
            connectAddress: String?,
            remoteAddress: InetSocketAddress?,
            config: ServerKeyDatabase.Configuration?,
        ): List<PublicKey> = emptyList()

        override fun accept(
            connectAddress: String?,
            remoteAddress: InetSocketAddress?,
            serverKey: PublicKey?,
            config: ServerKeyDatabase.Configuration?,
            provider: CredentialsProvider?,
        ): Boolean {
            val presented = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey)
                ?: return false
            observed?.set(presented)

            return when (val decision = HostKeyVerifier.verify(pinnedFingerprint, presented)) {
                is HostKeyDecision.Trusted -> true
                // Probing captures the key; syncing must never trust an unpinned host.
                is HostKeyDecision.FirstUse -> observed != null
                is HostKeyDecision.Mismatch ->
                    throw HostKeyMismatchException(decision.stored, decision.presented)
            }
        }
    }

    private companion object {
        /**
         * JGit insists on a home and .ssh directory. Android has neither, and the
         * key is supplied programmatically, so an unused path keeps it from
         * reading any on-disk config.
         */
        val NO_HOME: File = File("/data/local/tmp/strohhalm-nonexistent")
    }
}
```

- [ ] **Step 11: Run the mirror tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*JGitMirrorTest*'`
Expected: PASS, 6 tests. If `setSign(false)` is rejected by the JGit version in use, drop that call — it only suppresses GPG signing, which is off by default.

- [ ] **Step 12: Expose it on the container**

In `AppContainer.kt`, add to the interface:

```kotlin
    val gitMirror: GitMirror
```

and to `DefaultAppContainer`:

```kotlin
    override val gitMirror: GitMirror by lazy {
        JGitMirror(keyPairProvider = { sshKeyStore.keyPair() })
    }
```

with imports `de.nereide.strohhalm.domain.GitMirror` and `de.nereide.strohhalm.domain.JGitMirror`.

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "feat(domain): mirror repositories with JGit over SSH"
```

---

## Task 6: Repository persistence

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/data/RepoSlug.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/data/Repo.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/data/RepoDao.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/data/StrohhalmDatabase.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/domain/RepoRepository.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/domain/DefaultRepoRepository.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/data/RepoSlugTest.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/FakeRepoDao.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/DefaultRepoRepositoryTest.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/AppContainer.kt`

**Interfaces:**
- Consumes: `SyncError`, `SyncErrorCode` (Task 5).
- Produces:
  - `RepoSlug.fromRemoteUrl(url: String): String`, `RepoSlug.unique(base: String, taken: Set<String>): String`
  - `enum class SyncStatus { NEVER, SYNCING, OK, FAILED }`
  - `data class Repo(...)` — Room entity, table `repos`
  - `interface RepoDao`
  - `StrohhalmDatabase.getInstance(context): StrohhalmDatabase` with `repoDao()`
  - `interface RepoRepository` with `observeAll(): Flow<List<Repo>>`, `observe(id: Long): Flow<Repo?>`, `suspend fun add(displayName: String, remoteUrl: String, hostKeyFingerprint: String): Long`, `suspend fun all(): List<Repo>`, `suspend fun markSyncing(id: Long)`, `suspend fun markSuccess(id: Long, sizeBytes: Long)`, `suspend fun markFailure(id: Long, error: SyncError)`, `suspend fun delete(id: Long)`
  - `AppContainer.repoRepository: RepoRepository`

- [ ] **Step 1: Write the failing slug test**

```kotlin
package de.nereide.strohhalm.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RepoSlugTest {

    @Test
    fun `takes the last path segment of an ssh url`() {
        assertEquals("stromschnelle", RepoSlug.fromRemoteUrl("ssh://git@host:22/srv/git/stromschnelle.git"))
    }

    @Test
    fun `handles scp style remotes`() {
        assertEquals("notes", RepoSlug.fromRemoteUrl("git@host:notes.git"))
    }

    @Test
    fun `handles scp style remotes with a path`() {
        assertEquals("notes", RepoSlug.fromRemoteUrl("git@host:srv/git/notes.git"))
    }

    @Test
    fun `tolerates a trailing slash and a missing dot-git`() {
        assertEquals("erhebimus", RepoSlug.fromRemoteUrl("ssh://host/srv/erhebimus/"))
    }

    @Test
    fun `lowercases and collapses runs of punctuation`() {
        assertEquals("my-repo", RepoSlug.fromRemoteUrl("ssh://host/My__Repo!!.git"))
    }

    @Test
    fun `strips leading and trailing separators`() {
        assertEquals("repo", RepoSlug.fromRemoteUrl("ssh://host/--repo--.git"))
    }

    @Test
    fun `falls back when nothing usable remains`() {
        assertEquals("repo", RepoSlug.fromRemoteUrl("ssh://host/!!!.git"))
    }

    @Test
    fun `unique returns the base when it is free`() {
        assertEquals("notes", RepoSlug.unique("notes", emptySet()))
    }

    @Test
    fun `unique suffixes on collision`() {
        assertEquals("notes-2", RepoSlug.unique("notes", setOf("notes")))
    }

    @Test
    fun `unique keeps counting past several collisions`() {
        assertEquals("notes-4", RepoSlug.unique("notes", setOf("notes", "notes-2", "notes-3")))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*RepoSlugTest*'`
Expected: FAIL — unresolved reference `RepoSlug`.

- [ ] **Step 3: Implement `RepoSlug.kt`**

```kotlin
package de.nereide.strohhalm.data

/**
 * Derives the on-disk directory name for a mirror from its remote URL.
 *
 * The order of the two `substringAfterLast` calls matters: splitting on `/`
 * first and `:` second handles both `ssh://host/a/b/repo.git` and the scp-style
 * `git@host:repo.git`, where the whole string survives the first split.
 */
object RepoSlug {

    fun fromRemoteUrl(url: String): String {
        val lastSegment = url.trim().trimEnd('/')
            .substringAfterLast('/')
            .substringAfterLast(':')
        val slug = lastSegment.removeSuffix(".git")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return slug.ifEmpty { FALLBACK }
    }

    /**
     * Appends a numeric suffix until the name is free. Counting starts at 2 so
     * the first collision reads `notes-2`, matching how a person would name it.
     */
    fun unique(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    private const val FALLBACK = "repo"
}
```

- [ ] **Step 4: Run and confirm the slug tests pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*RepoSlugTest*'`
Expected: PASS, 10 tests.

- [ ] **Step 5: Write `Repo.kt`**

```kotlin
package de.nereide.strohhalm.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a repository stood at the end of its last sync attempt. */
enum class SyncStatus { NEVER, SYNCING, OK, FAILED }

/**
 * One mirrored repository.
 *
 * [lastSyncAt] and [lastAttemptAt] are deliberately separate: a failed sync
 * advances the attempt time but leaves the success time alone, so a repository
 * that has been failing for weeks cannot present a fresh timestamp and look
 * healthy.
 *
 * [localPath] is unique — two repositories sharing a directory would corrupt
 * each other.
 */
@Entity(
    tableName = "repos",
    indices = [Index(value = ["localPath"], unique = true)]
)
data class Repo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val remoteUrl: String,
    val localPath: String,
    val hostKeyFingerprint: String? = null,
    val lastSyncAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastStatus: SyncStatus = SyncStatus.NEVER,
    val lastErrorCode: String? = null,
    val lastErrorDetail: String? = null,
    val sizeBytes: Long = 0,
    val createdAt: Long,
)
```

- [ ] **Step 6: Write `RepoDao.kt`**

```kotlin
package de.nereide.strohhalm.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RepoDao {

    @Query("SELECT * FROM repos ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Repo>>

    @Query("SELECT * FROM repos WHERE id = :id")
    fun observe(id: Long): Flow<Repo?>

    /** Oldest-synced first, so a long backlog drains fairly. */
    @Query("SELECT * FROM repos ORDER BY IFNULL(lastSyncAt, 0) ASC")
    suspend fun all(): List<Repo>

    @Query("SELECT * FROM repos WHERE id = :id")
    suspend fun byId(id: Long): Repo?

    @Query("SELECT localPath FROM repos")
    suspend fun localPaths(): List<String>

    @Insert
    suspend fun insert(repo: Repo): Long

    @Update
    suspend fun update(repo: Repo)

    @Delete
    suspend fun delete(repo: Repo)
}
```

- [ ] **Step 7: Write `StrohhalmDatabase.kt`**

```kotlin
package de.nereide.strohhalm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class SyncStatusConverter {
    @TypeConverter
    fun toStatus(name: String): SyncStatus =
        SyncStatus.entries.firstOrNull { it.name == name } ?: SyncStatus.NEVER

    @TypeConverter
    fun fromStatus(status: SyncStatus): String = status.name
}

@Database(entities = [Repo::class], version = 1, exportSchema = true)
@TypeConverters(SyncStatusConverter::class)
abstract class StrohhalmDatabase : RoomDatabase() {

    abstract fun repoDao(): RepoDao

    companion object {
        @Volatile
        private var INSTANCE: StrohhalmDatabase? = null

        fun getInstance(context: Context): StrohhalmDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StrohhalmDatabase::class.java,
                    "strohhalm.db"
                ).build().also { INSTANCE = it }
            }
    }
}
```

An unknown stored status falls back to `NEVER` rather than throwing, mirroring how `stromschnelle`'s `SettingsRepository` treats unrecognised enum names.

- [ ] **Step 8: Write `RepoRepository.kt`**

```kotlin
package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.Repo
import kotlinx.coroutines.flow.Flow

/** Repository CRUD plus the bookkeeping each sync attempt writes back. */
interface RepoRepository {

    fun observeAll(): Flow<List<Repo>>

    fun observe(id: Long): Flow<Repo?>

    /** Oldest-synced first. */
    suspend fun all(): List<Repo>

    /**
     * Creates a repository with a directory name derived from [remoteUrl] and
     * made unique against those already taken. Returns the new row id.
     */
    suspend fun add(displayName: String, remoteUrl: String, hostKeyFingerprint: String): Long

    suspend fun markSyncing(id: Long)

    suspend fun markSuccess(id: Long, sizeBytes: Long)

    suspend fun markFailure(id: Long, error: SyncError)

    suspend fun delete(id: Long)
}
```

- [ ] **Step 9: Write the failing repository test**

First the fake DAO:

```kotlin
package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.Repo
import de.nereide.strohhalm.data.RepoDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [RepoDao] so the repository can be tested without Room. */
class FakeRepoDao : RepoDao {

    private val rows = MutableStateFlow<List<Repo>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<Repo>> =
        rows.map { list -> list.sortedBy { it.displayName.lowercase() } }

    override fun observe(id: Long): Flow<Repo?> =
        rows.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun all(): List<Repo> = rows.value.sortedBy { it.lastSyncAt ?: 0L }

    override suspend fun byId(id: Long): Repo? = rows.value.firstOrNull { it.id == id }

    override suspend fun localPaths(): List<String> = rows.value.map { it.localPath }

    override suspend fun insert(repo: Repo): Long {
        val id = nextId++
        rows.value = rows.value + repo.copy(id = id)
        return id
    }

    override suspend fun update(repo: Repo) {
        rows.value = rows.value.map { if (it.id == repo.id) repo else it }
    }

    override suspend fun delete(repo: Repo) {
        rows.value = rows.value.filterNot { it.id == repo.id }
    }
}
```

Then the test:

```kotlin
package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DefaultRepoRepositoryTest {

    private val dao = FakeRepoDao()
    private var now = 1_000L
    private val root = File("/storage/emulated/0/Strohhalm")

    private val repository = DefaultRepoRepository(
        dao = dao,
        storageRoot = { root },
        clock = { now }
    )

    @Test
    fun `add derives a local path from the remote url`() = runTest {
        val id = repository.add("Notes", "ssh://git@host/srv/notes.git", "SHA256:aaa")

        val repo = dao.byId(id)!!
        assertEquals(File(root, "notes.git").path, repo.localPath)
        assertEquals(SyncStatus.NEVER, repo.lastStatus)
        assertEquals(1_000L, repo.createdAt)
        assertEquals("SHA256:aaa", repo.hostKeyFingerprint)
    }

    @Test
    fun `add makes colliding paths unique`() = runTest {
        repository.add("First", "ssh://a.host/srv/notes.git", "SHA256:aaa")
        val second = repository.add("Second", "ssh://b.host/other/notes.git", "SHA256:bbb")

        assertEquals(File(root, "notes-2.git").path, dao.byId(second)!!.localPath)
    }

    @Test
    fun `a successful sync records the time and size`() = runTest {
        val id = repository.add("Notes", "ssh://host/notes.git", "SHA256:aaa")
        now = 2_000L

        repository.markSuccess(id, sizeBytes = 4_096)

        val repo = dao.byId(id)!!
        assertEquals(SyncStatus.OK, repo.lastStatus)
        assertEquals(2_000L, repo.lastSyncAt)
        assertEquals(2_000L, repo.lastAttemptAt)
        assertEquals(4_096L, repo.sizeBytes)
        assertNull(repo.lastErrorCode)
    }

    @Test
    fun `a failed sync preserves the last successful time`() = runTest {
        val id = repository.add("Notes", "ssh://host/notes.git", "SHA256:aaa")
        now = 2_000L
        repository.markSuccess(id, sizeBytes = 4_096)

        now = 3_000L
        repository.markFailure(id, SyncError(SyncErrorCode.AUTH_FAILED, "Auth fail"))

        val repo = dao.byId(id)!!
        assertEquals(SyncStatus.FAILED, repo.lastStatus)
        assertEquals("the success time must not move on failure", 2_000L, repo.lastSyncAt)
        assertEquals(3_000L, repo.lastAttemptAt)
        assertEquals("AUTH_FAILED", repo.lastErrorCode)
        assertEquals("Auth fail", repo.lastErrorDetail)
        assertEquals("the size must survive a failure", 4_096L, repo.sizeBytes)
    }

    @Test
    fun `a later success clears the recorded error`() = runTest {
        val id = repository.add("Notes", "ssh://host/notes.git", "SHA256:aaa")
        repository.markFailure(id, SyncError(SyncErrorCode.NO_NETWORK))
        repository.markSuccess(id, sizeBytes = 10)

        val repo = dao.byId(id)!!
        assertNull(repo.lastErrorCode)
        assertNull(repo.lastErrorDetail)
    }

    @Test
    fun `markSyncing does not touch the timestamps`() = runTest {
        val id = repository.add("Notes", "ssh://host/notes.git", "SHA256:aaa")
        repository.markSyncing(id)

        val repo = dao.byId(id)!!
        assertEquals(SyncStatus.SYNCING, repo.lastStatus)
        assertNull(repo.lastSyncAt)
    }

    @Test
    fun `all returns the least recently synced first`() = runTest {
        val a = repository.add("A", "ssh://host/a.git", "SHA256:aaa")
        val b = repository.add("B", "ssh://host/b.git", "SHA256:bbb")
        now = 5_000L
        repository.markSuccess(a, 1)

        assertEquals(listOf(b, a), repository.all().map { it.id })
    }

    @Test
    fun `delete removes the row`() = runTest {
        val id = repository.add("Notes", "ssh://host/notes.git", "SHA256:aaa")
        repository.delete(id)

        assertTrue(dao.all().isEmpty())
    }
}
```

- [ ] **Step 10: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*DefaultRepoRepositoryTest*'`
Expected: FAIL — unresolved reference `DefaultRepoRepository`.

- [ ] **Step 11: Implement `DefaultRepoRepository.kt`**

```kotlin
package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.Repo
import de.nereide.strohhalm.data.RepoDao
import de.nereide.strohhalm.data.RepoSlug
import de.nereide.strohhalm.data.SyncStatus
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * [storageRoot] is a function rather than a value because the user can change
 * the mirror directory at any time; resolving it per call avoids a stale root.
 * [clock] is injected so tests can advance time deterministically.
 */
class DefaultRepoRepository(
    private val dao: RepoDao,
    private val storageRoot: suspend () -> File,
    private val clock: () -> Long = System::currentTimeMillis,
) : RepoRepository {

    override fun observeAll(): Flow<List<Repo>> = dao.observeAll()

    override fun observe(id: Long): Flow<Repo?> = dao.observe(id)

    override suspend fun all(): List<Repo> = dao.all()

    override suspend fun add(
        displayName: String,
        remoteUrl: String,
        hostKeyFingerprint: String,
    ): Long {
        val root = storageRoot()
        val taken = dao.localPaths()
            .map { File(it).name.removeSuffix(GIT_SUFFIX) }
            .toSet()
        val slug = RepoSlug.unique(RepoSlug.fromRemoteUrl(remoteUrl), taken)
        return dao.insert(
            Repo(
                displayName = displayName.ifBlank { slug },
                remoteUrl = remoteUrl,
                localPath = File(root, slug + GIT_SUFFIX).path,
                hostKeyFingerprint = hostKeyFingerprint,
                createdAt = clock(),
            )
        )
    }

    override suspend fun markSyncing(id: Long) {
        val repo = dao.byId(id) ?: return
        dao.update(repo.copy(lastStatus = SyncStatus.SYNCING))
    }

    override suspend fun markSuccess(id: Long, sizeBytes: Long) {
        val repo = dao.byId(id) ?: return
        val now = clock()
        dao.update(
            repo.copy(
                lastStatus = SyncStatus.OK,
                lastSyncAt = now,
                lastAttemptAt = now,
                sizeBytes = sizeBytes,
                lastErrorCode = null,
                lastErrorDetail = null,
            )
        )
    }

    override suspend fun markFailure(id: Long, error: SyncError) {
        val repo = dao.byId(id) ?: return
        dao.update(
            repo.copy(
                lastStatus = SyncStatus.FAILED,
                lastAttemptAt = clock(),
                lastErrorCode = error.code.name,
                lastErrorDetail = error.detail,
            )
        )
    }

    override suspend fun delete(id: Long) {
        val repo = dao.byId(id) ?: return
        dao.delete(repo)
    }

    private companion object {
        const val GIT_SUFFIX = ".git"
    }
}
```

- [ ] **Step 12: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*DefaultRepoRepositoryTest*' --tests '*RepoSlugTest*'`
Expected: PASS, 18 tests.

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "feat(data): persist mirrored repositories and their sync state"
```

---

## Task 7: Settings, interval and storage root resolution

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/data/SyncInterval.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/data/SettingsRepository.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/domain/StorageRootResolver.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/data/SyncIntervalTest.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/FakeDataStore.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/data/SettingsRepositoryTest.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/StorageRootResolverTest.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/AppContainer.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class SyncInterval { MANUAL, M15, M30, H1, H3, H6, H12, D1 }` with `val minutes: Long?` and `val duration: Duration?`
  - `SettingsRepository(dataStore)` exposing `syncInterval: Flow<SyncInterval>`, `storageRoot: Flow<String?>`, `notifyOnFailure: Flow<Boolean>`, and setters `setSyncInterval`, `setStorageRoot`, `setNotifyOnFailure`; `suspend fun requireStorageRoot(): File`
  - `StorageRootResolver.resolve(documentId: String, primaryRoot: File, volumeLookup: (String) -> File?): File?` and `StorageRootResolver.isWritable(dir: File): Boolean`
  - `AppContainer.settingsRepository: SettingsRepository`

- [ ] **Step 1: Write the failing interval test**

```kotlin
package de.nereide.strohhalm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class SyncIntervalTest {

    @Test
    fun `manual has no duration`() {
        assertNull(SyncInterval.MANUAL.duration)
        assertNull(SyncInterval.MANUAL.minutes)
    }

    @Test
    fun `every scheduled value maps to the duration its name promises`() {
        assertEquals(Duration.ofMinutes(15), SyncInterval.M15.duration)
        assertEquals(Duration.ofMinutes(30), SyncInterval.M30.duration)
        assertEquals(Duration.ofHours(1), SyncInterval.H1.duration)
        assertEquals(Duration.ofHours(3), SyncInterval.H3.duration)
        assertEquals(Duration.ofHours(6), SyncInterval.H6.duration)
        assertEquals(Duration.ofHours(12), SyncInterval.H12.duration)
        assertEquals(Duration.ofDays(1), SyncInterval.D1.duration)
    }

    @Test
    fun `no scheduled value can violate WorkManagers 15 minute floor`() {
        val offenders = SyncInterval.entries
            .mapNotNull { it.minutes?.let { m -> it.name to m } }
            .filter { (_, minutes) -> minutes < 15 }

        assertTrue("intervals below the periodic floor: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the set of values is exactly the one the spec fixes`() {
        assertEquals(
            listOf("MANUAL", "M15", "M30", "H1", "H3", "H6", "H12", "D1"),
            SyncInterval.entries.map { it.name }
        )
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*SyncIntervalTest*'`
Expected: FAIL — unresolved reference `SyncInterval`.

- [ ] **Step 3: Implement `SyncInterval.kt`**

```kotlin
package de.nereide.strohhalm.data

import java.time.Duration

/**
 * How often mirrors are refreshed.
 *
 * An enum rather than a raw number of minutes: WorkManager rejects periodic
 * intervals below 15 minutes, and encoding the choices as named values makes
 * that floor impossible to violate through a bad write, at the cost of no
 * arbitrary intervals. [MANUAL] cancels the periodic work entirely.
 */
enum class SyncInterval(val minutes: Long?) {
    MANUAL(null),
    M15(15),
    M30(30),
    H1(60),
    H3(180),
    H6(360),
    H12(720),
    D1(1_440);

    val duration: Duration?
        get() = minutes?.let(Duration::ofMinutes)
}
```

- [ ] **Step 4: Run and confirm the interval tests pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*SyncIntervalTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Copy `FakeDataStore` from stromschnelle**

```bash
cd /home/christoph/Projects/strohhalm
mkdir -p app/src/test/java/de/nereide/strohhalm/domain
sed -e 's/stromschnelle/strohhalm/g' -e 's/SettingsRepository/SettingsRepository/g' \
  /home/christoph/Projects/stromschnelle/app/src/test/java/de/nereide/stromschnelle/domain/FakeDataStore.kt \
  > app/src/test/java/de/nereide/strohhalm/domain/FakeDataStore.kt
```

- [ ] **Step 6: Write the failing settings test**

```kotlin
package de.nereide.strohhalm.data

import de.nereide.strohhalm.domain.FakeDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsRepositoryTest {

    private val settings = SettingsRepository(FakeDataStore())

    @Test
    fun `defaults to hourly syncing`() = runTest {
        assertEquals(SyncInterval.H1, settings.syncInterval.first())
    }

    @Test
    fun `the interval round-trips`() = runTest {
        settings.setSyncInterval(SyncInterval.D1)
        assertEquals(SyncInterval.D1, settings.syncInterval.first())
    }

    @Test
    fun `an unrecognised stored interval falls back to the default`() = runTest {
        settings.setSyncIntervalRaw("H99")
        assertEquals(SyncInterval.H1, settings.syncInterval.first())
    }

    @Test
    fun `the storage root is unset until chosen`() = runTest {
        assertNull(settings.storageRoot.first())
    }

    @Test
    fun `the storage root round-trips`() = runTest {
        settings.setStorageRoot(File("/storage/emulated/0/Strohhalm"))
        assertEquals("/storage/emulated/0/Strohhalm", settings.storageRoot.first())
    }

    @Test
    fun `requireStorageRoot fails loudly when none is configured`() = runTest {
        val thrown = runCatching { settings.requireStorageRoot() }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun `failure notifications are on by default and can be turned off`() = runTest {
        assertTrue(settings.notifyOnFailure.first())

        settings.setNotifyOnFailure(false)
        assertEquals(false, settings.notifyOnFailure.first())
    }
}
```

- [ ] **Step 7: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*SettingsRepositoryTest*'`
Expected: FAIL — unresolved reference `SettingsRepository`.

- [ ] **Step 8: Implement `SettingsRepository.kt`**

```kotlin
package de.nereide.strohhalm.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Persists user settings via DataStore preferences: how often mirrors refresh,
 * where they are stored, and whether failures raise a notification.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    /**
     * An absent or unrecognised stored value falls back to the default rather
     * than throwing, so a downgrade that removes an interval cannot brick the
     * settings screen.
     */
    val syncInterval: Flow<SyncInterval> = dataStore.data.map { prefs ->
        prefs[KEY_SYNC_INTERVAL]
            ?.let { name -> SyncInterval.entries.firstOrNull { it.name == name } }
            ?: DEFAULT_SYNC_INTERVAL
    }

    suspend fun setSyncInterval(interval: SyncInterval) {
        dataStore.edit { prefs -> prefs[KEY_SYNC_INTERVAL] = interval.name }
    }

    /** Test seam for writing a value no current enum constant matches. */
    internal suspend fun setSyncIntervalRaw(name: String) {
        dataStore.edit { prefs -> prefs[KEY_SYNC_INTERVAL] = name }
    }

    /** Null until the user has picked a directory during onboarding. */
    val storageRoot: Flow<String?> = dataStore.data.map { prefs -> prefs[KEY_STORAGE_ROOT] }

    suspend fun setStorageRoot(dir: File) {
        dataStore.edit { prefs -> prefs[KEY_STORAGE_ROOT] = dir.path }
    }

    /**
     * The configured root, or an error. Callers that need a path — the worker,
     * the repository — cannot proceed without one, and failing here is clearer
     * than silently mirroring into a default location the user never chose.
     */
    suspend fun requireStorageRoot(): File =
        storageRoot.first()?.let(::File)
            ?: error("no storage root configured")

    val notifyOnFailure: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_NOTIFY_ON_FAILURE] ?: DEFAULT_NOTIFY_ON_FAILURE
    }

    suspend fun setNotifyOnFailure(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_NOTIFY_ON_FAILURE] = enabled }
    }

    companion object {
        val DEFAULT_SYNC_INTERVAL: SyncInterval = SyncInterval.H1
        const val DEFAULT_NOTIFY_ON_FAILURE: Boolean = true

        private val KEY_SYNC_INTERVAL = stringPreferencesKey("sync_interval")
        private val KEY_STORAGE_ROOT = stringPreferencesKey("storage_root")
        private val KEY_NOTIFY_ON_FAILURE = booleanPreferencesKey("notify_on_failure")
    }
}
```

- [ ] **Step 9: Run the settings tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*SettingsRepositoryTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 10: Write the failing storage-root resolver test**

```kotlin
package de.nereide.strohhalm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageRootResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val primary = File("/storage/emulated/0")
    private val sdCard = File("/storage/1A2B-3C4D")
    private val lookup: (String) -> File? = { id -> sdCard.takeIf { id == "1A2B-3C4D" } }

    @Test
    fun `a primary volume document id resolves under external storage`() {
        assertEquals(
            File("/storage/emulated/0/Strohhalm"),
            StorageRootResolver.resolve("primary:Strohhalm", primary, lookup)
        )
    }

    @Test
    fun `nested paths are preserved`() {
        assertEquals(
            File("/storage/emulated/0/Backups/git"),
            StorageRootResolver.resolve("primary:Backups/git", primary, lookup)
        )
    }

    @Test
    fun `an empty relative path resolves to the volume root`() {
        assertEquals(primary, StorageRootResolver.resolve("primary:", primary, lookup))
    }

    @Test
    fun `a removable volume is resolved through the lookup`() {
        assertEquals(
            File("/storage/1A2B-3C4D/Strohhalm"),
            StorageRootResolver.resolve("1A2B-3C4D:Strohhalm", primary, lookup)
        )
    }

    @Test
    fun `an unknown volume yields null so the caller can fall back`() {
        assertNull(StorageRootResolver.resolve("XXXX-YYYY:Strohhalm", primary, lookup))
    }

    @Test
    fun `a document id without a volume separator yields null`() {
        assertNull(StorageRootResolver.resolve("Strohhalm", primary, lookup))
    }

    @Test
    fun `isWritable is true for a real directory`() {
        assertTrue(StorageRootResolver.isWritable(tmp.newFolder("writable")))
    }

    @Test
    fun `isWritable is false for a path that does not exist`() {
        assertFalse(StorageRootResolver.isWritable(File(tmp.root, "absent")))
    }

    @Test
    fun `isWritable leaves nothing behind`() {
        val dir = tmp.newFolder("clean")
        StorageRootResolver.isWritable(dir)
        assertEquals(0, dir.listFiles()!!.size)
    }
}
```

- [ ] **Step 11: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*StorageRootResolverTest*'`
Expected: FAIL — unresolved reference `StorageRootResolver`.

- [ ] **Step 12: Implement `StorageRootResolver.kt`**

```kotlin
package de.nereide.strohhalm.domain

import java.io.File

/**
 * Turns the document id of a Storage Access Framework tree URI into a real
 * filesystem path.
 *
 * The picker is used purely as a chooser: access comes from
 * MANAGE_EXTERNAL_STORAGE and ordinary `java.io.File`, not from the URI, because
 * JGit needs a real path and SAF only hands out opaque document URIs.
 *
 * A document id looks like `primary:Backups/git` — a volume id, a colon, then a
 * path relative to that volume. Deriving the path is therefore a guess, which is
 * why [isWritable] must confirm it before it is persisted.
 */
object StorageRootResolver {

    /**
     * @param volumeLookup resolves a non-primary volume id to its mount point.
     * @return the directory, or null when the volume is unknown or the id is
     *   malformed — in which case the caller falls back to manual entry.
     */
    fun resolve(
        documentId: String,
        primaryRoot: File,
        volumeLookup: (String) -> File?,
    ): File? {
        val parts = documentId.split(":", limit = 2)
        if (parts.size != 2) return null
        val (volumeId, relativePath) = parts

        val base = if (volumeId == PRIMARY_VOLUME) primaryRoot else volumeLookup(volumeId)
            ?: return null

        return if (relativePath.isEmpty()) base else File(base, relativePath)
    }

    /**
     * Confirms the derived path is real and writable by creating and deleting a
     * probe file. Checking `File.canWrite()` alone is not enough — it reports
     * stale results under scoped storage.
     */
    fun isWritable(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val probe = File(dir, ".strohhalm-write-probe")
        return try {
            probe.writeBytes(ByteArray(0))
            probe.isFile
        } catch (e: Exception) {
            false
        } finally {
            probe.delete()
        }
    }

    private const val PRIMARY_VOLUME = "primary"
}
```

- [ ] **Step 13: Run the resolver tests**

Run: `./gradlew :app:testDebugUnitTest --tests '*StorageRootResolverTest*'`
Expected: PASS, 9 tests.

- [ ] **Step 14: Wire settings and the repository into the container**

`AppContainer.kt` in full, now that most collaborators exist:

```kotlin
package de.nereide.strohhalm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import de.nereide.strohhalm.data.SettingsRepository
import de.nereide.strohhalm.data.StrohhalmDatabase
import de.nereide.strohhalm.domain.DefaultRepoRepository
import de.nereide.strohhalm.domain.EncryptedSshKeyStore
import de.nereide.strohhalm.domain.GitMirror
import de.nereide.strohhalm.domain.JGitMirror
import de.nereide.strohhalm.domain.RepoRepository
import de.nereide.strohhalm.domain.SshKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Service-locator container exposing the app's singletons. No Hilt. */
interface AppContainer {
    val settingsRepository: SettingsRepository
    val repoRepository: RepoRepository
    val sshKeyStore: SshKeyStore
    val gitMirror: GitMirror

    /**
     * Scope living as long as the process — used for fire-and-forget work that
     * must not die with a ViewModel or screen.
     */
    val applicationScope: CoroutineScope
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings"
)

class DefaultAppContainer(context: Context) : AppContainer {

    private val appContext = context.applicationContext

    override val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext.settingsDataStore)
    }

    override val sshKeyStore: SshKeyStore by lazy {
        EncryptedSshKeyStore(appContext.filesDir)
    }

    override val gitMirror: GitMirror by lazy {
        JGitMirror(keyPairProvider = { sshKeyStore.keyPair() })
    }

    override val repoRepository: RepoRepository by lazy {
        DefaultRepoRepository(
            dao = StrohhalmDatabase.getInstance(appContext).repoDao(),
            storageRoot = { settingsRepository.requireStorageRoot() },
            clock = System::currentTimeMillis
        )
    }
}
```

- [ ] **Step 15: Run the whole unit suite and commit**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, all tests.

```bash
git add -A
git commit -m "feat(data): sync interval, settings and storage root resolution"
```

---

## Task 8: Notifications

Silent success, loud failure. Because the worker carries no WorkManager constraints, every reason a sync cannot proceed reaches this component.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/work/NotificationIds.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/work/SyncNotifier.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/work/NotificationIdsTest.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `SyncError`, `SyncErrorCode` (Task 5).
- Produces:
  - `NotificationIds.PROGRESS: Int`, `NotificationIds.forError(code: SyncErrorCode): Int`
  - `class SyncNotifier(context)` with `fun ensureChannels()`, `fun progress(text: String): Notification`, `fun notifyFailure(error: SyncError, repoName: String?)`, `fun notifyFailureCount(count: Int)`, `fun clearFailures()`

- [ ] **Step 1: Write the failing notification-id test**

```kotlin
package de.nereide.strohhalm.work

import de.nereide.strohhalm.domain.SyncErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdsTest {

    @Test
    fun `each error category gets its own id`() {
        val ids = SyncErrorCode.entries.map { NotificationIds.forError(it) }
        assertEquals("categories must not share an id", ids.size, ids.toSet().size)
    }

    @Test
    fun `the same category always maps to the same id`() {
        assertEquals(
            NotificationIds.forError(SyncErrorCode.AUTH_FAILED),
            NotificationIds.forError(SyncErrorCode.AUTH_FAILED)
        )
    }

    @Test
    fun `no error id collides with the progress notification`() {
        val ids = SyncErrorCode.entries.map { NotificationIds.forError(it) }
        assertFalse(NotificationIds.PROGRESS in ids)
    }

    @Test
    fun `all ids are positive`() {
        val ids = SyncErrorCode.entries.map { NotificationIds.forError(it) } + NotificationIds.PROGRESS
        assertTrue(ids.all { it > 0 })
    }
}
```

The point of a per-category id is de-duplication: a repeated failure replaces its predecessor instead of stacking. A phone left offline overnight on a 15-minute interval would otherwise post 96 notifications.

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*NotificationIdsTest*'`
Expected: FAIL — unresolved reference `NotificationIds`.

- [ ] **Step 3: Implement `NotificationIds.kt`**

```kotlin
package de.nereide.strohhalm.work

import de.nereide.strohhalm.domain.SyncErrorCode

/**
 * Notification ids are fixed per failure *category*, so a recurring failure
 * replaces its predecessor rather than stacking, and is cancelled on the next
 * success.
 */
object NotificationIds {

    const val PROGRESS: Int = 1

    private const val ERROR_BASE = 100

    fun forError(code: SyncErrorCode): Int = ERROR_BASE + code.ordinal

    /** Every id this app may post, for bulk cancellation. */
    fun allErrorIds(): List<Int> = SyncErrorCode.entries.map(::forError)
}
```

- [ ] **Step 4: Run and confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*NotificationIdsTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Add the notification strings**

Append inside `<resources>` in `app/src/main/res/values/strings.xml`:

```xml
    <!-- Notifications -->
    <string name="channel_sync_name">Sync progress</string>
    <string name="channel_sync_description">Shown while repositories are being mirrored.</string>
    <string name="channel_problems_name">Sync problems</string>
    <string name="channel_problems_description">Warns when a backup could not run.</string>
    <string name="notification_progress_title">Mirroring repositories</string>

    <!-- Sync error messages -->
    <string name="error_no_network">No network connection — the mirrors are out of date.</string>
    <string name="error_low_storage">Not enough free space to sync. Free up space and it will retry.</string>
    <string name="error_permission_lost">Storage access was revoked. Grant it again to resume backups.</string>
    <string name="error_auth_failed">The server rejected this key — did you add the public key from Settings?</string>
    <string name="error_host_key_mismatch">The server presented a different host key. This may mean the server was rebuilt, or that the connection is being intercepted. Syncing is stopped until you confirm.</string>
    <string name="error_host_unreachable">Could not reach the server.</string>
    <string name="error_remote_error">The remote repository could not be read.</string>
    <string name="error_local_corrupt">The local mirror looks damaged. Delete and re-add the repository.</string>
    <string name="error_unknown">The sync failed.</string>
    <string name="error_title_one">Backup failed: %1$s</string>
    <string name="error_title_many">%1$d backups failed</string>
```

- [ ] **Step 6: Implement `SyncNotifier.kt`**

```kotlin
package de.nereide.strohhalm.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.nereide.strohhalm.MainActivity
import de.nereide.strohhalm.R
import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode

/**
 * Posts sync progress and failure notifications.
 *
 * The worker runs without WorkManager constraints precisely so that every reason
 * a sync cannot proceed arrives here and can be shown. A backup tool that fails
 * silently is worse than none, because it manufactures false confidence.
 */
class SyncNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                context.getString(R.string.channel_sync_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = context.getString(R.string.channel_sync_description) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROBLEMS,
                context.getString(R.string.channel_problems_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.channel_problems_description) }
        )
    }

    /** The ongoing notification backing the foreground service during a sync. */
    fun progress(text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setContentTitle(context.getString(R.string.notification_progress_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .build()

    fun notifyFailure(error: SyncError, repoName: String?) {
        val title = if (repoName != null) {
            context.getString(R.string.error_title_one, repoName)
        } else {
            context.getString(messageFor(error.code))
        }
        val body = context.getString(messageFor(error.code))

        val notification = NotificationCompat.Builder(context, CHANNEL_PROBLEMS)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

        runCatching { manager.notify(NotificationIds.forError(error.code), notification) }
    }

    fun notifyFailureCount(count: Int) {
        val title = context.getString(R.string.error_title_many, count)
        val notification = NotificationCompat.Builder(context, CHANNEL_PROBLEMS)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { manager.notify(NotificationIds.forError(SyncErrorCode.UNKNOWN), notification) }
    }

    /** Called after a clean run so stale warnings do not linger. */
    fun clearFailures() {
        NotificationIds.allErrorIds().forEach(manager::cancel)
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

    private fun messageFor(code: SyncErrorCode): Int = when (code) {
        SyncErrorCode.NO_NETWORK -> R.string.error_no_network
        SyncErrorCode.LOW_STORAGE -> R.string.error_low_storage
        SyncErrorCode.PERMISSION_LOST -> R.string.error_permission_lost
        SyncErrorCode.AUTH_FAILED -> R.string.error_auth_failed
        SyncErrorCode.HOST_KEY_MISMATCH -> R.string.error_host_key_mismatch
        SyncErrorCode.HOST_UNREACHABLE -> R.string.error_host_unreachable
        SyncErrorCode.REMOTE_ERROR -> R.string.error_remote_error
        SyncErrorCode.LOCAL_CORRUPT -> R.string.error_local_corrupt
        SyncErrorCode.UNKNOWN -> R.string.error_unknown
    }

    private companion object {
        const val CHANNEL_SYNC = "sync"
        const val CHANNEL_PROBLEMS = "problems"
    }
}
```

`manager.notify` is wrapped in `runCatching` because posting throws when the user has denied `POST_NOTIFICATIONS`; a missing notification must never fail a sync that otherwise succeeded.

- [ ] **Step 7: Build and commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass.

```bash
git add -A
git commit -m "feat(work): notification channels and per-category failure notices"
```

---

## Task 9: The sync worker and scheduler

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/work/SyncPreconditions.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/work/SyncWorker.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/work/SyncScheduler.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/work/SyncPreconditionsTest.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/StrohhalmApp.kt`

**Interfaces:**
- Consumes: `RepoRepository`, `GitMirror`, `SettingsRepository`, `SyncNotifier`, `SyncError`, `SyncInterval`, `MirrorOutcome`.
- Produces:
  - `SyncPreconditions.check(freeBytes: Long, storageRootExists: Boolean, hasStoragePermission: Boolean, hasNetwork: Boolean): SyncError?`
  - `SyncPreconditions.MIN_FREE_BYTES: Long`
  - `SyncScheduler.apply(context: Context, interval: SyncInterval)`, `SyncScheduler.syncNow(context: Context)`, `SyncScheduler.UNIQUE_WORK_NAME`

- [ ] **Step 1: Write the failing preconditions test**

```kotlin
package de.nereide.strohhalm.work

import de.nereide.strohhalm.domain.SyncErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPreconditionsTest {

    private val plenty = 10L * 1024 * 1024 * 1024

    @Test
    fun `everything in order yields no error`() {
        assertNull(
            SyncPreconditions.check(
                freeBytes = plenty,
                storageRootExists = true,
                hasStoragePermission = true,
                hasNetwork = true
            )
        )
    }

    @Test
    fun `too little free space is reported`() {
        val error = SyncPreconditions.check(
            freeBytes = 1024,
            storageRootExists = true,
            hasStoragePermission = true,
            hasNetwork = true
        )
        assertEquals(SyncErrorCode.LOW_STORAGE, error?.code)
    }

    @Test
    fun `exactly the minimum is enough`() {
        assertNull(
            SyncPreconditions.check(
                freeBytes = SyncPreconditions.MIN_FREE_BYTES,
                storageRootExists = true,
                hasStoragePermission = true,
                hasNetwork = true
            )
        )
    }

    @Test
    fun `a revoked permission is reported`() {
        val error = SyncPreconditions.check(plenty, storageRootExists = true, hasStoragePermission = false, hasNetwork = true)
        assertEquals(SyncErrorCode.PERMISSION_LOST, error?.code)
    }

    @Test
    fun `a missing storage root is reported as a permission problem`() {
        val error = SyncPreconditions.check(plenty, storageRootExists = false, hasStoragePermission = true, hasNetwork = true)
        assertEquals(SyncErrorCode.PERMISSION_LOST, error?.code)
    }

    @Test
    fun `no network is reported`() {
        val error = SyncPreconditions.check(plenty, storageRootExists = true, hasStoragePermission = true, hasNetwork = false)
        assertEquals(SyncErrorCode.NO_NETWORK, error?.code)
    }

    @Test
    fun `storage is checked before the network so the actionable problem wins`() {
        val error = SyncPreconditions.check(
            freeBytes = 0,
            storageRootExists = true,
            hasStoragePermission = true,
            hasNetwork = false
        )
        assertEquals(SyncErrorCode.LOW_STORAGE, error?.code)
    }

    @Test
    fun `the minimum is the 250 MB the spec fixes`() {
        assertEquals(250L * 1024 * 1024, SyncPreconditions.MIN_FREE_BYTES)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*SyncPreconditionsTest*'`
Expected: FAIL — unresolved reference `SyncPreconditions`.

- [ ] **Step 3: Implement `SyncPreconditions.kt`**

```kotlin
package de.nereide.strohhalm.work

import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode

/**
 * The conditions a sync needs, expressed as plain values so the ordering and
 * thresholds are unit-testable without a device.
 *
 * These are checked *inside* the worker rather than declared as WorkManager
 * constraints. A constraint defers the work silently — the code never runs and
 * so can never report why — which would defeat the requirement to tell the user
 * when a sync cannot proceed.
 */
object SyncPreconditions {

    const val MIN_FREE_BYTES: Long = 250L * 1024 * 1024

    /**
     * Returns the reason a sync must not start, or null when it may proceed.
     * Storage is evaluated before the network so the user is shown the problem
     * they can actually act on.
     */
    fun check(
        freeBytes: Long,
        storageRootExists: Boolean,
        hasStoragePermission: Boolean,
        hasNetwork: Boolean,
    ): SyncError? = when {
        freeBytes < MIN_FREE_BYTES ->
            SyncError(SyncErrorCode.LOW_STORAGE, "$freeBytes bytes free")

        !hasStoragePermission || !storageRootExists ->
            SyncError(SyncErrorCode.PERMISSION_LOST)

        !hasNetwork ->
            SyncError(SyncErrorCode.NO_NETWORK)

        else -> null
    }
}
```

- [ ] **Step 4: Run and confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*SyncPreconditionsTest*'`
Expected: PASS, 8 tests.

- [ ] **Step 5: Implement `SyncWorker.kt`**

```kotlin
package de.nereide.strohhalm.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import de.nereide.strohhalm.StrohhalmApp
import de.nereide.strohhalm.domain.MirrorOutcome
import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode
import de.nereide.strohhalm.domain.SyncErrors
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Mirrors every configured repository once.
 *
 * Registered with [androidx.work.Constraints.NONE] on purpose — see
 * [SyncPreconditions]. The worker always starts, checks its own conditions, and
 * reports what it found.
 *
 * A failure in one repository never aborts the others: partial backups beat none.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val container get() = (applicationContext as StrohhalmApp).container
    private val notifier by lazy { SyncNotifier(applicationContext) }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        notifier.ensureChannels()
        val notification = notifier.progress(applicationContext.getString(
            de.nereide.strohhalm.R.string.notification_progress_title
        ))
        // The first mirror of a large repository exceeds WorkManager's ten-minute
        // window, so the work runs in the foreground.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationIds.PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NotificationIds.PROGRESS, notification)
        }
    }

    override suspend fun doWork(): Result {
        notifier.ensureChannels()
        val settings = container.settingsRepository
        val repos = container.repoRepository
        val mirror = container.gitMirror

        val root = runCatching { settings.requireStorageRoot() }.getOrNull()
        val notifyEnabled = runCatching { settings.notifyOnFailure.first() }.getOrDefault(true)

        val blocked = SyncPreconditions.check(
            freeBytes = freeBytesAt(root),
            storageRootExists = root?.isDirectory == true,
            hasStoragePermission = hasStoragePermission(),
            hasNetwork = hasNetwork()
        )
        if (blocked != null) {
            if (notifyEnabled) notifier.notifyFailure(blocked, repoName = null)
            return if (blocked.code == SyncErrorCode.PERMISSION_LOST) {
                Result.failure()
            } else {
                Result.retry()
            }
        }

        setForeground(getForegroundInfo())

        var failures = 0
        var lastError: SyncError? = null
        var lastFailedName: String? = null

        for (repo in repos.all()) {
            repos.markSyncing(repo.id)
            val outcome = runCatching {
                mirror.sync(repo.remoteUrl, File(repo.localPath), repo.hostKeyFingerprint)
            }.getOrElse { t -> MirrorOutcome.Failure(SyncErrors.fromException(t)) }

            when (outcome) {
                is MirrorOutcome.Success -> repos.markSuccess(repo.id, outcome.sizeBytes)
                is MirrorOutcome.Failure -> {
                    repos.markFailure(repo.id, outcome.error)
                    failures++
                    lastError = outcome.error
                    lastFailedName = repo.displayName
                }
            }
        }

        return when {
            failures == 0 -> {
                notifier.clearFailures()
                Result.success()
            }
            notifyEnabled && failures == 1 -> {
                notifier.notifyFailure(lastError!!, lastFailedName)
                Result.success()
            }
            notifyEnabled -> {
                notifier.notifyFailureCount(failures)
                Result.success()
            }
            else -> Result.success()
        }
    }

    private fun freeBytesAt(root: File?): Long {
        val target = root?.takeIf { it.isDirectory } ?: Environment.getExternalStorageDirectory()
        return runCatching { StatFs(target.path).availableBytes }.getOrDefault(0L)
    }

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    private fun hasNetwork(): Boolean {
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
```

Note the return values: a run where individual repositories failed still reports `Result.success()`, because the *worker* did its job — the failures are recorded on the rows and surfaced as notifications. Returning `retry()` there would make WorkManager back off exponentially and drift away from the user's chosen interval.

- [ ] **Step 6: Implement `SyncScheduler.kt`**

```kotlin
package de.nereide.strohhalm.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.nereide.strohhalm.data.SyncInterval
import java.util.concurrent.TimeUnit

/**
 * Owns the registration of [SyncWorker].
 *
 * [Constraints.NONE] is deliberate: constraints defer work silently, and the
 * worker must run in order to report why it could not proceed.
 */
object SyncScheduler {

    const val UNIQUE_WORK_NAME = "de.nereide.strohhalm.work.SyncWorker"
    private const val ONE_SHOT_WORK_NAME = "de.nereide.strohhalm.work.SyncWorker.now"

    /**
     * Applies [interval], replacing any existing registration. `MANUAL` cancels
     * the periodic work entirely — manual syncs still run via [syncNow].
     */
    fun apply(context: Context, interval: SyncInterval) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val minutes = interval.minutes
        if (minutes == null) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.NONE)
            .build()
        // UPDATE rather than KEEP: re-applying must actually change the interval.
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** Runs one sync immediately, regardless of the configured interval. */
    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.NONE)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ONE_SHOT_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
```

- [ ] **Step 7: Apply the stored interval on startup**

Replace `StrohhalmApp.kt`:

```kotlin
package de.nereide.strohhalm

import android.app.Application
import de.nereide.strohhalm.work.SyncScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Application entry point. Builds the [AppContainer] which workers and screens
 * reach via `(context.applicationContext as StrohhalmApp).container`.
 */
class StrohhalmApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)

        // Re-apply the stored interval on every start: WorkManager registrations
        // do not survive a reinstall, and UPDATE makes this idempotent.
        container.applicationScope.launch {
            val interval = container.settingsRepository.syncInterval.first()
            SyncScheduler.apply(this@StrohhalmApp, interval)
        }
    }
}
```

- [ ] **Step 8: Build, test and commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass.

```bash
git add -A
git commit -m "feat(work): periodic mirror sync with self-checked preconditions"
```

> **Coverage note:** `SyncWorker` and `SyncScheduler` themselves have no automated test. Their logic that *can* be tested without a device — the precondition ordering and thresholds — was extracted into `SyncPreconditions` and is covered. Verifying the WorkManager wiring itself needs `work-testing` and an instrumented run, which is deferred to Task 13's manual verification rather than pretended at here.

---

## Task 10: Navigation and onboarding

Onboarding exists because Strohhalm cannot function until three things are granted or chosen, none of which can be requested with a single dialog.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/ui/common/ViewModelFactories.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/ui/common/StorageRootPicking.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/ui/nav/AppNavHost.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/ui/onboarding/OnboardingViewModel.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/ui/onboarding/OnboardingScreen.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `SettingsRepository`, `StorageRootResolver`, `AppContainer`.
- Produces:
  - `CreationExtras.application(): Application`, `CreationExtras.appContainer(): AppContainer`
  - `@Composable fun rememberStorageRootPicker(onPicked: (File?) -> Unit): () -> Unit`
  - `object Routes { LIST, ADD, DETAIL, SETTINGS, ONBOARDING; fun detail(id: Long): String }`
  - `@Composable fun AppNavHost(navController: NavHostController, startDestination: String)`
  - `OnboardingViewModel` with `uiState: StateFlow<OnboardingUiState>`, `fun refresh()`, `fun setStorageRoot(dir: File?)` — null means the pick was cancelled or the derived path failed its write probe

- [ ] **Step 1: Write `ViewModelFactories.kt`**

Identical in shape to `stromschnelle`'s, so every ViewModel factory below reads the same way.

```kotlin
package de.nereide.strohhalm.ui.common

import android.app.Application
import androidx.lifecycle.viewmodel.CreationExtras
import de.nereide.strohhalm.AppContainer
import de.nereide.strohhalm.StrohhalmApp

/** Resolves the [Application] from any [CreationExtras] used by a ViewModel factory. */
fun CreationExtras.application(): Application =
    this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application

/** Resolves the app's [AppContainer] from any [CreationExtras] used by a ViewModel factory. */
fun CreationExtras.appContainer(): AppContainer =
    (application() as StrohhalmApp).container
```

- [ ] **Step 2: Write `StorageRootPicking.kt`**

```kotlin
package de.nereide.strohhalm.ui.common

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import de.nereide.strohhalm.domain.StorageRootResolver
import java.io.File

/**
 * Launches the system folder picker and hands back a real filesystem path.
 *
 * The returned tree URI is discarded: access comes from MANAGE_EXTERNAL_STORAGE
 * and ordinary `java.io.File`, because JGit needs a real path and SAF only
 * hands out opaque document URIs. The picker is used purely as a chooser.
 *
 * [onPicked] receives null when the user cancelled, or when the path could not
 * be derived or written to — the caller then falls back to manual entry.
 */
@Composable
fun rememberStorageRootPicker(onPicked: (File?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            onPicked(null)
            return@rememberLauncherForActivityResult
        }
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val resolved = documentId?.let {
            StorageRootResolver.resolve(
                documentId = it,
                primaryRoot = Environment.getExternalStorageDirectory(),
                volumeLookup = volumeLookup(context)
            )
        }
        // The derivation is a guess; only a successful write proves it right.
        onPicked(resolved?.takeIf { it.mkdirs() || StorageRootResolver.isWritable(it) })
    }

    // Android 11+ refuses to return the root of primary storage, so the initial
    // URI only hints at a starting point — the user picks or creates a subfolder.
    return { launcher.launch(null) }
}

private fun volumeLookup(context: Context): (String) -> File? = { volumeId ->
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.getSystemService(StorageManager::class.java)
            ?.storageVolumes
            ?.firstOrNull { it.uuid == volumeId }
            ?.directory
    } else {
        null
    }
}
```

- [ ] **Step 3: Add the onboarding strings**

Append inside `<resources>`:

```xml
    <!-- Onboarding -->
    <string name="onboarding_title">Set up Strohhalm</string>
    <string name="onboarding_intro">Strohhalm keeps offline mirror copies of your git repositories. Three things are needed before it can start.</string>
    <string name="onboarding_storage_title">1. All files access</string>
    <string name="onboarding_storage_body">Mirrors are stored as real git repositories in a folder you choose, so they survive uninstalling the app and can be copied to a computer. Android only allows that with all-files access, which has to be granted by hand in system settings.</string>
    <string name="onboarding_storage_action">Grant access</string>
    <string name="onboarding_folder_title">2. Backup folder</string>
    <string name="onboarding_folder_body">Pick or create a folder for the mirrors. Android does not allow choosing the top level of internal storage, so create a folder such as "Strohhalm".</string>
    <string name="onboarding_folder_action">Choose folder</string>
    <string name="onboarding_folder_failed">That folder could not be used. Try another one.</string>
    <string name="onboarding_notifications_title">3. Notifications</string>
    <string name="onboarding_notifications_body">Strohhalm stays quiet while backups succeed and only notifies you when one cannot run.</string>
    <string name="onboarding_notifications_action">Allow notifications</string>
    <string name="onboarding_done">Done</string>
    <string name="onboarding_granted">Granted</string>
```

- [ ] **Step 4: Write `OnboardingViewModel.kt`**

```kotlin
package de.nereide.strohhalm.ui.onboarding

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.data.SettingsRepository
import de.nereide.strohhalm.ui.common.appContainer
import de.nereide.strohhalm.ui.common.application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

data class OnboardingUiState(
    val hasStorageAccess: Boolean = false,
    val storageRoot: String? = null,
    val hasNotificationPermission: Boolean = true,
    val folderPickFailed: Boolean = false,
) {
    val complete: Boolean get() = hasStorageAccess && storageRoot != null
}

class OnboardingViewModel(
    private val settings: SettingsRepository,
    private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /**
     * Permissions are granted in system screens the app does not control, so
     * state is re-read whenever the screen resumes rather than observed.
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                hasStorageAccess = hasStorageAccess(),
                storageRoot = settings.storageRoot.first(),
                hasNotificationPermission = hasNotificationPermission(),
            )
        }
    }

    fun setStorageRoot(dir: File?) {
        viewModelScope.launch {
            if (dir == null) {
                _uiState.value = _uiState.value.copy(folderPickFailed = true)
                return@launch
            }
            settings.setStorageRoot(dir)
            _uiState.value = _uiState.value.copy(storageRoot = dir.path, folderPickFailed = false)
        }
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                OnboardingViewModel(
                    settings = this.appContainer().settingsRepository,
                    context = this.application() as Application
                )
            }
        }
    }
}
```

- [ ] **Step 5: Write `OnboardingScreen.kt`**

```kotlin
package de.nereide.strohhalm.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.nereide.strohhalm.R
import de.nereide.strohhalm.ui.common.rememberStorageRootPicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Returning from the system all-files-access screen produces no result, so
    // the state is simply re-read when the launcher's callback fires.
    val storageAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refresh() }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refresh() }

    val pickFolder = rememberStorageRootPicker { dir -> viewModel.setStorageRoot(dir) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(stringResource(R.string.onboarding_intro))
            Spacer(Modifier.height(16.dp))

            Step(
                title = stringResource(R.string.onboarding_storage_title),
                body = stringResource(R.string.onboarding_storage_body),
                action = stringResource(R.string.onboarding_storage_action),
                satisfied = uiState.hasStorageAccess,
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        storageAccessLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }
            )
            Spacer(Modifier.height(12.dp))

            Step(
                title = stringResource(R.string.onboarding_folder_title),
                body = if (uiState.folderPickFailed) {
                    stringResource(R.string.onboarding_folder_failed)
                } else {
                    uiState.storageRoot ?: stringResource(R.string.onboarding_folder_body)
                },
                action = stringResource(R.string.onboarding_folder_action),
                satisfied = uiState.storageRoot != null,
                enabled = uiState.hasStorageAccess,
                onAction = pickFolder
            )
            Spacer(Modifier.height(12.dp))

            Step(
                title = stringResource(R.string.onboarding_notifications_title),
                body = stringResource(R.string.onboarding_notifications_body),
                action = stringResource(R.string.onboarding_notifications_action),
                satisfied = uiState.hasNotificationPermission,
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDone,
                enabled = uiState.complete,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.onboarding_done))
            }
        }
    }
}

@Composable
private fun Step(
    title: String,
    body: String,
    action: String,
    satisfied: Boolean,
    onAction: () -> Unit,
    enabled: Boolean = true,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            if (satisfied) {
                Text(
                    stringResource(R.string.onboarding_granted),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Button(onClick = onAction, enabled = enabled) { Text(action) }
            }
        }
    }
}
```

- [ ] **Step 6: Write `AppNavHost.kt`**

Screens referenced here are created in Tasks 11 and 12; write the nav host now and those tasks fill it in. To keep the app compiling in between, add the remaining routes in Task 11 Step 1 and Task 12 Step 1 rather than all at once.

```kotlin
package de.nereide.strohhalm.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.nereide.strohhalm.ui.onboarding.OnboardingScreen

/** Route definitions for the app's single-activity navigation graph. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val LIST = "list"
    const val ADD = "add"
    const val DETAIL = "detail/{id}"
    const val SETTINGS = "settings"

    fun detail(id: Long) = "detail/$id"
}

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.LIST,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onDone = {
                    navController.navigate(Routes.LIST) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }
    }
}
```

- [ ] **Step 7: Update `MainActivity.kt` to choose the start destination**

```kotlin
package de.nereide.strohhalm

import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.compose.rememberNavController
import de.nereide.strohhalm.ui.nav.AppNavHost
import de.nereide.strohhalm.ui.nav.Routes
import de.nereide.strohhalm.ui.theme.StrohhalmTheme
import kotlinx.coroutines.flow.first

/**
 * Single-activity host. Onboarding is the start destination until all-files
 * access has been granted and a mirror folder chosen — without both, every
 * other screen would fail on its first action.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            StrohhalmTheme {
                // Null until the stored settings have been read; rendering the
                // wrong start destination first would flash onboarding at users
                // who have already completed it.
                var startDestination by mutableStateOf<String?>(null)

                LaunchedEffect(Unit) {
                    val container = (application as StrohhalmApp).container
                    val root = container.settingsRepository.storageRoot.first()
                    startDestination = if (root != null && hasStorageAccess()) {
                        Routes.LIST
                    } else {
                        Routes.ONBOARDING
                    }
                }

                startDestination?.let { destination ->
                    AppNavHost(
                        navController = rememberNavController(),
                        startDestination = destination
                    )
                }
            }
        }
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
}
```

- [ ] **Step 8: Build, install and walk through onboarding**

Run: `./gradlew installDebug && adb shell am start -n de.nereide.strohhalm/.MainActivity`
Expected: the onboarding screen appears. Granting all-files access, choosing a folder and allowing notifications each flips its card to "Granted"; the Done button enables once the first two are satisfied. Confirm the chosen folder exists:

```bash
adb shell ls -d /storage/emulated/0/Strohhalm
```

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(ui): onboarding for storage access, mirror folder and notifications"
```

---

## Task 11: Repository list and adding a repository

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/ui/list/RepoListViewModel.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/ui/list/RepoListScreen.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/ui/add/AddRepoViewModel.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/ui/add/AddRepoScreen.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/ui/common/SyncErrorText.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/ui/nav/AppNavHost.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `RepoRepository`, `GitMirror.probeHostKey`, `SyncScheduler.syncNow`, `Repo`, `SyncStatus`, `SyncErrorCode`.
- Produces:
  - `@Composable fun syncErrorText(code: String?): String?`
  - `RepoListViewModel` with `uiState: StateFlow<RepoListUiState>`, `fun syncNow()`
  - `AddRepoViewModel` with `uiState: StateFlow<AddRepoUiState>`, `fun setUrl(String)`, `fun setName(String)`, `fun probe()`, `fun confirmFingerprint()`, `fun dismissFingerprint()`

- [ ] **Step 1: Add the list and add-repo strings**

```xml
    <!-- Repository list -->
    <string name="list_title">Repositories</string>
    <string name="list_empty">No repositories yet. Tap + to mirror one.</string>
    <string name="list_add">Add repository</string>
    <string name="list_sync_now">Sync now</string>
    <string name="list_settings">Settings</string>
    <string name="status_never">Never synced</string>
    <string name="status_syncing">Syncing…</string>
    <string name="status_ok">Last synced %1$s</string>
    <string name="status_failed">Failed</string>

    <!-- Add repository -->
    <string name="add_title">Add repository</string>
    <string name="add_url_label">Remote URL</string>
    <string name="add_url_placeholder">ssh://git@host/srv/git/repo.git</string>
    <string name="add_name_label">Name (optional)</string>
    <string name="add_probe">Continue</string>
    <string name="add_checking">Contacting the server…</string>
    <string name="add_fingerprint_title">Trust this server?</string>
    <string name="add_fingerprint_body">Strohhalm has not seen this server before. Check that the fingerprint matches the one on your server, then accept it. It will be remembered, and you will be warned if it ever changes.\n\n%1$s</string>
    <string name="add_fingerprint_accept">Trust and add</string>
    <string name="add_url_required">Enter a remote URL.</string>
```

- [ ] **Step 2: Write `SyncErrorText.kt`**

```kotlin
package de.nereide.strohhalm.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.nereide.strohhalm.R
import de.nereide.strohhalm.domain.SyncErrorCode

/**
 * Renders a stored error code as a message. The code is persisted rather than a
 * rendered string so messages stay localisable and the domain layer never holds
 * an Android resource id.
 */
@Composable
fun syncErrorText(code: String?): String? {
    val parsed = code?.let { name -> SyncErrorCode.entries.firstOrNull { it.name == name } }
        ?: return null
    return stringResource(
        when (parsed) {
            SyncErrorCode.NO_NETWORK -> R.string.error_no_network
            SyncErrorCode.LOW_STORAGE -> R.string.error_low_storage
            SyncErrorCode.PERMISSION_LOST -> R.string.error_permission_lost
            SyncErrorCode.AUTH_FAILED -> R.string.error_auth_failed
            SyncErrorCode.HOST_KEY_MISMATCH -> R.string.error_host_key_mismatch
            SyncErrorCode.HOST_UNREACHABLE -> R.string.error_host_unreachable
            SyncErrorCode.REMOTE_ERROR -> R.string.error_remote_error
            SyncErrorCode.LOCAL_CORRUPT -> R.string.error_local_corrupt
            SyncErrorCode.UNKNOWN -> R.string.error_unknown
        }
    )
}
```

- [ ] **Step 3: Write `RepoListViewModel.kt`**

```kotlin
package de.nereide.strohhalm.ui.list

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.data.Repo
import de.nereide.strohhalm.domain.RepoRepository
import de.nereide.strohhalm.ui.common.appContainer
import de.nereide.strohhalm.ui.common.application
import de.nereide.strohhalm.work.SyncScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class RepoListUiState(
    val repos: List<Repo> = emptyList(),
    val loading: Boolean = true,
)

class RepoListViewModel(
    repository: RepoRepository,
    private val context: Context,
) : ViewModel() {

    val uiState: StateFlow<RepoListUiState> = repository.observeAll()
        .map { repos -> RepoListUiState(repos = repos, loading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RepoListUiState())

    fun syncNow() = SyncScheduler.syncNow(context)

    companion object {
        val Factory = viewModelFactory {
            initializer {
                RepoListViewModel(
                    repository = this.appContainer().repoRepository,
                    context = this.application()
                )
            }
        }
    }
}
```

- [ ] **Step 4: Write `RepoListScreen.kt`**

```kotlin
package de.nereide.strohhalm.ui.list

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.nereide.strohhalm.R
import de.nereide.strohhalm.data.Repo
import de.nereide.strohhalm.data.SyncStatus
import de.nereide.strohhalm.ui.common.syncErrorText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoListScreen(
    onOpenRepo: (Long) -> Unit,
    onAddRepo: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: RepoListViewModel = viewModel(factory = RepoListViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.list_title)) },
                actions = {
                    IconButton(onClick = { viewModel.syncNow() }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.list_sync_now))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.list_settings))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddRepo) {
                Icon(Icons.Filled.Add, stringResource(R.string.list_add))
            }
        }
    ) { padding ->
        if (uiState.repos.isEmpty() && !uiState.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.list_empty))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
            ) {
                items(uiState.repos, key = { it.id }) { repo ->
                    RepoRow(repo = repo, onClick = { onOpenRepo(repo.id) })
                }
            }
        }
    }
}

@Composable
private fun RepoRow(repo: Repo, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(repo.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = statusLine(repo),
                style = MaterialTheme.typography.bodySmall,
                color = if (repo.lastStatus == SyncStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (repo.sizeBytes > 0) {
            Text(
                text = Formatter.formatShortFileSize(context, repo.sizeBytes),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun statusLine(repo: Repo): String = when (repo.lastStatus) {
    SyncStatus.NEVER -> stringResource(R.string.status_never)
    SyncStatus.SYNCING -> stringResource(R.string.status_syncing)
    SyncStatus.OK -> stringResource(
        R.string.status_ok,
        repo.lastSyncAt?.let {
            DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        } ?: ""
    )
    // Show what actually went wrong rather than a bare "Failed" — the whole
    // point of the error taxonomy is that the user learns what to do next.
    SyncStatus.FAILED -> syncErrorText(repo.lastErrorCode) ?: stringResource(R.string.status_failed)
}
```

- [ ] **Step 5: Write `AddRepoViewModel.kt`**

```kotlin
package de.nereide.strohhalm.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.domain.GitMirror
import de.nereide.strohhalm.domain.RepoRepository
import de.nereide.strohhalm.domain.SyncErrors
import de.nereide.strohhalm.ui.common.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AddRepoUiState(
    val url: String = "",
    val name: String = "",
    val probing: Boolean = false,
    val fingerprint: String? = null,
    val errorCode: String? = null,
    val saved: Boolean = false,
)

/**
 * Adding a repository is a two-phase flow: probe the server for its host key,
 * show the fingerprint for confirmation, and only then create the row. Nothing
 * is persisted until the user has accepted the key.
 */
class AddRepoViewModel(
    private val repository: RepoRepository,
    private val mirror: GitMirror,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddRepoUiState())
    val uiState: StateFlow<AddRepoUiState> = _uiState.asStateFlow()

    fun setUrl(value: String) {
        _uiState.value = _uiState.value.copy(url = value, errorCode = null)
    }

    fun setName(value: String) {
        _uiState.value = _uiState.value.copy(name = value)
    }

    fun probe() {
        val url = _uiState.value.url.trim()
        if (url.isEmpty()) return
        _uiState.value = _uiState.value.copy(probing = true, errorCode = null)
        viewModelScope.launch {
            mirror.probeHostKey(url)
                .onSuccess { fingerprint ->
                    _uiState.value = _uiState.value.copy(probing = false, fingerprint = fingerprint)
                }
                .onFailure { t ->
                    _uiState.value = _uiState.value.copy(
                        probing = false,
                        errorCode = SyncErrors.fromException(t).code.name
                    )
                }
        }
    }

    fun confirmFingerprint() {
        val state = _uiState.value
        val fingerprint = state.fingerprint ?: return
        viewModelScope.launch {
            repository.add(
                displayName = state.name.trim(),
                remoteUrl = state.url.trim(),
                hostKeyFingerprint = fingerprint
            )
            _uiState.value = state.copy(fingerprint = null, saved = true)
        }
    }

    fun dismissFingerprint() {
        _uiState.value = _uiState.value.copy(fingerprint = null)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                AddRepoViewModel(
                    repository = this.appContainer().repoRepository,
                    mirror = this.appContainer().gitMirror
                )
            }
        }
    }
}
```

- [ ] **Step 6: Write `AddRepoScreen.kt`**

```kotlin
package de.nereide.strohhalm.ui.add

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.nereide.strohhalm.R
import de.nereide.strohhalm.ui.common.syncErrorText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRepoScreen(
    onDone: () -> Unit,
    viewModel: AddRepoViewModel = viewModel(factory = AddRepoViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            OutlinedTextField(
                value = uiState.url,
                onValueChange = viewModel::setUrl,
                label = { Text(stringResource(R.string.add_url_label)) },
                placeholder = { Text(stringResource(R.string.add_url_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = uiState.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.add_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            syncErrorText(uiState.errorCode)?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
            }

            if (uiState.probing) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.add_checking))
            } else {
                Button(
                    onClick = viewModel::probe,
                    enabled = uiState.url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.add_probe))
                }
            }
        }
    }

    uiState.fingerprint?.let { fingerprint ->
        AlertDialog(
            onDismissRequest = viewModel::dismissFingerprint,
            title = { Text(stringResource(R.string.add_fingerprint_title)) },
            text = { Text(stringResource(R.string.add_fingerprint_body, fingerprint)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmFingerprint) {
                    Text(stringResource(R.string.add_fingerprint_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissFingerprint) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
```

- [ ] **Step 7: Wire the routes into `AppNavHost.kt`**

Add inside the `NavHost` block, and the matching imports:

```kotlin
        composable(Routes.LIST) {
            RepoListScreen(
                onOpenRepo = { id -> navController.navigate(Routes.detail(id)) },
                onAddRepo = { navController.navigate(Routes.ADD) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.ADD) {
            AddRepoScreen(onDone = { navController.popBackStack() })
        }
```

- [ ] **Step 8: Build, install and add a repository**

Run: `./gradlew installDebug`

On the device: tap +, enter a real `ssh://` remote, tap Continue. Expected: the fingerprint dialog appears showing `SHA256:…`. Accepting returns to the list with the repository showing "Never synced".

The public key must already be on the server, which Task 12's settings screen provides — if authentication is not yet set up, the probe still succeeds, because the host key is read before authentication.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(ui): repository list and host-key-confirmed adding"
```

---

## Task 12: Repository detail and settings

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/ui/detail/RepoDetailViewModel.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/ui/detail/RepoDetailScreen.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/ui/settings/SettingsViewModel.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/ui/nav/AppNavHost.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `RepoRepository`, `SettingsRepository`, `SshKeyStore`, `SyncInterval`, `SyncScheduler`.
- Produces: `RepoDetailViewModel`, `RepoDetailScreen(id, onBack)`, `SettingsViewModel`, `SettingsScreen(onBack)`.

- [ ] **Step 1: Add the detail and settings strings**

```xml
    <!-- Repository detail -->
    <string name="detail_title">Repository</string>
    <string name="detail_remote">Remote</string>
    <string name="detail_local">Local mirror</string>
    <string name="detail_size">Size</string>
    <string name="detail_last_sync">Last successful sync</string>
    <string name="detail_last_attempt">Last attempt</string>
    <string name="detail_fingerprint">Host key</string>
    <string name="detail_never">Never</string>
    <string name="detail_delete_title">Delete repository?</string>
    <string name="detail_delete_body">Strohhalm will stop mirroring it.</string>
    <string name="detail_delete_files">Also delete the local mirror from storage</string>

    <!-- Settings -->
    <string name="settings_title">Settings</string>
    <string name="settings_interval_title">Sync every</string>
    <string name="settings_interval_manual">Manual only</string>
    <string name="settings_interval_m15">15 minutes</string>
    <string name="settings_interval_m30">30 minutes</string>
    <string name="settings_interval_h1">Hour</string>
    <string name="settings_interval_h3">3 hours</string>
    <string name="settings_interval_h6">6 hours</string>
    <string name="settings_interval_h12">12 hours</string>
    <string name="settings_interval_d1">Day</string>
    <string name="settings_folder_title">Backup folder</string>
    <string name="settings_folder_change">Change folder</string>
    <string name="settings_folder_warning">Changing the folder does not move existing mirrors. They will be cloned again into the new location.</string>
    <string name="settings_key_title">Public key</string>
    <string name="settings_key_body">Add this line to the server\'s authorized_keys, or as a deploy key, so Strohhalm may read the repositories.</string>
    <string name="settings_key_copy">Copy public key</string>
    <string name="settings_key_copied">Public key copied</string>
    <string name="settings_key_regenerate">Regenerate key</string>
    <string name="settings_key_regenerate_title">Regenerate the key?</string>
    <string name="settings_key_regenerate_body">A new key is generated and the current one stops working immediately. Every server has to be updated with the new public key before backups resume.</string>
    <string name="settings_notify_title">Notify when a backup fails</string>
```

- [ ] **Step 2: Write `RepoDetailViewModel.kt`**

```kotlin
package de.nereide.strohhalm.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.data.Repo
import de.nereide.strohhalm.domain.RepoRepository
import de.nereide.strohhalm.ui.common.appContainer
import de.nereide.strohhalm.ui.common.application
import de.nereide.strohhalm.work.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RepoDetailViewModel(
    private val id: Long,
    private val repository: RepoRepository,
    private val context: Context,
) : ViewModel() {

    val repo: StateFlow<Repo?> = repository.observe(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    /** Enqueues the shared worker; it syncs every repository, this one included. */
    fun syncNow() = SyncScheduler.syncNow(context)

    fun delete(alsoDeleteFiles: Boolean) {
        viewModelScope.launch {
            val current = repository.observe(id).first()
            repository.delete(id)
            if (alsoDeleteFiles && current != null) {
                withContext(Dispatchers.IO) {
                    runCatching { File(current.localPath).deleteRecursively() }
                }
            }
            _deleted.value = true
        }
    }

    companion object {
        fun factory(id: Long) = viewModelFactory {
            initializer {
                RepoDetailViewModel(
                    id = id,
                    repository = this.appContainer().repoRepository,
                    context = this.application()
                )
            }
        }
    }
}
```

- [ ] **Step 3: Write `RepoDetailScreen.kt`**

```kotlin
package de.nereide.strohhalm.ui.detail

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.nereide.strohhalm.R
import de.nereide.strohhalm.ui.common.syncErrorText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailScreen(
    id: Long,
    onBack: () -> Unit,
    viewModel: RepoDetailViewModel = viewModel(factory = RepoDetailViewModel.factory(id))
) {
    val repo by viewModel.repo.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var alsoDeleteFiles by remember { mutableStateOf(false) }

    LaunchedEffect(deleted) { if (deleted) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(repo?.displayName ?: stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.syncNow() }) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.list_sync_now))
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.delete))
                    }
                }
            )
        }
    ) { padding ->
        val current = repo ?: return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            syncErrorText(current.lastErrorCode)?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
                current.lastErrorDetail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
            }

            Field(stringResource(R.string.detail_remote), current.remoteUrl)
            Field(stringResource(R.string.detail_local), current.localPath)
            Field(
                stringResource(R.string.detail_size),
                Formatter.formatShortFileSize(context, current.sizeBytes)
            )
            Field(
                stringResource(R.string.detail_last_sync),
                current.lastSyncAt?.let { relative(it) } ?: stringResource(R.string.detail_never)
            )
            Field(
                stringResource(R.string.detail_last_attempt),
                current.lastAttemptAt?.let { relative(it) } ?: stringResource(R.string.detail_never)
            )
            Field(
                stringResource(R.string.detail_fingerprint),
                current.hostKeyFingerprint ?: stringResource(R.string.detail_never)
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.detail_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.detail_delete_body))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = alsoDeleteFiles,
                            onCheckedChange = { alsoDeleteFiles = it }
                        )
                        Text(stringResource(R.string.detail_delete_files))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(alsoDeleteFiles)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun relative(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
```

The delete dialog defaults to *keeping* the mirror. Deleting a backup should be a deliberate second choice, not the consequence of removing a row.

- [ ] **Step 4: Write `SettingsViewModel.kt`**

```kotlin
package de.nereide.strohhalm.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.data.SettingsRepository
import de.nereide.strohhalm.data.SyncInterval
import de.nereide.strohhalm.domain.SshKeyStore
import de.nereide.strohhalm.ui.common.appContainer
import de.nereide.strohhalm.ui.common.application
import de.nereide.strohhalm.work.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class SettingsUiState(
    val interval: SyncInterval = SettingsRepository.DEFAULT_SYNC_INTERVAL,
    val storageRoot: String? = null,
    val notifyOnFailure: Boolean = true,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val keyStore: SshKeyStore,
    private val context: Context,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.syncInterval,
        settings.storageRoot,
        settings.notifyOnFailure
    ) { interval, root, notify ->
        SettingsUiState(interval = interval, storageRoot = root, notifyOnFailure = notify)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private val _publicKey = MutableStateFlow<String?>(null)
    val publicKey: StateFlow<String?> = _publicKey.asStateFlow()

    init {
        // Generating the key here means it exists by the time the user goes
        // looking for it, rather than on the first sync attempt.
        viewModelScope.launch { _publicKey.value = keyStore.publicKeyLine() }
    }

    fun setInterval(interval: SyncInterval) {
        viewModelScope.launch {
            settings.setSyncInterval(interval)
            SyncScheduler.apply(context, interval)
        }
    }

    fun setStorageRoot(dir: File?) {
        if (dir == null) return
        viewModelScope.launch { settings.setStorageRoot(dir) }
    }

    fun setNotifyOnFailure(enabled: Boolean) {
        viewModelScope.launch { settings.setNotifyOnFailure(enabled) }
    }

    fun regenerateKey() {
        viewModelScope.launch {
            keyStore.regenerate()
            _publicKey.value = keyStore.publicKeyLine()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settings = this.appContainer().settingsRepository,
                    keyStore = this.appContainer().sshKeyStore,
                    context = this.application()
                )
            }
        }
    }
}
```

- [ ] **Step 5: Write `SettingsScreen.kt`**

```kotlin
package de.nereide.strohhalm.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.nereide.strohhalm.R
import de.nereide.strohhalm.data.SyncInterval
import de.nereide.strohhalm.ui.common.rememberStorageRootPicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val publicKey by viewModel.publicKey.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickFolder = rememberStorageRootPicker { dir -> viewModel.setStorageRoot(dir) }
    var confirmRegenerate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                stringResource(R.string.settings_interval_title),
                style = MaterialTheme.typography.titleMedium
            )
            SyncInterval.entries.forEach { interval ->
                val selected = interval == uiState.interval
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, onClick = { viewModel.setInterval(interval) })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected, onClick = { viewModel.setInterval(interval) })
                    Text(intervalLabel(interval), modifier = Modifier.padding(start = 8.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(
                stringResource(R.string.settings_folder_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(uiState.storageRoot ?: "", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_folder_warning),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = pickFolder) { Text(stringResource(R.string.settings_folder_change)) }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(
                stringResource(R.string.settings_key_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.settings_key_body),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = publicKey ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    onClick = { publicKey?.let { copyPublicKey(context, it) } },
                    enabled = publicKey != null
                ) {
                    Text(stringResource(R.string.settings_key_copy))
                }
                Spacer(Modifier.padding(horizontal = 4.dp))
                TextButton(onClick = { confirmRegenerate = true }) {
                    Text(stringResource(R.string.settings_key_regenerate))
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.settings_notify_title),
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = uiState.notifyOnFailure,
                    onCheckedChange = viewModel::setNotifyOnFailure
                )
            }
        }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text(stringResource(R.string.settings_key_regenerate_title)) },
            text = { Text(stringResource(R.string.settings_key_regenerate_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRegenerate = false
                    viewModel.regenerateKey()
                }) { Text(stringResource(R.string.settings_key_regenerate)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerate = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun intervalLabel(interval: SyncInterval): String = stringResource(
    when (interval) {
        SyncInterval.MANUAL -> R.string.settings_interval_manual
        SyncInterval.M15 -> R.string.settings_interval_m15
        SyncInterval.M30 -> R.string.settings_interval_m30
        SyncInterval.H1 -> R.string.settings_interval_h1
        SyncInterval.H3 -> R.string.settings_interval_h3
        SyncInterval.H6 -> R.string.settings_interval_h6
        SyncInterval.H12 -> R.string.settings_interval_h12
        SyncInterval.D1 -> R.string.settings_interval_d1
    }
)

/**
 * Android 13+ shows its own confirmation when something is copied, so the app
 * only adds a toast below that version — otherwise the user sees two.
 */
private fun copyPublicKey(context: Context, key: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Strohhalm public key", key))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.settings_key_copied, Toast.LENGTH_SHORT).show()
    }
}
```

- [ ] **Step 6: Wire the remaining routes into `AppNavHost.kt`**

Add the imports for `RepoDetailScreen`, `SettingsScreen`, `NavType` and `navArgument`, then inside the `NavHost` block:

```kotlin
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: return@composable
            RepoDetailScreen(id = id, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
```

- [ ] **Step 7: Build, install and verify the whole loop end to end**

Run: `./gradlew installDebug`

On the device:
1. Settings → copy the public key, paste it into the server's `authorized_keys`.
2. Add a repository, accept the fingerprint.
3. Tap sync now. Expected: a progress notification appears, then the row shows a relative "Last synced" time and a size.
4. Confirm the mirror is a real bare repository:

```bash
adb shell ls /storage/emulated/0/Strohhalm/
adb shell ls /storage/emulated/0/Strohhalm/<slug>.git
```

Expected: `HEAD`, `objects`, `refs`, `config` — and no working-tree files.

5. Pull the mirror off the device and prove the recovery path works:

```bash
adb pull /storage/emulated/0/Strohhalm/<slug>.git /tmp/recovered.git
git clone /tmp/recovered.git /tmp/recovered
git -C /tmp/recovered branch -a
git -C /tmp/recovered tag
```

Expected: a working copy with every branch and tag from the origin. This is the whole point of the app; if it fails, nothing else matters.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(ui): repository detail and settings with public key export"
```

---

## Task 13: Release readiness

**Files:**
- Create: `README.md`
- Create: `LICENSE`
- Create: `scripts/release.sh`
- Create: `app/src/androidTest/java/de/nereide/strohhalm/data/DatabaseTest.kt`
- Modify: `app/src/main/res/values/strings.xml` (only if gaps are found)

**Interfaces:**
- Consumes: everything above.
- Produces: a signed release APK and a documented repository.

- [ ] **Step 1: Write a Room smoke test**

There is no migration to test at version 1, but the schema must be proven to build and the unique constraint to hold.

```kotlin
package de.nereide.strohhalm.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {

    private lateinit var db: StrohhalmDatabase

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, StrohhalmDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun roundTripsARepositoryIncludingItsStatus() = runBlocking {
        val dao = db.repoDao()
        val id = dao.insert(
            Repo(
                displayName = "Notes",
                remoteUrl = "ssh://host/notes.git",
                localPath = "/storage/emulated/0/Strohhalm/notes.git",
                lastStatus = SyncStatus.OK,
                createdAt = 1_000
            )
        )

        val loaded = dao.byId(id)!!
        assertEquals("Notes", loaded.displayName)
        assertEquals(SyncStatus.OK, loaded.lastStatus)
    }

    @Test
    fun refusesTwoRepositoriesInTheSameDirectory() = runBlocking {
        val dao = db.repoDao()
        val row = Repo(
            displayName = "A",
            remoteUrl = "ssh://host/a.git",
            localPath = "/storage/emulated/0/Strohhalm/a.git",
            createdAt = 1
        )
        dao.insert(row)

        val failed = runCatching { dao.insert(row.copy(displayName = "B")) }.isFailure
        assertTrue("the unique constraint on localPath did not hold", failed)
    }
}
```

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew :app:testDebugUnitTest connectedDebugAndroidTest`
Expected: all unit and instrumented tests pass. Record the actual counts; do not claim success without reading the output.

- [ ] **Step 3: Copy the release script and licence**

```bash
cd /home/christoph/Projects/strohhalm
cp /home/christoph/Projects/stromschnelle/LICENSE .
mkdir -p scripts
cp /home/christoph/Projects/stromschnelle/scripts/release.sh scripts/ 2>/dev/null || true
```

If `stromschnelle` has no `scripts/release.sh`, write one that bumps `versionName` in `version.properties`, commits, tags `v<version>` and runs `./gradlew assembleRelease`.

- [ ] **Step 4: Write `README.md`**

```markdown
# Strohhalm

Keeps offline mirror copies of remote git repositories on an Android device.

Strohhalm pulls; it never pushes. Each repository is stored as a bare
`git clone --mirror`, so every branch, tag and ref is captured — not just the
default branch — and upstream deletions are pruned on the next sync.

## Recovery

Mirrors are ordinary bare repositories. Restoring one needs no Strohhalm:

    adb pull /storage/emulated/0/Strohhalm/myrepo.git .
    git clone myrepo.git myrepo

## Setup

1. Grant all-files access when prompted. Mirrors are stored in a folder you
   choose so they survive uninstalling the app; Android only permits that with
   this permission.
2. Choose or create a backup folder.
3. Copy the public key from Settings into your server's `authorized_keys`, or
   add it as a read-only deploy key.
4. Add a repository by its `ssh://` URL and confirm the host key fingerprint.

## Security

- An Ed25519 key is generated on device. The private key never leaves it: only
  the 32-byte seed is stored, encrypted with AES-256-GCM under a key held in the
  Android Keystore, in internal storage — never in the backup folder.
- Host keys are pinned on first use. If a server later presents a different key,
  syncing stops and you are notified rather than silently trusting it.
- `android:allowBackup` is off: a restored backup would contain a key blob that
  cannot be decrypted on the new device.

## Sync behaviour

The interval is configurable from 15 minutes to daily, or manual only.
Syncing is registered without WorkManager constraints on purpose — a constraint
defers work silently, and Strohhalm is built to tell you when a backup could not
run. The worker checks free space, storage access and connectivity itself, and
notifies when any of them blocks a sync.

Notifications appear only on failure. Success is silent.

## Build

    ./gradlew assembleDebug

A release build is signed when `app/keystore.properties` exists (gitignored,
with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without it,
`assembleRelease` produces an unsigned APK.

## Licence

See `LICENSE`.
```

- [ ] **Step 5: Build a release APK**

Run: `./gradlew assembleRelease`
Expected: `BUILD SUCCESSFUL` and an APK under `app/build/outputs/apk/release/`. Confirm signing:

```bash
ls -la app/build/outputs/apk/release/
```

An `-unsigned.apk` name means `app/keystore.properties` is absent, which is a valid outcome — create it from the existing keystore if a signed build is wanted.

- [ ] **Step 6: Verify the periodic sync actually fires**

WorkManager wiring has no automated coverage, so confirm it by hand:

```bash
adb shell dumpsys jobscheduler | grep -A3 strohhalm
```

Expected: a scheduled job for the app. Then force a run and watch it:

```bash
adb logcat -c
adb shell cmd jobscheduler run -f de.nereide.strohhalm <jobId>
adb logcat -d | grep -i strohhalm
```

Expected: the sync runs and the repository row updates. Also verify the failure path: disable Wi-Fi and mobile data, force a run, and confirm a notification appears saying there is no network.

- [ ] **Step 7: Commit and tag**

```bash
cd /home/christoph/Projects/strohhalm
git add -A
git commit -m "docs: README, licence and release tooling"
git tag v0.1.0
```

---

