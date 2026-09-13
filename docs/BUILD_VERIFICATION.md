# Build verification

Static verification completed in the generation environment:

- package/namespace/applicationId are `com.elina.assistant`
- compileSdk/targetSdk are 36
- Java/Kotlin target is 17
- GitHub Actions workflow runs `./gradlew assembleDebug` and uploads the APK
- no legacy MYRA references remain
- no phone/SMS/WhatsApp/contacts feature identifiers or permissions remain
- supplied VRM is present at `app/src/main/assets/avatar/elina.vrm`
- avatar manifest is generated from the supplied binary and identifies it as VRM 0.x
- XML resources parse successfully
- helper VRM validator passes Python syntax compilation

A local `./gradlew assembleDebug` invocation was attempted, but this execution environment does not have Gradle/Android SDK installed and outbound DNS cannot resolve `services.gradle.org`, so a real APK was not produced here. GitHub Actions is configured to provide JDK 17, Android SDK, and Gradle 9.6.0 before invoking the same build command.
