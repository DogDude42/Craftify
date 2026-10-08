# Craftify Rewrite — Verified Plan for Minecraft 26.1.2 (Fabric)

## Verified Versions
| Component | Version | Source |
|-----------|---------|--------|
| Minecraft | 26.1.2 | meta.fabricmc.net |
| Fabric Loader | 0.19.5 | meta.fabricmc.net |
| Fabric API | 26.1.2 | maven.fabricmc.net |
| Java | 8 (minimum) | Fabric loader meta |
| Intermediary | 0.0.0 | meta.fabricmc.net |

## Tasks (bite-sized, ordered)

### Phase 1: Gradle Build Configuration
- [ ] **T1.1** Create `build.gradle.kts` with Fabric 26.1.2 deps (loader 0.19.5, API 26.1.2, Java 8 toolchain)
- [ ] **T1.2** Create `gradle.properties` with mappings (yarn 26.1.2) and version vars
- [ ] **T1.3** Create `settings.gradle.kts` and `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.8+)
- [ ] **T1.4** Verify `./gradlew build` compiles clean

### Phase 2: Fabric Mod Metadata
- [ ] **T2.1** Update `fabric.mod.json` with exact verified versions (loader >=0.19.5, fabric >=26.1.2, minecraft ~26.1.2)
- [ ] **T2.2** Add entrypoints (main, client) and mixins config

### Phase 3: Core Controller (YTM Web)
- [ ] **T3.1** Refactor `YTMWebController.kt` with proper WebSocket client (OkHttp or Java 11 HttpClient WebSocket)
- [ ] **T3.2** Add native messaging host binary build (Go/Rust/Python — cross-platform)
- [ ] **T3.3** Add Chrome extension manifest v3 + content script (verified selectors for music.youtube.com)
- [ ] **T3.4** Add build step to package extension + host with mod JAR

### Phase 4: Integration & Testing
- [ ] **T4.1** Add Fabric mod entrypoint `Craftify.kt` + `CraftifyClient.kt` registering controller
- [ ] **T4.2** Test `./gradlew build` → produces `craftify-ytm-web-1.0.0+26.1.2.jar`
- [ ] **T4.3** Verify JAR loads on Fabric 26.1.2 dev environment

### Phase 5: Git History Cleanup
- [ ] **T5.1** Squash/amend commits to logical history
- [ ] **T5.2** Tag release `v1.0.0+26.1.2`
- [ ] **T5.3** Push tags to GitHub

## Execution: One task at a time, verify before next
