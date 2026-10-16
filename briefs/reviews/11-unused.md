# Brief 11: Unused files and code

Report only. Nothing in the repo was changed except this file. Measured on `develop` at `f648b5e`.

Out of scope by the brief and never listed: Flyway migrations, `LICENSE`, `LICENSES/`, `THIRD_PARTY_NOTICES.md`, the Fredoka `OFL.txt`, `docs/ARCHITECTURE.md`, `CODEBASE.md`, `docs/DEPLOYMENT.md`, `src/tools/resources/ai-eval-cases.jsonl` and everything under `briefs/`.

## Result

| Confidence | Meaning | Candidates |
|---|---|---|
| SAFE | nothing references it | 0 |
| CHECK | only docs or tests reference it, or it only runs behind a flag that is off | 4 |
| KEEP? | unused, but there is a reason to keep it | 3 |
| **Total** | | **7** |

The repo is lean. No static file, property key, environment variable, main dependency, production class or firmware function came out unused. No TODO, FIXME or `@Deprecated` exists outside `briefs/`.

## How I searched

All searches were `git grep` over tracked files, `briefs/` excluded, so build output and `.pio/` were never counted.

- **Files.** For every file under `src/main/resources/static`, `src/test/resources` and `src/tools/resources`: its file name searched across the whole repo (HTML `href` and `src`, CSS `url()` and `@import`, JS, Java string constants such as `NotFoundPage`, `LinkPreview` and the page controllers, `StaticCacheConfig`, `SecurityConfig`, tests, docs). Path patterns such as `/icons/**` in `SecurityConfig` and `StaticCacheConfig` serve any file and are not counted as a use of one.
- **Build and deploy.** `build.gradle.kts`, `settings.gradle.kts`, `Dockerfile` (copies only `gradlew`, `gradle/`, the build files and `src`, runs `bootJar`, so `src/tools` and `firmware/` never reach the image), `.dockerignore`, `docker-compose.yml`, `.github/workflows/ci.yml` (jobs `build`, `secrets-scan`, `firmware` in `firmware/` only, `deploy` through the Render hook) and the Render variables in `docs/DEPLOYMENT.md`. There is no `render.yaml`; Render's settings live in its dashboard, which I cannot see.
- **Java classes.** Each class under `src/main` and `src/tools` by simple name, whole word. The ones with no reference from other code are all wired by Spring (`@Component`, `@RestController`, `@Configuration`, `@SpringBootApplication`) or named as a Gradle `mainClass` (`FakeDevice`, `NewAccessCode`, `AiEval`).
- **Methods.** 649 method declarations in `src/main` and `src/tools`, each name searched as a word or `::name` in `src/main` and `src/tools`, skipping methods annotated `@Override`, `@Bean`, `@Scheduled`, an event listener, a request mapping or `@ExceptionHandler`. Six had no caller: four are `@AssertTrue` Bean Validation checks on `@ConfigurationProperties` records (`AiProperties.isApiKeyPresentWhenEnabled`, `DemoDeviceProperties.isSecretValid`, `DemoOwnerProperties.isPasswordValid`, `TipProperties.isRangeConsistent`), called by the validator; two are listed below. Test helper classes and their methods were searched the same way; all are used.
- **Configuration.** Every key in `application.properties` against the `@ConfigurationProperties` records and `@Value` uses; every variable in `.env.example`, `docs/DEPLOYMENT.md` and `README.md` against `application.properties`, Java, the Gradle tasks, `docker-compose.yml` and the firmware. `SPRING_DATASOURCE_*` bind through Spring Boot's relaxed binding; `TIP_DB_PORT` is read by `docker-compose.yml`.
- **Dependencies.** Each `implementation` and `testImplementation` entry against the imports of `src/main`, `src/tools` and `src/test`, and against test annotations (`@DataJpaTest`, `@AutoConfigure*`).
- **Web assets inside files.** Every JS `function` name (185) counted within its file and across the other pages; every CSS class and id selector (209 selectors) searched as a substring of the HTML and JS. Nothing unused. Arrow functions assigned to `const` were not checked one by one.
- **Firmware.** Every `#define` and `constexpr k...` in `include/` and `src/*.h`, and every function declared in `lib/tip_core/src/*.h` and `src/*.h`, against `src`, `lib`, `test` and `platformio.ini`. All used by the firmware itself, not only by tests. `scripts/static_link.py` is named in `platformio.ini:32`.
- **Markers.** `TODO`, `FIXME`, `XXX`, `HACK`, `temporar`, `for now`, `debug`, `@Deprecated`, `forRemoval`, `@SuppressWarnings("unused")`.

Limits: the method search is by name, so an unused method that shares its name with a used one (`find`, `size`, `isEmpty`) would be missed; record components were not checked one by one; the CSS search is by substring, so it can only miss an unused selector, never flag a used one.

## CHECK

### 1. `src/main/resources/static/icons/icon-512-panda.png`

- **What it was for:** the 512 px panda icon from `e6142e7` ("Give the panda icons and link preview new file names"), the size a web app manifest or a store listing asks for.
- **Proof nothing uses it:** no page links it. All nine HTML pages link only `/favicon.ico`, `/icons/favicon-panda.svg` and `/icons/apple-touch-icon-panda.png`. There is no web app manifest (`manifest` appears nowhere in the repo outside `briefs/`). No JS, CSS or Java names it. It is served only because `/icons/**` is open in `SecurityConfig` and cached by `StaticCacheConfig`.
- **References left:** `CODEBASE.md:78` (the static files row) and `LinkPreviewTest.java:53`, one entry of the map in `iconsAndPreviewImageAreServedWithoutASessionAndCachedForThirtyDays`.
- **Removing it also removes:** that map entry and the file name in `CODEBASE.md:78`. No env var, no Render setting.

### 2. `TIP_DEBUG_CLIENT_IP` and `ClientIpDiagnostics`

- **What it was for:** a one time check on a new host of which proxy header carries the real client address (`fef1461`, `db77385`). With the flag on, `ClientIpValve` snapshots the connection address and raw proxy headers and the `ClientIpDiagnostics` filter logs `Client IP check:` for each `/api/public/` request. The code calls it temporary (`ClientIpDiagnostics.java:17`, `application.properties:13`, `.env.example:22`).
- **Proof it is not used in normal operation:** `tip.debug-client-ip` defaults to `false`; the filter bean is `@ConditionalOnBooleanProperty("tip.debug-client-ip")` and the valve's snapshot runs only under `if (diagnostics)`. Only `ClientIpDiagnosticsTest` (`@TestPropertySource(properties = "TIP_DEBUG_CLIENT_IP=true")`) ever turns it on. The check it exists for has been done: production reads `CF-Connecting-IP` (`CfConnectingIpHeaderTest`, "the header production sets"). Not set in `Dockerfile`, `ci.yml`, `application-test.properties` or `docker-compose.yml`.
- **References left:** `ClientIpConfig.java:25` and `:30-37`, `ClientIpValve.java:22-23`, `:34`, `:36-39`, `:44-46`, `application.properties:13-14`, `.env.example:22-23`, `docs/DEPLOYMENT.md:123` and the section "Choosing the client IP header on a new host" (`:223-238`, steps 1, 3 and 4 need it), `CODEBASE.md:7`, `:14`, `:219`.
- **Removing it also removes:** `ClientIpDiagnostics.java`; the second `@Bean` and the `diagnostics` parameter in `ClientIpConfig`; the `diagnostics` field, constructor parameter, branch and Javadoc paragraph in `ClientIpValve`; `ClientIpDiagnosticsTest` (the whole class); `BehindProxyTest.clientIpDiagnosticsLogNothingWhenOff` (`:91`); the property and the `.env.example` lines; the `DEPLOYMENT.md` row, and the new host procedure would need another way to see the headers. On Render, `TIP_DEBUG_CLIENT_IP` should be deleted from the service's environment if it is still there (a person has to look).
- **Note:** this is the only "temporary" feature in the code. The new host procedure in `DEPLOYMENT.md` is its one remaining reason to exist; if TiP will only ever run on the current host, it can go.

### 3. `ObserverSessions.isWatched(String)` (`src/main/java/dev/tip/devices/ObserverSessions.java:41`)

- **What it was for:** whether a slug has any observer session.
- **Proof nothing in the app uses it:** no caller in `src/main` or `src/tools`. `ReactionDispatcher` uses `isEmpty()` and `broadcast`, `SessionKeepalive` uses `forEach`.
- **References left:** tests only, as the point to wait on until an observer is registered or gone: `KeepaliveTest.java:94`, `:105`, `:118`; `ObserverChannelTest.java:56`, `:71`, `:89`; `PlaybackWindowTest.java:85`.
- **Removing it also removes:** those seven waits, which would need another sync point (a message from the server, or a test helper in `devices` such as `DeviceSessionProbe`). It is a three line test seam; removing it saves little.

### 4. `FakeDevice.waitingReactions()` (`src/tools/java/dev/tip/tools/FakeDevice.java:132`)

- **What it was for:** how many reactions wait behind the one playing.
- **Proof nothing in the tool uses it:** no caller in `src/tools` or `src/main`; `FakeDevice` itself never prints it.
- **References left:** `FakeDevicePartsTest.java:153`.
- **Removing it also removes:** that assertion; the test would have to read `Player.waiting()` another way. A test seam in a dev tool, never in the app jar.

## KEEP?

### 5. `firmware/spikes/pn532/` (9 files: `README.md`, `platformio.ini`, `src/main.cpp`, `src/ndef.cpp`, `src/ndef.h`, `src/pn532_target.cpp`, `src/pn532_target.h`, `src/type4_tag.cpp`, `src/type4_tag.h`)

- **What it was for:** the brief 05c PN532 spike (tag emulation and NTAG215 writing on UART2), superseded by brief 05d (`67d3eaa`), which moved the tag into the main firmware (`lib/tip_core/src/tip_type4_tag`, `tip_pn532_frame`, `src/pn532.cpp`, `src/nfc_task.cpp`).
- **Proof nothing builds or loads it:** it is its own PlatformIO project. The CI `firmware` job runs `pio run` and `pio test -e native` in `firmware/` against `firmware/platformio.ini`, which never names `spikes/`. No source in `firmware/src`, `lib` or `test` includes a spike file. Not in the Docker image.
- **References left:** `.gitignore:23` (`firmware/spikes/*/.pio/`), `CODEBASE.md:197`, `THIRD_PARTY_NOTICES.md:179` (the BSD notice names the spike's `type4_tag.h`), `firmware/README.md:101` and the comment in `firmware/lib/tip_core/src/tip_pn532_frame.h:12` ("so logs compare with the 05c spike's").
- **Removing it also removes:** the `.gitignore` line, the `CODEBASE.md` paragraph, the spike's half sentence in `THIRD_PARTY_NOTICES.md`; the two firmware mentions would then point at history only. No test, env var or Render setting.
- **Why keep:** `CODEBASE.md` records the 05d decision to keep it "as evidence": its `README.md` holds the wiring and the real tap results. If the tap results matter for the write up, moving them into `firmware/README.md` first would let the code go.

### 6. `docs/WORKFLOW.md`

- **What it is:** how the team works: roles (lead, owner, owner's agent), the brief and report loop.
- **Proof nothing references it:** no file outside `briefs/` names `WORKFLOW.md`; `CLAUDE.md` and `README.md` do not link it. Nothing loads it.
- **Removing it also removes:** nothing else.
- **Why keep:** it is the only written account of the process for a human reader; `CLAUDE.md` covers only the agent's side. A link from `README.md` would make it reachable.

### 7. Four test starters in `build.gradle.kts`

`spring-boot-starter-actuator-test`, `spring-boot-starter-data-jpa-test`, `spring-boot-starter-flyway-test`, `spring-boot-starter-validation-test`.

- **What they are for:** Spring Boot 4 pairs each starter with a test starter for its test slices (`@DataJpaTest` and the like).
- **Proof nothing imports them:** no test uses `@DataJpaTest` or any `@AutoConfigure*` but `@AutoConfigureMockMvc` (from `webmvc-test`). The only test imports mentioning Flyway, JPA, actuator or validation are `org.flywaydb.core.Flyway`, `org.springframework.boot.health.registry.HealthContributorRegistry` and `BindValidationException`, all from main dependencies. `spring-boot-starter-security-test` (`csrf()`, `user()`), `webmvc-test`, Testcontainers, WireMock, ArchUnit and AssertJ are all imported.
- **Removing them also removes:** four lines of `build.gradle.kts`. `spring-boot-starter-test` (JUnit, Mockito, Awaitility) still arrives through `webmvc-test`.
- **Why keep, or prove first:** they cost nothing in the app jar, and a test starter can add test auto-configuration to every `@SpringBootTest` without an import, so only a green `./gradlew build` after removing them would prove they are unused. I did not try it, because this brief changes nothing.

## Checked and in use

Not candidates, listed so they are not checked again:

- Every static page, CSS, JS, font, icon and image except item 1; `src/test/resources/art/panda-sprite.svg` (`PandaArtTest`).
- Every key in `application.properties` and every variable in `.env.example`.
- Every main and tools dependency (`paypal-server-sdk`, `anthropic-java`, `bucket4j`, `caffeine`, `qrcodegen`, `flyway-database-postgresql`, `postgresql` at runtime).
- Files read by name by a tool: `.github/pull_request_template.md` (GitHub), `.gitleaksignore` (gitleaks; its one fingerprint is for commit `a0add93` in history, and CI scans the full history), `.editorconfig`, `.gitattributes`, `.dockerignore`, `gradle/wrapper/*`, `firmware/include/secrets.example.h` (CI copies it to `secrets.h`), `firmware/scripts/static_link.py`.
- Docs: `docs/DEVICE_PROTOCOL.md` and `docs/OWNER_CHANNEL.md` are linked from code and other docs.
