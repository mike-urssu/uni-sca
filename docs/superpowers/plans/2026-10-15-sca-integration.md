# 통합 SCA 툴 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** repo URL·이미지 레퍼런스·이미지 tar를 입력받아 cdxgen/syft/trivy/scancode 결과를 병합·보강한 CycloneDX 1.6 + SPDX 2.3 SBOM을 만드는 Spring Boot 백엔드(CLI 겸용)와 React 프론트엔드.

**Architecture:** `backend/`는 파이프라인 Acquire → Scan(docker, 병렬) → Normalize → Merge → Enrich → Emit을 구현하고 REST/SSE와 CLI 모드를 제공한다. `frontend/`는 REST만 사용하는 React 앱이며, 빌드 결과는 backend jar의 static 리소스로 들어간다. 병합 우선순위는 `merge-rules.yml` 설정이라 마지막 작업(도구 분석)에서 실측값으로 조정한다.

**Tech Stack:** Kotlin, Spring Boot 3 (Web, SSE), Gradle Kotlin DSL, Java 21, cyclonedx-core-java, packageurl-java, networknt json-schema-validator, JUnit 5; React + Vite + TypeScript, Vitest.

**Spec:** `docs/superpowers/specs/2026-10-15-sca-integration-design.md`

**스펙과 다른 결정 (이 계획에서 확정):**
- SPDX는 Spdx-Java-Library 대신 Jackson으로 직접 쓰고, 공식 SPDX 2.3 JSON 스키마로 검증한다. 같은 결과를 훨씬 적은 의존성으로 얻는다.
- 버전은 병합 키에 포함된다. 따라서 도구 간 버전 불일치는 `conflicts`가 아니라 "한 도구에서만 발견된 컴포넌트" 두 건으로 드러난다.
- 스펙의 1단계(도구 분석)는 마지막 작업으로 둔다. 분석에 쓸 실측 데이터를 이 파이프라인이 만들어 내기 때문이다.

## Global Constraints

- Java 21, Kotlin, Spring Boot 3.x, Gradle Kotlin DSL. 루트 패키지는 `sca`.
- 라이브러리 버전은 Task 1에서 그 시점의 최신 안정 버전으로 고정한다.
- 출력 포맷: CycloneDX 1.6 JSON(`sbom.cdx.json`), SPDX 2.3 JSON(`sbom.spdx.json`), `report.html`, `result.json`.
- 서버 바인딩은 `127.0.0.1`. Host/Origin 헤더를 검증한다.
- 외부 프로세스는 셸 없이 인자 배열로만 실행한다. 사용자 입력 앞에는 `--`를 두거나, `-`로 시작하는 입력을 거부한다.
- 스캐너 컨테이너: 비root, `--cap-drop ALL`, `--security-opt no-new-privileges`, CPU·메모리 제한, docker 소켓 마운트 금지. cdxgen만 네트워크를 쓰고 나머지는 `--network none`.
- 스캐너 이미지는 태그와 digest로 고정하고 `application.yml`에 둔다. 버전은 호스트에 설치된 syft/trivy/cdxgen과 같은 버전으로 맞춰서 `lab/out` fixture와 일치시킨다.
- 기본값: 도구 타임아웃 15분, 작업 스레드 풀 2, 업로드 상한 10GB, 보강 재시도 최대 3회(지수 백오프), 데이터 디렉터리 `data/`(`sca.data-dir`).
- 상태: `QUEUED`, `RUNNING`, `DONE`, `PARTIAL`, `FAILED`. CLI 종료 코드는 DONE `0`, PARTIAL 또는 검증 경고 `2`, FAILED `1`.
- 출처 문자열: `cdxgen`, `syft`, `trivy`, `scancode`, `deps.dev`, `clearlydefined`, `template`.
- OS 생태계: `deb`, `rpm`, `apk`, `alpm`. 그 외 PURL type은 앱 생태계로 본다.

## Review Focus

1. **지원 manifest가 하나도 없는 repo**: 0개 컴포넌트, 경고 1건, 상태 DONE, 유효한 SBOM이 나와야 한다. 크래시하면 안 된다. → Task 8 테스트.
2. **같은 패키지의 여러 버전이 공존**(npm 중첩, 예: lodash 4.17.15와 4.17.21): 두 컴포넌트로 남고, 둘 다 그래프에 연결되어야 한다. → Task 4 테스트.
3. **PURL이 없거나 잘못된 항목**: 크래시 없이 `name@version` 키와 `needsReview=true`로 남아야 한다. → Task 3 테스트.
4. **의존 그래프의 순환**(npm은 순환을 허용): direct/transitive 계산과 UI의 "포함 경로" 탐색이 종료되어야 한다. → Task 4, Task 12 테스트.
5. **존재하지 않거나 private인 repo/이미지**: git이 자격 증명을 묻느라 멈추지 않고 즉시 FAILED가 되어야 한다(`GIT_TERMINAL_PROMPT=0`). → Task 7 테스트.

---

## File Structure

```
lab/                                   기존 frontend/ 랩 이동 (Task 1)
backend/
  build.gradle.kts, settings.gradle.kts, gradlew
  src/main/kotlin/sca/
    ScaApplication.kt                  main: scan(CLI) / serve(web) 분기
    model/Model.kt                     공유 데이터 타입 전부
    normalize/Readers.kt               도구 출력 → RawResult (CycloneDX 계열, syft, scancode)
    normalize/Normalizer.kt            키 정규화, generic 제거, src/bin 매핑
    merge/MergeRules.kt                merge-rules.yml 로딩
    merge/Merger.kt                    합집합, 필드 우선순위, 그래프, direct/transitive
    enrich/DownloadUrls.kt             PURL → 다운로드 URL / 기본 registry
    enrich/Enricher.kt                 deps.dev, ClearlyDefined, 파일 캐시, 재시도
    emit/CycloneDxWriter.kt
    emit/SpdxWriter.kt
    emit/NtiaValidator.kt
    emit/ReportWriter.kt
    scan/Docker.kt                     ContainerSpec, dockerArgs(), DockerRunner
    scan/Scanners.kt                   도구별 ContainerSpec 생성
    acquire/Acquirer.kt                parseTarget(), clone / pull+save / tar
    job/Pipeline.kt                    단계 실행, 상태 결정
    job/Jobs.kt                        JobStore(파일), JobRunner(스레드 풀, SSE 구독)
    cli/CliRunner.kt
    web/ScanController.kt
    web/HostGuard.kt
  src/main/resources/
    application.yml, merge-rules.yml, spdx-schema-2.3.json
  src/test/kotlin/sca/...              위 구조와 1:1
  src/test/resources/fixtures/         lab/out 일부 복사본 + 녹화한 API 응답
frontend/
  package.json, vite.config.ts, tsconfig.json, index.html
  src/main.tsx, src/api.ts, src/types.ts, src/graph.ts, src/graph.test.ts
  src/pages/ScanForm.tsx, src/pages/JobList.tsx, src/pages/JobView.tsx
  src/components/Summary.tsx, ComponentTable.tsx, DepTree.tsx, ComponentDetail.tsx
build.sh                               frontend 빌드 → backend bootJar
```

---

### Task 1: 저장소 재배치, 백엔드 스캐폴딩, 공유 모델

**Files:**
- Move: `frontend/*` → `lab/` (`demo.sh`, `summary.sh`, `server.js`, `index.html`, `package.json`, `package-lock.json`, `out/`, `node_modules/`가 있으면 그것도)
- Create: `backend/` (Spring Initializr: Gradle Kotlin DSL, Kotlin, Java 21, 의존성 `web`), `backend/src/main/kotlin/sca/model/Model.kt`, `.gitignore`
- Create: `backend/src/test/resources/fixtures/` ← `lab/out/{syft-dir.json, cdxgen.json, trivy-dir.cdx.json, syft-img.json, cdxgen-img.json}` 복사
- Test: `backend/src/test/kotlin/sca/model/ModelTest.kt`

**Interfaces:**
- Produces (`sca.model`, 이후 모든 작업이 사용):

```kotlin
data class Hash(val alg: String, val content: String)          // alg: "SHA-512", "SHA-256", "SHA-1", "MD5"
data class Sourced<T>(val value: T, val source: String, val evidence: String? = null)
data class Conflict(val field: String, val value: String, val source: String)

// 도구 하나의 출력 한 줄. ref는 그 도구 안에서의 ID(bom-ref, syft id)
data class RawComponent(
    val tool: String, val ref: String, val purl: String?, val name: String, val version: String?,
    val licenses: List<String> = emptyList(), val hashes: List<Hash> = emptyList(),
    val downloadUrl: String? = null, val supplier: String? = null, val sourceRepo: String? = null,
    val scope: String? = null, val locations: Set<String> = emptySet(),
    val upstream: String? = null,        // OS 바이너리 패키지의 소스 패키지 이름
)
data class RawResult(val tool: String, val components: List<RawComponent>,
                     val edges: Set<Pair<String, String>>,   // ref → ref (앞이 뒤에 의존)
                     val roots: Set<String>?)                // 도구가 루트를 모르면 null

data class NormalizedResult(val tool: String, val components: Map<String, RawComponent>, // key → 항목
                            val edges: Set<Pair<String, String>>, val roots: Set<String>?)

data class Component(
    val key: String, val purl: String?, val name: String, val version: String?, val ecosystem: String,
    val qualifiers: Map<String, String> = emptyMap(),
    val licenses: Sourced<String>? = null, val hashes: List<Sourced<Hash>> = emptyList(),
    val scope: Sourced<String>? = null, val downloadUrl: Sourced<String>? = null,
    val registry: Sourced<String>? = null, val supplier: Sourced<String>? = null,
    val sourceRepo: Sourced<String>? = null, val locations: Set<String> = emptySet(),
    val foundBy: Set<String>, val direct: Boolean = false,
    val conflicts: List<Conflict> = emptyList(), val unresolved: Set<String> = emptySet(),
    val needsReview: Boolean = false, val licenseTexts: Map<String, String> = emptyMap(), // LicenseRef-* → 원문
)

enum class Status { QUEUED, RUNNING, DONE, PARTIAL, FAILED }
enum class TargetKind { REPO, IMAGE }
data class TargetInfo(val kind: TargetKind, val input: String, val ref: String?, val id: String?) // id: 커밋 SHA 또는 sha256 digest
data class ToolRun(val tool: String, val image: String, val ok: Boolean, val millis: Long, val log: String)
data class Warning(val code: String, val message: String)  // code: TOOL_FAILED, TRANSITIVE_INCOMPLETE, NTIA_MISSING, SCHEMA_INVALID, NO_COMPONENTS
data class ScanResult(val target: TargetInfo, val status: Status, val tools: List<ToolRun>,
                      val components: List<Component>, val edges: Set<Pair<String, String>>,
                      val roots: Set<String>, val warnings: List<Warning>, val createdAt: java.time.Instant)
```

- [ ] **Step 1: `frontend/`의 내용을 `lab/`으로 `git mv`가 아닌 `mv`로 옮긴다** (아직 untracked). `lab/server.js`가 같은 디렉터리의 `index.html`을 읽으므로 상대 경로는 그대로 동작한다. 확인: `ls lab/out/syft-dir.json`.
- [ ] **Step 2: Spring Initializr로 `backend/` 생성.** `curl https://start.spring.io/starter.zip -d type=gradle-project-kotlin -d language=kotlin -d javaVersion=21 -d groupId=sca -d artifactId=sca -d packageName=sca -d dependencies=web -o /tmp/...zip` 후 `backend/`에 풀고, 생성된 Application 클래스를 `ScaApplication.kt`로 둔다. `build.gradle.kts`에 `jackson-module-kotlin`(Initializr가 넣어 줌), `com.github.package-url:packageurl-java`, `org.cyclonedx:cyclonedx-core-java`, `com.networknt:json-schema-validator`를 최신 안정 버전으로 추가한다. bootJar 이름은 `sca.jar`.
- [ ] **Step 3: fixture 복사와 `.gitignore`** (`node_modules/`, `data/`, `backend/build/`, `backend/.gradle/`, `backend/src/main/resources/static/`, `frontend/dist/`, `lab/node_modules/`).
- [ ] **Step 4: 실패하는 테스트 작성**

```kotlin
@Test fun `ScanResult round-trips through jackson`() {
    val c = Component(key = "npm/lodash@4.17.15", purl = "pkg:npm/lodash@4.17.15", name = "lodash",
        version = "4.17.15", ecosystem = "npm", licenses = Sourced("MIT", "cdxgen"), foundBy = setOf("cdxgen", "syft"))
    val r = ScanResult(TargetInfo(TargetKind.REPO, "https://github.com/x/y", null, "abc"), Status.DONE, emptyList(),
        listOf(c), setOf("a" to "b"), setOf("a"), emptyList(), Instant.parse("2026-10-15T00:00:00Z"))
    val json = Json.mapper.writeValueAsString(r)
    assertEquals(r, Json.mapper.readValue<ScanResult>(json))
}
```

- [ ] **Step 5: 실행해서 실패 확인.** `cd backend && ./gradlew test --tests sca.model.ModelTest` → FAIL (`Json` 미정의).
- [ ] **Step 6: `Model.kt`에 위 타입들과 `object Json { val mapper: ObjectMapper }`를 구현한다.** `jacksonObjectMapper()` + `JavaTimeModule`, ISO-8601 날짜. `Pair`는 `{"first":..,"second":..}`로 직렬화되는 기본 동작을 그대로 쓴다.
- [ ] **Step 7: 테스트 통과 확인.** 같은 명령 → PASS.
- [ ] **Step 8: 커밋.** `git add .gitignore backend lab && git commit -m "chore: move lab, scaffold backend with shared model"` (이 작업 이후 모든 커밋에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` 줄을 붙인다).

---

### Task 2: 도구 출력 리더

**Files:**
- Create: `backend/src/main/kotlin/sca/normalize/Readers.kt`
- Test: `backend/src/test/kotlin/sca/normalize/ReadersTest.kt`

**Interfaces:**
- Consumes: Task 1 모델.
- Produces:
  - `object Readers { fun read(tool: String, file: Path): RawResult }`. `cdxgen`과 `trivy`는 CycloneDX 리더, `syft`는 syft-json 리더, `scancode`는 scancode 리더로 보낸다.

**매핑 규칙:**
- **CycloneDX** (`components[]`를 중첩까지 포함해 평탄화)
  - `ref = bom-ref`.
  - 라이선스: `licenses[].license.id` / `.name`, `licenses[].expression`.
  - `hashes[]`, `scope`.
  - 다운로드 URL: `properties[name=ResolvedUrl]`, 없으면 `externalReferences[type=distribution]`.
  - 공급자: `supplier.name` 또는 `author`(publisher).
  - 소스 저장소: `externalReferences[type=vcs]`.
  - 위치: `properties[name=SrcFile]`, `evidence.occurrences[].location`.
  - 그래프: `dependencies[]`의 `ref → dependsOn`. 루트는 `metadata.component.bom-ref`의 `dependsOn`이고, 그 항목이 없으면 `null`.
- **syft** (`artifacts[]`)
  - `ref = id`.
  - 라이선스: `licenses[].value`. **`sha256:`으로 시작하는 값은 버린다.**
  - 다운로드 URL·해시: `metadata.resolved`, `metadata.integrity`. `sha512-<base64>`를 hex로 바꿔 `Hash("SHA-512", hex)`.
  - 공급자: `metadata.maintainer`/`author`.
  - upstream: PURL qualifier `upstream`(값의 공백 앞부분), 없으면 `metadata.source`.
  - 위치: `locations[].path`.
  - 그래프: `artifactRelationships[type=dependency-of]`. `parent`가 `child`의 의존성이므로 엣지는 `child → parent`. 루트는 `null`.
- **scancode** (`packages[]`)
  - `ref = package_uid`, `purl`, 라이선스 `declared_license_expression_spdx`, 위치 `datafile_paths`, 다운로드 URL `download_url`, 소스 저장소 `vcs_url`.
  - 엣지 없음, 루트 `null`.

- [ ] **Step 1: 실패하는 테스트 작성** (fixture: `syft-dir.json`, `cdxgen.json`, `trivy-dir.cdx.json`, `syft-img.json`)

```kotlin
@Test fun `cdxgen gives lodash with resolved url, sha512 and MIT`() {
    val r = Readers.read("cdxgen", fixture("cdxgen.json"))
    val l = r.components.single { it.name == "lodash" }
    assertEquals("pkg:npm/lodash@4.17.15", l.purl)
    assertEquals("https://registry.npmjs.org/lodash/-/lodash-4.17.15.tgz", l.downloadUrl)
    assertEquals(listOf("MIT"), l.licenses)
    assertEquals("SHA-512", l.hashes.single().alg)
    assertTrue("pkg:npm/axios@0.21.0" to "pkg:npm/follow-redirects@1.16.1" in r.edges)
}
@Test fun `syft integrity becomes the same sha512 hex as cdxgen`() {
    val s = Readers.read("syft", fixture("syft-dir.json")).components.single { it.name == "lodash" }
    val c = Readers.read("cdxgen", fixture("cdxgen.json")).components.single { it.name == "lodash" }
    assertEquals(c.hashes.single().content, s.hashes.single().content)
}
@Test fun `syft drops sha256 license values and reads upstream`() {
    val r = Readers.read("syft", fixture("syft-img.json"))
    assertTrue(r.components.flatMap { it.licenses }.none { it.startsWith("sha256:") })
    assertEquals("acl", r.components.single { it.name == "libacl1" }.upstream)
}
@Test fun `syft dependency-of becomes child to parent edge`() {
    val r = Readers.read("syft", fixture("syft-dir.json"))
    val byRef = r.components.associateBy { it.ref }
    assertTrue(r.edges.any { (a, b) -> byRef[a]?.name == "axios" && byRef[b]?.name == "follow-redirects" })
}
@Test fun `trivy cyclonedx output is read`() {
    assertEquals(57, Readers.read("trivy", fixture("trivy-dir.cdx.json")).components.size)
}
```

- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew test --tests sca.normalize.ReadersTest` → FAIL.
- [ ] **Step 3: `Readers.kt` 구현.** Jackson `JsonNode` 트리로 읽는다. 도구 출력 JSON 전체를 모델 클래스로 만들지 않는다.
- [ ] **Step 4: 테스트 통과 확인.** → PASS. 숫자 57이 다르게 나오면 fixture 값(`jq '.components|length' trivy-dir.cdx.json`)을 기준으로 맞춘다.
- [ ] **Step 5: 커밋.** `feat(backend): read cdxgen, trivy, syft, scancode outputs`

---

### Task 3: 정규화

**Files:**
- Create: `backend/src/main/kotlin/sca/normalize/Normalizer.kt`
- Test: `backend/src/test/kotlin/sca/normalize/NormalizerTest.kt`

**Interfaces:**
- Consumes: `RawResult`, `Readers.read`.
- Produces:
  - `object Normalizer { fun key(purl: String?, name: String, version: String?): String; fun normalize(results: List<RawResult>): List<NormalizedResult> }`
  - `val OS_TYPES = setOf("deb", "rpm", "apk", "alpm")` (`Normalizer.kt` 최상위)

**규칙 (스펙 §4 ③):**
- `key`는 `PackageURL`로 파싱해서 `type/namespace/name@version`(namespace가 없으면 `type/name@version`)으로 만든다. qualifier와 subpath는 버린다. 이름·버전은 퍼센트 디코딩한다.
- pypi 이름은 소문자로 바꾸고 `[-_.]+`를 `-`로 바꾼다. npm, golang 등 나머지는 packageurl-java의 정규화를 따른다.
- PURL 파싱에 실패하거나 PURL이 없으면 키는 `name@version`이고, 그 항목을 `needsReview` 대상으로 표시한다. 표시는 키가 `pkg:`에서 오지 않았다는 사실로 판단하며, Merger가 `!key.contains('/')`로 확인한다.
- 버전이 없는 `pkg:generic` 항목은 제거한다. 그 항목을 가리키는 엣지도 제거한다.
- **src/bin 매핑** (도구 간): 어떤 결과에 OS 타입의 바이너리 패키지 B(`upstream = U`, 버전 V)가 있으면, 다른 결과의 같은 타입·같은 namespace의 `U@V` 컴포넌트를 B의 키로 다시 매핑한다. 그 컴포넌트의 엣지 끝점도 B의 키로 바꾼다. 같은 U에 B가 여러 개면 B 키 중 사전순 첫 번째를 쓴다.
- 한 도구 안에서 같은 키가 여러 번 나오면 하나로 합친다(`locations` 합집합, 나머지 필드는 먼저 나온 값).
- 엣지와 루트의 ref는 키로 바꾼다. 자기 자신을 가리키는 엣지는 버린다.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
@Test fun `key strips qualifiers and normalizes pypi`() {
    assertEquals("deb/debian/libacl1@2.2.53-4", Normalizer.key("pkg:deb/debian/libacl1@2.2.53-4?arch=arm64&upstream=acl", "libacl1", "2.2.53-4"))
    assertEquals("pypi/zope-interface@5.0", Normalizer.key("pkg:pypi/Zope_Interface@5.0", "Zope_Interface", "5.0"))
    assertEquals("npm/@types/node@20.1.0", Normalizer.key("pkg:npm/%40types/node@20.1.0", "node", "20.1.0"))
    assertEquals("deb/debian/attr@1:2.4.48-4", Normalizer.key("pkg:deb/debian/attr@1%3A2.4.48-4", "attr", "1:2.4.48-4"))
}
@Test fun `missing or broken purl falls back to name@version`() {
    assertEquals("foo@1.0", Normalizer.key(null, "foo", "1.0"))
    assertEquals("foo@1.0", Normalizer.key("not a purl", "foo", "1.0"))
}
@Test fun `image results drop versionless generic and fold source packages`() {
    val n = Normalizer.normalize(listOf(Readers.read("cdxgen", fixture("cdxgen-img.json")), Readers.read("syft", fixture("syft-img.json"))))
    val cdx = n.first { it.tool == "cdxgen" }
    assertTrue(cdx.components.keys.none { it.startsWith("generic/") && !it.contains('@') })
    assertFalse("deb/debian/acl@2.2.53-4" in cdx.components)
    assertTrue("deb/debian/libacl1@2.2.53-4" in cdx.components)
}
```

- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew test --tests sca.normalize.NormalizerTest` → FAIL.
- [ ] **Step 3: `Normalizer.kt` 구현.**
- [ ] **Step 4: 테스트 통과 확인.** → PASS.
- [ ] **Step 5: 커밋.** `feat(backend): normalize purls, drop generic files, fold source packages`

---

### Task 4: 병합

**Files:**
- Create: `backend/src/main/kotlin/sca/merge/MergeRules.kt`, `backend/src/main/kotlin/sca/merge/Merger.kt`, `backend/src/main/resources/merge-rules.yml`
- Test: `backend/src/test/kotlin/sca/merge/MergerTest.kt`

**Interfaces:**
- Consumes: `NormalizedResult`, `OS_TYPES`.
- Produces:
  - `data class MergeRules(val fields: Map<String, FieldRule>) { companion object { fun load(): MergeRules } }`
  - `data class FieldRule(val app: List<String>, val os: List<String>)`
  - `data class Merged(val components: List<Component>, val edges: Set<Pair<String, String>>, val roots: Set<String>)`
  - `object Merger { fun merge(results: List<NormalizedResult>, rules: MergeRules): Merged }`

**`merge-rules.yml` 초기값** (스펙 §4 ④ 표):

```yaml
fields:
  graph:      { app: [cdxgen, syft, trivy], os: [syft, trivy, cdxgen] }
  licenses:   { app: [cdxgen, syft, trivy, scancode], os: [trivy, syft, cdxgen] }
  scope:      { app: [cdxgen], os: [] }
  hashes:     { app: [cdxgen, syft, trivy], os: [syft, trivy, cdxgen] }
  downloadUrl:{ app: [cdxgen, syft, scancode, trivy], os: [syft, cdxgen, trivy] }
  supplier:   { app: [cdxgen, syft, trivy], os: [syft, trivy, cdxgen] }
  sourceRepo: { app: [cdxgen, scancode, syft, trivy], os: [syft, cdxgen, trivy] }
```

**알고리즘:**
- 컴포넌트는 모든 결과의 키 합집합이다. `foundBy`는 그 키를 가진 도구들이다. `ecosystem`은 키의 type, 키에 `/`가 없으면 `"unknown"`.
- 필드마다 OS 여부에 따라 `app` 또는 `os` 목록을 순서대로 보고, 값이 있는 첫 도구의 값을 `Sourced(value, tool)`로 쓴다. 다른 도구의 값이 다르면 `Conflict`로 기록한다. 비교 전에 라이선스는 SPDX 표현식 문자열(목록이면 `" AND "`로 결합)로, URL은 `git+` 접두사와 `.git` 접미사를 제거해 비교한다.
- 라이선스 값은 SPDX ID 목록(`org.cyclonedx.util.LicenseResolver` 또는 cyclonedx-core-java에 들어 있는 SPDX 라이선스 목록)과 대조한다. 매칭되지 않는 텍스트는 `LicenseRef-` + 원문 sha256 앞 12자리로 바꾸고, 원문은 `licenseTexts`에 둔다.
- 해시는 선택된 도구의 것을 모두 쓴다.
- `qualifiers`와 `locations`는 모든 도구 값의 합집합이다.
- `needsReview = conflicts.isNotEmpty() || foundBy.size == 1 || !key.contains('/')`
- **그래프**: 앱 컴포넌트에 걸린 엣지(양 끝 중 하나라도 앱)는 `graph.app` 목록에서 엣지가 하나라도 있는 첫 도구의 엣지를 쓰고, OS 엣지는 `graph.os` 목록으로 같은 방식으로 고른다. 두 엣지 집합을 합친다. 합친 집합에 없는 키를 가리키는 엣지는 버린다.
- **roots**: 선택된 앱 그래프 도구의 `roots`를 쓰고, 그것이 `null`이면 들어오는 엣지가 없는 노드 전체를 쓴다. OS 컴포넌트는 들어오는 엣지가 없으면 root에 포함한다.
- **direct**: `roots`의 원소이거나 다른 root의 직속 자식이면 `true`. 순환이 있어도 이 정의는 반복 탐색이 필요 없으므로 종료가 보장된다.

- [ ] **Step 1: 실패하는 테스트 작성** (dir fixture 3개를 Readers → Normalizer → Merger로 실행하는 헬퍼 `mergedDir()`)

```kotlin
@Test fun `lodash merges into one component with sourced fields`() {
    val l = mergedDir().components.single { it.name == "lodash" }
    assertEquals(setOf("cdxgen", "syft", "trivy"), l.foundBy)
    assertEquals(Sourced("MIT", "cdxgen"), l.licenses)
    assertEquals("https://registry.npmjs.org/lodash/-/lodash-4.17.15.tgz", l.downloadUrl?.value)
    assertFalse(l.needsReview)
}
@Test fun `axios is direct and follow-redirects is transitive`() {
    val m = mergedDir()
    assertTrue(m.components.single { it.name == "axios" }.direct)
    assertFalse(m.components.single { it.name == "follow-redirects" }.direct)
}
@Test fun `differing license values are recorded as conflict`() {
    val a = raw("cdxgen", "pkg:npm/x@1", licenses = listOf("MIT")); val b = raw("syft", "pkg:npm/x@1", licenses = listOf("Apache-2.0"))
    val c = Merger.merge(Normalizer.normalize(listOf(a, b)), MergeRules.load()).components.single()
    assertEquals("MIT", c.licenses?.value)
    assertEquals(listOf(Conflict("licenses", "Apache-2.0", "syft")), c.conflicts)
    assertTrue(c.needsReview)
}
@Test fun `two versions of the same package stay separate and both are linked`() {   // Review Focus 2
    val r = rawResult("cdxgen", purls = listOf("pkg:npm/app@1", "pkg:npm/lodash@4.17.15", "pkg:npm/lodash@4.17.21", "pkg:npm/b@1"),
        edges = setOf("pkg:npm/app@1" to "pkg:npm/lodash@4.17.21", "pkg:npm/app@1" to "pkg:npm/b@1", "pkg:npm/b@1" to "pkg:npm/lodash@4.17.15"),
        roots = setOf("pkg:npm/app@1"))
    val m = Merger.merge(Normalizer.normalize(listOf(r)), MergeRules.load())
    assertEquals(2, m.components.count { it.name == "lodash" })
    assertTrue("npm/b@1" to "npm/lodash@4.17.15" in m.edges)
}
@Test fun `cyclic graph terminates`() {   // Review Focus 4
    val r = rawResult("cdxgen", purls = listOf("pkg:npm/a@1", "pkg:npm/b@1"),
        edges = setOf("pkg:npm/a@1" to "pkg:npm/b@1", "pkg:npm/b@1" to "pkg:npm/a@1"), roots = null)
    assertEquals(2, Merger.merge(Normalizer.normalize(listOf(r)), MergeRules.load()).components.size)
}
```

`raw(...)`, `rawResult(...)`는 테스트 파일 안의 작은 빌더다. `rawResult`의 `ref`는 PURL을 그대로 쓴다.

- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew test --tests sca.merge.MergerTest` → FAIL.
- [ ] **Step 3: `MergeRules.kt`(Jackson YAML, `jackson-dataformat-yaml` 추가)와 `Merger.kt` 구현.**
- [ ] **Step 4: 테스트 통과 확인.** → PASS.
- [ ] **Step 5: 커밋.** `feat(backend): merge components by key with configurable field priority`

---

### Task 5: 다운로드 URL 템플릿

**Files:**
- Create: `backend/src/main/kotlin/sca/enrich/DownloadUrls.kt`
- Test: `backend/src/test/kotlin/sca/enrich/DownloadUrlsTest.kt`

**Interfaces:**
- Produces: `object DownloadUrls { fun forPurl(purl: String): String?; fun defaultRegistry(type: String): String? }`

**템플릿** (`{v}`는 버전, 이름과 버전은 퍼센트 디코딩한 값):

| type | URL |
|---|---|
| npm | `https://registry.npmjs.org/{ns/}{name}/-/{name}-{v}.tgz` (ns는 `@scope`) |
| maven | `https://repo.maven.apache.org/maven2/{ns의 .을 /로}/{name}/{v}/{name}-{v}[-{classifier}].{type qualifier, 기본 jar}` |
| pypi | `https://pypi.org/packages/source/{name[0]}/{name}/{name}-{v}.tar.gz` |
| golang | `https://proxy.golang.org/{ns/name 대문자는 !소문자로 이스케이프}/@v/{v}.zip` |
| cargo | `https://crates.io/api/v1/crates/{name}/{v}/download` |
| nuget | `https://api.nuget.org/v3-flatcontainer/{name 소문자}/{v 소문자}/{name 소문자}.{v 소문자}.nupkg` |
| gem | `https://rubygems.org/downloads/{name}-{v}.gem` |
| deb (namespace debian) | `http://deb.debian.org/debian/pool/main/{p}/{src}/{name}_{epoch를 뺀 v}_{arch}.deb`. `src`는 `upstream` qualifier의 공백 앞부분이고 없으면 name. `p`는 src가 `lib`로 시작하면 앞 4글자, 아니면 1글자. `arch` qualifier가 없으면 `null` |
| 그 외 (rpm, apk, composer, generic 등) | `null` |

PURL에 `download_url` qualifier가 있으면 그것을 먼저 반환한다. deb 행에는 `// ponytail: main 컴포넌트만 가정, contrib/non-free·보안 업데이트 미러는 snapshot.debian.org 조회가 필요해지면 추가` 주석을 단다.

`defaultRegistry`: npm `https://registry.npmjs.org`, maven `https://repo.maven.apache.org/maven2`, pypi `https://pypi.org`, golang `https://proxy.golang.org`, cargo `https://crates.io`, nuget `https://api.nuget.org/v3/index.json`, gem `https://rubygems.org`, deb `http://deb.debian.org/debian`, 그 외 `null`.

- [ ] **Step 1: 실패하는 테스트 작성**: 위 표의 행마다 하나씩 `assertEquals`를 둔다. 꼭 들어가야 할 케이스는 아래와 같다.

```kotlin
assertEquals("https://registry.npmjs.org/@types/node/-/node-20.1.0.tgz", DownloadUrls.forPurl("pkg:npm/%40types/node@20.1.0"))
assertEquals("https://repo.maven.apache.org/maven2/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar", DownloadUrls.forPurl("pkg:maven/org.apache.commons/commons-lang3@3.12.0"))
assertEquals("https://proxy.golang.org/github.com/!burnt!sushi/toml/@v/v1.3.2.zip", DownloadUrls.forPurl("pkg:golang/github.com/BurntSushi/toml@v1.3.2"))
assertEquals("http://deb.debian.org/debian/pool/main/liba/acl/libacl1_2.2.53-4_arm64.deb", DownloadUrls.forPurl("pkg:deb/debian/libacl1@2.2.53-4?arch=arm64&upstream=acl"))
assertEquals("http://deb.debian.org/debian/pool/main/a/attr/attr_2.4.48-4_arm64.deb", DownloadUrls.forPurl("pkg:deb/debian/attr@1%3A2.4.48-4?arch=arm64"))
assertNull(DownloadUrls.forPurl("pkg:rpm/redhat/bash@5.1"))
```

- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew test --tests sca.enrich.DownloadUrlsTest` → FAIL.
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 테스트 통과 확인.** → PASS.
- [ ] **Step 5: 커밋.** `feat(backend): compute download urls from purl`

---

### Task 6: 보강 (deps.dev, ClearlyDefined, 캐시, 재시도)

**Files:**
- Create: `backend/src/main/kotlin/sca/enrich/Enricher.kt`
- Test: `backend/src/test/kotlin/sca/enrich/EnricherTest.kt`, `backend/src/test/resources/fixtures/depsdev-lodash.json`, `clearlydefined-x.json`

**Interfaces:**
- Consumes: `Component`, `DownloadUrls`, `OS_TYPES`.
- Produces:
  - `class Enricher(val depsDevBase: String = "https://api.deps.dev", val clearlyDefinedBase: String = "https://api.clearlydefined.io", val cacheDir: Path, val backoff: Duration = Duration.ofSeconds(1))`
  - `fun enrich(c: Component): Component`
  - `fun enrichAll(cs: List<Component>): List<Component>` (가상 스레드 동시성 8)

**규칙:**
- **비어 있는 필드만 채운다.** 도구가 준 값은 절대 바꾸지 않는다.
- `downloadUrl`: `DownloadUrls.forPurl` 값을 `source="template"`로 쓴다.
- `registry`: PURL `repository_url`이 있으면 그 값을 `source`=원래 도구로, 없으면 `defaultRegistry`를 `"template"`으로 쓴다.
- **deps.dev**: `GET {base}/v3/systems/{SYSTEM}/packages/{urlenc name}/versions/{urlenc v}`
  - SYSTEM: npm→NPM, maven→MAVEN(name은 `group:artifact`), pypi→PYPI, golang→GO, cargo→CARGO, nuget→NUGET. OS 타입은 호출하지 않는다.
  - 응답의 `licenses`(`" AND "`로 결합)로 `licenses`를, `links[label=SOURCE_REPO]`로 `sourceRepo`를 채운다. `source="deps.dev"`.
- **ClearlyDefined**: deps.dev 뒤에도 `licenses`가 비어 있을 때만 `GET {base}/definitions/{type}/{provider}/{ns 또는 -}/{name}/{v}`를 호출해 `licensed.declared`를 쓴다.
  - type/provider: npm/npmjs, maven/mavencentral, pypi/pypi, gem/rubygems, nuget/nuget, crate/cratesio, go/golang, deb/debian.
  - `NOASSERTION`은 값이 없는 것으로 본다.
- **HTTP**: `java.net.http.HttpClient`, 타임아웃 10초.
  - 200은 성공. 404는 "없음"으로 캐시한다.
  - 429와 5xx, IO 예외는 `backoff * 2^n` 간격으로 최대 3회 재시도한다. 그래도 실패하면 캐시하지 않는다.
- **캐시**: `cacheDir/<sha256(url)>.json`에 응답 본문(404면 빈 파일)을 저장한다.
- **unresolved**: 끝까지 비어 있는 필드 이름을 `unresolved`에 넣는다. 대상 필드는 `licenses`, `downloadUrl`, `supplier`, `sourceRepo`, `hashes`.

- [ ] **Step 1: 실패하는 테스트 작성.** 테스트 서버는 JDK `com.sun.net.httpserver.HttpServer`를 쓴다. 요청 수를 세고, 응답 상태를 경로별로 지정할 수 있게 한다. 백오프는 `Duration.ZERO`로 둔다.

```kotlin
@Test fun `fills only empty fields from deps dev and template`() {
    val c = comp("pkg:npm/lodash@4.17.15")                       // licenses 없음
    val e = server.respond("/v3/systems/NPM/packages/lodash/versions/4.17.15", 200, fixture("depsdev-lodash.json")).enricher()
    val r = e.enrich(c)
    assertEquals(Sourced("MIT", "deps.dev"), r.licenses)
    assertEquals("template", r.downloadUrl?.source)
    assertEquals("git+https://github.com/lodash/lodash.git", r.sourceRepo?.value)
}
@Test fun `never overwrites tool values`() {
    val c = comp("pkg:npm/lodash@4.17.15").copy(licenses = Sourced("Apache-2.0", "cdxgen"))
    assertEquals(Sourced("Apache-2.0", "cdxgen"), enricherWithDepsDev().enrich(c).licenses)
}
@Test fun `falls back to clearlydefined when deps dev has no license`() { /* deps.dev 404 → CD 200 licensed.declared "BSD-3-Clause" */ }
@Test fun `429 retried 3 times then unresolved and not cached`() {
    val e = server.respondAlways(429).enricher()
    val r = e.enrich(comp("pkg:npm/x@1"))
    assertTrue("licenses" in r.unresolved)
    assertEquals(1 + 3, server.count("/v3/systems/NPM/packages/x/versions/1"))
    assertEquals(0, Files.list(cacheDir).count())
}
@Test fun `second call is served from cache`() { /* 200 응답 후 같은 컴포넌트 두 번 enrich → 서버 요청 1회 */ }
@Test fun `os packages skip deps dev`() { /* pkg:deb/... → deps.dev 요청 0회 */ }
```

- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew test --tests sca.enrich.EnricherTest` → FAIL.
- [ ] **Step 3: `Enricher.kt` 구현.** `depsdev-lodash.json` fixture는 실제 응답을 한 번 받아서 녹화한다: `curl -s https://api.deps.dev/v3/systems/npm/packages/lodash/versions/4.17.15`.
- [ ] **Step 4: 테스트 통과 확인.** → PASS.
- [ ] **Step 5: 커밋.** `feat(backend): enrich empty fields from deps.dev and clearlydefined with cache`

---

### Task 7: 출력 (CycloneDX, SPDX, NTIA, HTML 리포트)

**Files:**
- Create: `backend/src/main/kotlin/sca/emit/{CycloneDxWriter,SpdxWriter,NtiaValidator,ReportWriter}.kt`, `backend/src/main/resources/spdx-schema-2.3.json` (출처: `https://raw.githubusercontent.com/spdx/spdx-spec/v2.3/schemas/spdx-schema.json`)
- Test: `backend/src/test/kotlin/sca/emit/EmitTest.kt`

**Interfaces:**
- Consumes: `ScanResult`.
- Produces:
  - `object CycloneDxWriter { fun write(r: ScanResult, out: Path): List<Warning> }` → 스키마 오류는 `SCHEMA_INVALID` 경고로 반환.
  - `object SpdxWriter { fun write(r: ScanResult, out: Path): List<Warning> }` → 같은 방식.
  - `object NtiaValidator { fun check(r: ScanResult): List<Warning> }` → `NTIA_MISSING`. 필드별로 묶어 경고 1건씩 낸다. 예: `"supplier missing for 12 components"`.
  - `object ReportWriter { fun write(r: ScanResult, out: Path) }`
  - `const val TOOL_NAME = "sca-integration"` (CycloneDxWriter.kt)

**매핑 (스펙 §4 ⑥):**
- **CycloneDX** (cyclonedx-core-java 모델 → `BomGeneratorFactory.createJson(Version.VERSION_16, bom)`, 검증은 `JsonParser().validate(file, Version.VERSION_16)`)
  - `metadata.timestamp=createdAt`, `metadata.tools`에 `TOOL_NAME`과 `tools[]`(image 문자열)을 넣는다.
  - `metadata.component`는 대상(repo URL 또는 이미지 입력), version=`id`.
  - `metadata.properties`: `sca:status`, `sca:warning`(경고마다 하나).
  - 컴포넌트: `bom-ref=key`, `purl`, `licenses`(expression), `hashes`, `scope`, `supplier.name`, `externalReferences`(distribution=downloadUrl, vcs=sourceRepo).
  - 컴포넌트 `properties`: `sca:foundBy`, `sca:source:<field>`, `sca:needsReview`, `sca:unresolved`, `sca:conflict`.
  - 의존 관계: 루트 컴포넌트 → `roots`, 그리고 `edges`.
- **SPDX 2.3** (Jackson `Map` 트리 → networknt로 `spdx-schema-2.3.json` 검증)
  - `spdxVersion="SPDX-2.3"`, `dataLicense="CC0-1.0"`, `SPDXID="SPDXRef-DOCUMENT"`.
  - `documentNamespace="https://sca-integration.local/spdx/{target.id ?: 입력 sha256}/{createdAt epoch}"`.
  - `creationInfo.creators=["Tool: sca-integration"]`, `created=createdAt`.
  - 패키지
    - `SPDXID`: `"SPDXRef-" + key의 [^A-Za-z0-9.-]를 -로 바꾼 값`. 충돌하면 뒤에 `-n`을 붙인다.
    - `downloadLocation`: downloadUrl, 없으면 `"NOASSERTION"`.
    - `licenseDeclared`: SPDX 표현식, 없으면 `"NOASSERTION"`.
    - `licenseConcluded`: `"NOASSERTION"`.
    - `supplier`: `"Organization: …"` 또는 `"NOASSERTION"`.
    - `checksums`: SPDX 알고리즘명.
    - `externalRefs`: `PACKAGE-MANAGER`/`purl`.
    - `filesAnalyzed=false`.
  - `hasExtractedLicensingInfos`: `licenseTexts`.
  - `relationships`: 문서 DESCRIBES 루트 패키지(대상), 루트 패키지 DEPENDS_ON `roots`, 그리고 각 엣지 DEPENDS_ON.
- **NTIA 검사**: 각 컴포넌트에 `name`, `version`, `purl`(고유 ID), `supplier`가 있어야 한다. 컴포넌트가 2개 이상인데 `edges`가 비어 있으면 경고 1건. 작성자와 타임스탬프는 writer가 항상 채우므로 검사하지 않는다.
- **report.html**: 정적 HTML 1개다. 요약(상태, 경고, 도구 실행 결과, 컴포넌트 수 direct/transitive, 생태계별 수, 라이선스별 수, 검토 필요 수)과 컴포넌트 테이블(이름, 버전, 라이선스, direct, 다운로드 URL, foundBy, 검토 필요)을 담는다. 모든 값은 HTML 이스케이프한다. 테이블 위에 `<input>`과 인라인 스크립트 10줄 정도로 필터를 넣는다.

- [ ] **Step 1: 실패하는 테스트 작성.** 입력은 Task 4의 `mergedDir()` 결과로 만든 `ScanResult`다.

```kotlin
@Test fun `cyclonedx output is schema valid and keeps provenance`() {
    assertEquals(emptyList<Warning>(), CycloneDxWriter.write(result, tmp.resolve("sbom.cdx.json")))
    val bom = Json.mapper.readTree(tmp.resolve("sbom.cdx.json").toFile())
    assertEquals("1.6", bom["specVersion"].asText())
    val l = bom["components"].single { it["name"].asText() == "lodash" }
    assertEquals("https://registry.npmjs.org/lodash/-/lodash-4.17.15.tgz", l["externalReferences"].single { it["type"].asText() == "distribution" }["url"].asText())
    assertTrue(l["properties"].any { it["name"].asText() == "sca:source:licenses" && it["value"].asText() == "cdxgen" })
}
@Test fun `spdx output is schema valid with depends_on relationships`() {
    assertEquals(emptyList<Warning>(), SpdxWriter.write(result, tmp.resolve("sbom.spdx.json")))
    val doc = Json.mapper.readTree(tmp.resolve("sbom.spdx.json").toFile())
    assertTrue(doc["relationships"].any { it["relationshipType"].asText() == "DEPENDS_ON" })
}
@Test fun `ntia reports missing supplier once`() {
    val w = NtiaValidator.check(result.copy(components = result.components.map { it.copy(supplier = null) }))
    assertEquals(1, w.count { it.code == "NTIA_MISSING" && it.message.startsWith("supplier") })
}
@Test fun `report escapes html`() {
    ReportWriter.write(result.copy(components = listOf(result.components[0].copy(name = "<script>x"))), tmp.resolve("r.html"))
    assertFalse(Files.readString(tmp.resolve("r.html")).contains("<script>x"))
}
@Test fun `empty result still yields valid sboms`() {   // Review Focus 1 (출력 쪽)
    val empty = result.copy(components = emptyList(), edges = emptySet(), roots = emptySet())
    assertEquals(emptyList<Warning>(), CycloneDxWriter.write(empty, tmp.resolve("e.cdx.json")) + SpdxWriter.write(empty, tmp.resolve("e.spdx.json")))
}
```

- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew test --tests sca.emit.EmitTest` → FAIL.
- [ ] **Step 3: 네 파일 구현.**
- [ ] **Step 4: 테스트 통과 확인.** → PASS.
- [ ] **Step 5: 커밋.** `feat(backend): emit cyclonedx 1.6, spdx 2.3, ntia check and html report`

---

### Task 8: 대상 획득과 샌드박스 스캐너 실행

**Files:**
- Create: `backend/src/main/kotlin/sca/acquire/Acquirer.kt`, `backend/src/main/kotlin/sca/scan/Docker.kt`, `backend/src/main/kotlin/sca/scan/Scanners.kt`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/kotlin/sca/acquire/AcquirerTest.kt`, `backend/src/test/kotlin/sca/scan/ScannersTest.kt`

**Interfaces:**
- Produces:

```kotlin
sealed interface Target { data class Repo(val url: String, val ref: String?) : Target
                          data class ImageRef(val ref: String) : Target
                          data class ImageTar(val path: Path) : Target }
class InvalidInput(msg: String) : RuntimeException(msg)
class AcquireFailed(msg: String) : RuntimeException(msg)
fun parseTarget(input: String, ref: String?): Target    // https:// → Repo, 존재하는 .tar 파일 → ImageTar, 그 외 레퍼런스 문법 → ImageRef, 아니면 InvalidInput
data class Acquired(val kind: TargetKind, val path: Path, val id: String)   // REPO: 체크아웃 디렉터리 + 커밋 SHA, IMAGE: tar + sha256 digest
class Acquirer(val gitTimeout: Duration = Duration.ofMinutes(10)) { fun acquire(t: Target, work: Path): Acquired }

data class Mount(val host: Path, val container: String, val readOnly: Boolean)
data class ContainerSpec(val tool: String, val image: String, val args: List<String>, val mounts: List<Mount>,
                         val network: Boolean, val output: String /* 컨테이너 안 결과 파일 경로, /out/<tool>.json */)
fun dockerArgs(s: ContainerSpec, limits: Limits): List<String>
data class Limits(val cpus: String = "2", val memory: String = "4g")
class DockerRunner { fun run(s: ContainerSpec, limits: Limits, timeout: Duration, log: Path): Boolean } // 타임아웃이면 컨테이너 kill 후 false

@ConfigurationProperties("sca") data class ScaProperties(val dataDir: Path = Path.of("data"), val toolTimeout: Duration = Duration.ofMinutes(15),
    val workers: Int = 2, val images: Map<String, String>, val limits: Limits = Limits())
object Scanners { fun specs(a: Acquired, deep: Boolean, outDir: Path, trivyDb: Path, images: Map<String, String>): List<ContainerSpec> }
```

**규칙:**
- **image ref 검증 정규식**: `^[a-zA-Z0-9][a-zA-Z0-9._/:@-]*$`, 최대 255자. `-`로 시작하거나 공백, `$`, `` ` ``이 들어간 입력은 거부한다.
- **repo URL**: `^https://[^\s]+$`만 받는다.
- **`dockerArgs`**에는 항상 `run --rm --user 1000:1000 --cap-drop ALL --security-opt no-new-privileges --cpus <c> --memory <m> --name sca-<uuid>`가 들어간다. `network=false`면 `--network none`이 붙는다. 마운트는 `-v host:container[:ro]`이고, 그 뒤에 image와 args가 온다. **`/var/run/docker.sock`을 마운트하는 spec이 오면 `IllegalArgumentException`을 던진다.**
- **`Acquirer`**
  - **Repo**: `git -c core.hooksPath=/dev/null clone --depth 1 --recurse-submodules --shallow-submodules [--branch ref] -- <url> <work/src>`를 환경변수 `GIT_TERMINAL_PROMPT=0`, `GIT_ASKPASS=/bin/false`로 실행한다. 이어서 `git rev-parse HEAD`로 커밋 SHA를 얻는다.
  - **ImageRef**: `docker pull -- <ref>`, `docker save -o <work/image.tar> -- <ref>`, `docker inspect --format {{index .RepoDigests 0}}`로 digest를 얻는다. RepoDigests가 없으면 tar의 sha256을 쓴다.
  - **ImageTar**: 경로를 그대로 쓰고 sha256을 계산한다.
  - 실패하면 stderr 마지막 20줄을 담아 `AcquireFailed(msg)`를 던진다.
- **`Scanners.specs`** (컨테이너 안 대상 경로는 `/src`(repo) 또는 `/in/image.tar`)
  - **cdxgen**: repo는 `work/src`를 `work/cdxgen-src`로 복사해 쓰기 가능 마운트로 넘기고, `-r /src -o /out/cdxgen.json --spec-version 1.6`, 네트워크 허용. 이미지는 `-t docker /in/image.tar -o /out/cdxgen.json --spec-version 1.6`(정확한 플래그는 `docker run <cdxgen> --help`로 확인한다).
  - **syft**: `scan dir:/src` 또는 `docker-archive:/in/image.tar`, `-o syft-json=/out/syft.json`.
  - **trivy**: `fs /src` 또는 `image --input /in/image.tar`, `--format cyclonedx --output /out/trivy.json --offline-scan --skip-db-update --cache-dir /trivy`. `trivyDb`를 `/trivy`에 read-only로 마운트한다.
  - **scancode**: `deep && REPO`일 때만. `-clpi --json-pp /out/scancode.json /src`.
  - `/out`은 `outDir`(= job `raw/`)을 쓰기 가능으로 마운트한다.
- **`application.yml`**
  - `sca.images.{cdxgen,syft,trivy,scancode}`에 `이름:버전@sha256:…` 형식을 넣는다. 각 이미지를 `docker pull`한 뒤 `docker inspect --format '{{index .RepoDigests 0}}'`로 digest를 얻는다. 버전은 호스트 CLI(`syft version`, `trivy -v`, `cdxgen --version`)와 맞춘다. scancode는 `ghcr.io/aboutcode-org/scancode-toolkit` 최신 릴리스다.
  - `server.address: 127.0.0.1`, `spring.servlet.multipart.max-file-size: 10GB`, `max-request-size: 10GB`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
@Test fun `parseTarget classifies and rejects injection`() {
    assertEquals(Target.Repo("https://github.com/a/b", "v1"), parseTarget("https://github.com/a/b", "v1"))
    assertEquals(Target.ImageRef("ghcr.io/a/b@sha256:" + "0".repeat(64)), parseTarget("ghcr.io/a/b@sha256:" + "0".repeat(64), null))
    assertEquals(Target.ImageRef("node:16-slim"), parseTarget("node:16-slim", null))
    listOf("--privileged", "a b", "\$(id)", "http://x/y.git", "file:///etc").forEach { assertThrows<InvalidInput> { parseTarget(it, null) } }
}
@Test fun `docker args are locked down`() {
    val a = dockerArgs(ContainerSpec("syft", "img", listOf("x"), listOf(Mount(Path.of("/h"), "/src", true)), false, "/out/syft.json"), Limits())
    assertTrue(a.containsAll(listOf("--cap-drop", "ALL", "--network", "none", "--security-opt", "no-new-privileges", "-v", "/h:/src:ro")))
    assertThrows<IllegalArgumentException> { dockerArgs(ContainerSpec("x", "i", emptyList(), listOf(Mount(Path.of("/var/run/docker.sock"), "/var/run/docker.sock", false)), false, ""), Limits()) }
}
@Test fun `only cdxgen gets network, scancode only when deep repo`() {
    val s = Scanners.specs(Acquired(TargetKind.REPO, tmp, "sha"), deep = false, tmp, tmp, images)
    assertEquals(setOf("cdxgen"), s.filter { it.network }.map { it.tool }.toSet())
    assertTrue(s.none { it.tool == "scancode" })
    assertTrue(Scanners.specs(Acquired(TargetKind.REPO, tmp, "sha"), true, tmp, tmp, images).any { it.tool == "scancode" })
}
@Test fun `missing repo fails fast without prompting`() {   // Review Focus 5. 네트워크 필요: @Tag("integration")
    val t0 = System.nanoTime()
    assertThrows<AcquireFailed> { Acquirer().acquire(Target.Repo("https://github.com/this-org-does-not-exist-xyz/nope", null), tmp) }
    assertTrue(Duration.ofNanos(System.nanoTime() - t0) < Duration.ofSeconds(30))
}
```

- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew test --tests 'sca.acquire.*' --tests 'sca.scan.*'` → FAIL.
- [ ] **Step 3: 구현과 `application.yml` 이미지 고정.**
- [ ] **Step 4: 테스트 통과 확인.** → PASS. 통합 태그 테스트는 `./gradlew integrationTest`로 돌린다(이 작업에서 `build.gradle.kts`에 `integrationTest` 태스크를 만들고, 기본 `test`는 `integration` 태그를 제외한다).
- [ ] **Step 5: 커밋.** `feat(backend): acquire targets and run scanners in locked-down containers`

---

### Task 9: 파이프라인과 작업 관리

**Files:**
- Create: `backend/src/main/kotlin/sca/job/Pipeline.kt`, `backend/src/main/kotlin/sca/job/Jobs.kt`
- Test: `backend/src/test/kotlin/sca/job/PipelineTest.kt`, `backend/src/test/kotlin/sca/job/JobsTest.kt`

**Interfaces:**
- Consumes: Task 2~8 전부.
- Produces:

```kotlin
data class ScanInput(val target: String, val ref: String? = null, val deep: Boolean = false, val uploadedTar: Path? = null)
fun interface ScanStep { fun run(spec: ContainerSpec, log: Path): Boolean }   // DockerRunner를 감싼 것. 테스트에서 가짜로 대체
class Pipeline(val props: ScaProperties, val acquirer: Acquirer, val step: ScanStep, val enricher: Enricher, val rules: MergeRules) {
    fun run(jobDir: Path, input: ScanInput, progress: (String) -> Unit): ScanResult   // 결과 파일 전부 jobDir에 기록
}
data class JobStatus(val id: String, val status: Status, val stage: String?, val input: ScanInput, val createdAt: Instant,
                     val tools: List<ToolRun> = emptyList(), val error: String? = null)
class JobStore(val dataDir: Path) { fun create(input: ScanInput): JobStatus; fun save(s: JobStatus); fun get(id: String): JobStatus?
                                    fun list(): List<JobStatus>; fun dir(id: String): Path; fun failInterrupted() }
@Service class JobRunner(store: JobStore, pipeline: Pipeline, props: ScaProperties) {
    fun submit(input: ScanInput): String; fun subscribe(id: String): SseEmitter }   // SSE 이벤트: stage, tool, done (data = JobStatus JSON)
```

**`Pipeline.run` 순서 (진행 이벤트 이름은 괄호 안 그대로):**
1. `acquire`: `uploadedTar`가 있으면 `ImageTar`로 처리한다.
2. `scan`: specs를 병렬로 실행한다(가상 스레드). 결과 파일이 없거나 실행이 실패한 도구는 `ToolRun(ok=false)`.
3. `normalize`
4. `merge`
5. `enrich`
6. `emit`

상태 결정:
- 모든 도구가 실패하면 FAILED(결과 파일 없이 `JobStatus.error`). 하나라도 실패하면 PARTIAL에 `TOOL_FAILED` 경고. 아니면 DONE.
- 컴포넌트가 0개면 `NO_COMPONENTS` 경고를 붙인다(상태는 바꾸지 않는다).
- cdxgen 로그에 빌드 해석 실패 표지(Task 10 분석 전 초기값: 로그에 `Unable to` 또는 `exited with error`가 있는 줄)가 있으면, 그 줄마다 `TRANSITIVE_INCOMPLETE` 경고를 낸다.
- NTIA 경고와 스키마 경고는 결과에 합친다.
- 마지막에 `work/` 아래 clone 디렉터리와 `image.tar`(ImageRef일 때만)를 삭제한다. `raw/`는 남긴다.

**JobStore**: `dataDir/jobs/<id>/status.json`, `input.json`. id는 `yyyyMMdd-HHmmss-<6자 랜덤>`. `failInterrupted()`는 RUNNING/QUEUED 상태를 `FAILED("server restarted")`로 바꾸며, `@PostConstruct`에서 호출한다.

**trivy DB**: `JobRunner` 시작 시와 24시간마다 `docker run <trivy> image --download-db-only --cache-dir /trivy`를 `dataDir/trivy-db`에 대해 실행한다. 이 컨테이너만 네트워크를 쓰고 쓰기 가능으로 마운트한다.

- [ ] **Step 1: 실패하는 테스트 작성.** 가짜 `ScanStep`은 fixture를 `raw/<tool>.json`으로 복사한다. 가짜 Acquirer는 `Acquired(REPO, tmp, "sha")`를 반환하는 서브클래스다. Enricher는 Task 6의 테스트 서버(모두 404)를 쓴다.

```kotlin
@Test fun `all tools succeed gives DONE and all files`() {
    val r = pipeline(fakeStep(ok = setOf("cdxgen", "syft", "trivy"))).run(jobDir, ScanInput("https://github.com/a/b"), {})
    assertEquals(Status.DONE, r.status)
    listOf("result.json", "sbom.cdx.json", "sbom.spdx.json", "report.html").forEach { assertTrue(Files.exists(jobDir.resolve(it))) }
}
@Test fun `one tool failing gives PARTIAL with warning`() {
    val r = pipeline(fakeStep(ok = setOf("cdxgen", "syft"))).run(jobDir, ScanInput("https://github.com/a/b"), {})
    assertEquals(Status.PARTIAL, r.status); assertTrue(r.warnings.any { it.code == "TOOL_FAILED" && "trivy" in it.message })
}
@Test fun `all tools failing gives FAILED`() { /* ok = emptySet() → r.status == FAILED */ }
@Test fun `repo with nothing to scan gives DONE with NO_COMPONENTS`() {   // Review Focus 1
    val r = pipeline(fakeStepWritingEmptyBoms()).run(jobDir, ScanInput("https://github.com/a/b"), {})
    assertEquals(Status.DONE, r.status); assertTrue(r.warnings.any { it.code == "NO_COMPONENTS" })
}
@Test fun `progress reports stages in order`() { /* 수집한 progress == listOf("acquire","scan","normalize","merge","enrich","emit") */ }
@Test fun `interrupted jobs become FAILED on startup`() { /* JobsTest: RUNNING 저장 → failInterrupted() → FAILED, error == "server restarted" */ }
```

- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew test --tests 'sca.job.*'` → FAIL.
- [ ] **Step 3: `Pipeline.kt`, `Jobs.kt` 구현.**
- [ ] **Step 4: 테스트 통과 확인.** → PASS.
- [ ] **Step 5: 커밋.** `feat(backend): pipeline with partial/failed status and file-backed jobs`

---

### Task 10: CLI 모드와 REST API

**Files:**
- Create: `backend/src/main/kotlin/sca/cli/CliRunner.kt`, `backend/src/main/kotlin/sca/web/ScanController.kt`, `backend/src/main/kotlin/sca/web/HostGuard.kt`
- Modify: `backend/src/main/kotlin/sca/ScaApplication.kt`
- Test: `backend/src/test/kotlin/sca/cli/CliRunnerTest.kt`, `backend/src/test/kotlin/sca/web/ScanControllerTest.kt`

**Interfaces:**
- Consumes: `Pipeline`, `JobRunner`, `JobStore`, `parseTarget`.
- Produces:
  - `fun parseCli(args: Array<String>): CliCommand`. `sealed CliCommand { Scan(input: ScanInput, out: Path, strict: Boolean); Serve(port: Int); Usage(msg: String) }`
  - `fun exitCode(r: ScanResult?, strict: Boolean): Int`
  - REST 경로는 스펙 §8 표 그대로다.

**규칙:**
- **`main`**: `Scan`이면 `web-application-type=none`으로 컨텍스트를 띄워 `CliRunner`가 `Pipeline.run`을 동기 실행한다. 출력 디렉터리는 `-o`(기본 `./sca-out`)이고, 진행 단계를 stderr에 한 줄씩 찍고, `exitProcess(exitCode)`로 끝낸다. `Serve`면 웹 모드, `Usage`면 stderr에 사용법을 찍고 종료 코드 1.
- **`exitCode`**:
  - `null`(예외)이거나 FAILED면 1.
  - PARTIAL이면 2.
  - DONE이면서 `NTIA_MISSING`/`SCHEMA_INVALID` 경고가 있으면, `strict`일 때 1, 아닐 때 2.
  - 그 외 0.
- **HostGuard** (`OncePerRequestFilter`)
  - Host가 `localhost:<port>` 또는 `127.0.0.1:<port>`가 아니면 403.
  - Origin이 있으면 같은 집합에 들어 있어야 한다.
  - `POST`는 `Content-Type`이 `application/json` 또는 `multipart/form-data`여야 한다.
- **`POST /api/scans`**
  - JSON `{target, ref?, deep?}` 또는 multipart(`file` + `deep`).
  - `parseTarget`이 실패하면 400 `{error}`.
  - 업로드는 `jobs/<id>/upload.tar`로 저장한다.
  - 응답은 201 `{id}`.
- **`GET /api/scans/{id}/files/{name}`**: `name`은 `sbom.cdx.json|sbom.spdx.json|report.html|result.json|raw.zip` 화이트리스트만 허용한다. `raw.zip`은 `raw/`를 즉석에서 zip으로 스트리밍한다. 그 외 이름은 404(경로 탐색 차단).

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
@Test fun `cli parsing`() {
    assertEquals(CliCommand.Scan(ScanInput("node:16-slim", null, true), Path.of("o"), true), parseCli(arrayOf("scan", "node:16-slim", "--deep", "--strict", "-o", "o")))
    assertEquals(CliCommand.Serve(8080), parseCli(arrayOf("serve")))
    assertTrue(parseCli(arrayOf("scan")) is CliCommand.Usage)
}
@Test fun `exit codes`() {
    assertEquals(0, exitCode(result(Status.DONE), false)); assertEquals(2, exitCode(result(Status.PARTIAL), false))
    assertEquals(1, exitCode(null, false)); assertEquals(1, exitCode(result(Status.DONE, Warning("NTIA_MISSING", "x")), true))
    assertEquals(2, exitCode(result(Status.DONE, Warning("NTIA_MISSING", "x")), false))
}
// ScanControllerTest: @WebMvcTest + MockMvc, JobRunner/JobStore는 @MockitoBean
@Test fun `rejects foreign host and origin`() { /* Host: evil.com → 403; Origin: https://evil.com → 403 */ }
@Test fun `bad target is 400`() { /* {"target":"--privileged"} → 400 */ }
@Test fun `file name outside whitelist is 404`() { /* /api/scans/x/files/..%2Fstatus.json → 404 */ }
@Test fun `json submit returns 201 with id`() { /* {"target":"node:16-slim"} → 201, JobRunner.submit 호출 확인 */ }
```

- [ ] **Step 2: 실행해서 실패 확인.** `./gradlew test --tests 'sca.cli.*' --tests 'sca.web.*'` → FAIL.
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 테스트 통과 확인.** → PASS. 그다음 `./gradlew test` 전체도 PASS.
- [ ] **Step 5: 커밋.** `feat(backend): cli mode, rest api with sse and host guard`

---

### Task 11: 프론트엔드 스캐폴딩, 스캔 시작, 작업 목록과 진행 상황

**Files:**
- Create: `frontend/` (`npm create vite@latest frontend -- --template react-ts`), `frontend/src/{api.ts,types.ts,main.tsx}`, `frontend/src/pages/{ScanForm,JobList}.tsx`
- Modify: `frontend/vite.config.ts`

**Interfaces:**
- Consumes: Task 10 REST API, Task 1 모델(JSON 형태).
- Produces:
  - `types.ts`: `ScanResult`, `Component`, `Sourced`, `JobStatus`, `Status`를 Kotlin 모델과 같은 필드 이름으로 정의한다. `edges`는 `{first: string, second: string}[]`.
  - `api.ts`: `submitScan(body: {target: string; ref?: string; deep?: boolean} | FormData): Promise<{id: string}>`, `listScans(): Promise<JobStatus[]>`, `getScan(id): Promise<JobStatus>`, `getResult(id): Promise<ScanResult>`, `watch(id, onStatus: (s: JobStatus) => void): () => void`(EventSource), `fileUrl(id, name): string`.

**규칙:**
- **라우팅**은 `location.hash`로 한다(`#/`, `#/jobs`, `#/jobs/<id>`). 라우터 라이브러리는 쓰지 않는다.
- **`vite.config.ts`**: dev 서버에서 `/api`를 `http://127.0.0.1:8080`으로 프록시한다. `build.outDir='../backend/src/main/resources/static'`, `emptyOutDir: true`.
- **ScanForm**: 라디오로 repo URL(+ ref) / 이미지 레퍼런스 / tar 업로드를 고르고, `deep` 체크박스를 둔다. 제출하면 `#/jobs/<id>`로 이동한다. 서버의 400 `error` 메시지를 폼 아래에 보여 준다.
- **JobList**: 상태 배지, 대상, 시각을 보여 준다. 5초마다 폴링한다.
- 스타일은 CSS 파일 하나(`index.css`)다. UI 라이브러리는 쓰지 않는다.

- [ ] **Step 1: 스캐폴딩 생성 후 `npm i -D vitest`.**
- [ ] **Step 2: 위 파일 구현.**
- [ ] **Step 3: 검증.** `cd frontend && npx tsc --noEmit && npm run build` → 오류 없음, `backend/src/main/resources/static/index.html` 생성됨.
- [ ] **Step 4: 수동 확인.** `cd backend && ./gradlew bootRun --args=serve`와 `cd frontend && npm run dev`를 띄우고, 브라우저에서 `node:16-slim` 스캔을 제출해 작업 목록에 나타나는지 확인한다.
- [ ] **Step 5: 커밋.** `feat(frontend): scaffold, scan form and job list`

---

### Task 12: 프론트엔드 결과 화면

**Files:**
- Create: `frontend/src/graph.ts`, `frontend/src/graph.test.ts`, `frontend/src/pages/JobView.tsx`, `frontend/src/components/{Summary,ComponentTable,DepTree,ComponentDetail}.tsx`

**Interfaces:**
- Consumes: Task 11 `api.ts`, `types.ts`.
- Produces (`graph.ts`):
  - `children(r: ScanResult): Map<string, string[]>`
  - `pathTo(r: ScanResult, key: string): string[] | null`: roots에서 key까지의 최단 경로를 BFS로 찾는다(방문 집합 사용).

**화면 (스펙 §9):**
- **JobView**: 상단에 상태와 단계를 표시한다(`watch`로 갱신). 도구별 성공/실패와 로그 파일명을 보여 준다. 종료되면 `getResult`를 호출하고 Summary와 탭(컴포넌트 | 의존 트리)을 보여 준다. 다운로드 링크 4개(`fileUrl`)를 둔다.
- **Summary**: 상태, 경고 목록, 컴포넌트 수(direct/transitive), 생태계별 수, 라이선스별 상위 10개, 검토 필요 수.
- **ComponentTable**
  - 열: 이름, 버전, 생태계, 라이선스, direct/transitive, scope, 다운로드 URL(링크), 공급자, foundBy, 검토 필요.
  - 텍스트 검색과 필터(생태계, direct/transitive, 검토 필요만)를 둔다.
  - 행을 클릭하면 ComponentDetail을 연다.
  - 수천 행도 무리 없게 결과를 500행씩 "더 보기"로 나눈다.
- **DepTree**: roots부터 펼친다. 펼칠 때 자식을 렌더링하고, 이미 조상 경로에 있는 노드는 "(순환)" 표시와 함께 더 펼치지 않는다.
- **ComponentDetail**: 필드마다 값과 출처(`source`), `conflicts`, `unresolved`, `locations`, 그리고 `pathTo` 결과를 "포함 경로: a → b → c"로 보여 준다.

- [ ] **Step 1: 실패하는 테스트 작성** (`graph.test.ts`)

```ts
const r = { roots: ["a"], edges: [{first:"a",second:"b"},{first:"b",second:"c"},{first:"c",second:"b"},{first:"a",second:"d"}] } as ScanResult
test("shortest path from root", () => expect(pathTo(r, "c")).toEqual(["a", "b", "c"]))
test("terminates on cycles and returns null when unreachable", () => expect(pathTo(r, "zzz")).toBeNull())
test("root itself", () => expect(pathTo(r, "a")).toEqual(["a"]))
```

- [ ] **Step 2: 실행해서 실패 확인.** `cd frontend && npx vitest run` → FAIL.
- [ ] **Step 3: `graph.ts`와 화면 컴포넌트 구현.**
- [ ] **Step 4: 검증.** `npx vitest run && npx tsc --noEmit && npm run build` → 모두 성공.
- [ ] **Step 5: 수동 확인.** Task 11의 dev 환경에서 끝난 작업을 열어 lodash를 검색하고, 상세 화면에 "출처 cdxgen"과 포함 경로가 나오는지, 의존 트리가 펼쳐지는지 확인한다.
- [ ] **Step 6: 커밋.** `feat(frontend): result view with summary, table, tree and provenance`

---

### Task 13: 단일 jar 빌드와 실제 대상 통합 테스트

**Files:**
- Create: `build.sh`, `backend/src/test/kotlin/sca/IntegrationTest.kt`, `backend/src/test/resources/golden.json`

**Interfaces:**
- Consumes: 전부.

**`build.sh`**: `set -euo pipefail; (cd frontend && npm ci && npm run build); (cd backend && ./gradlew bootJar)`, 그리고 `backend/build/libs/sca.jar` 경로를 출력한다.

**통합 테스트** (`@Tag("integration")`, docker와 네트워크 필요): 실제 `DockerRunner`와 Enricher로 `Pipeline`을 돌린다.
- **lab 디렉터리**: `parseTarget`은 로컬 디렉터리를 받지 않으므로, Acquirer를 `Acquired(REPO, lab 복사본, "local")`을 반환하는 서브클래스로 바꿔 주입한다. 검증 항목은 다음과 같다.
  - lodash 다운로드 URL, MIT
  - axios direct
  - 상태 DONE
  - 두 SBOM 스키마 경고 없음
- **`node:16-slim` 이미지**: 다음을 확인한다.
  - 상태 DONE 또는 PARTIAL
  - 버전 없는 generic 0건
  - `libacl1` 존재, `acl` 단독 컴포넌트 없음
  - deb 컴포넌트 다운로드 URL 채움률 90% 이상
- **골든 값**: 첫 실행에서 생태계별 컴포넌트 수를 `golden.json`에 기록한다. 이후 실행에서는 ±5% 안에 들어야 한다(도구 버전을 올리면 다시 기록).

- [ ] **Step 1: 테스트 작성 후 실행.** `./gradlew integrationTest` → 처음에는 golden 없음으로 FAIL.
- [ ] **Step 2: golden 기록.** `-Dsca.recordGolden=true`로 한 번 실행하고, 기록된 값이 `lab/out` 실측(npm 56~57, deb 85 근처)과 비슷한지 눈으로 확인한다.
- [ ] **Step 3: 재실행.** `./gradlew integrationTest` → PASS.
- [ ] **Step 4: 전체 확인.** `./build.sh && java -jar backend/build/libs/sca.jar scan node:16-slim -o /tmp/sca-out; echo $?` → 종료 코드 0 또는 2, `/tmp/sca-out`에 SBOM 2개와 report.html. 이어서 `java -jar backend/build/libs/sca.jar serve` 후 `http://127.0.0.1:8080`에 React 화면이 뜨는지 확인한다.
- [ ] **Step 5: 커밋.** `test: end-to-end integration on lab dir and node image; single jar build`

---

### Task 14: 도구 분석 (스펙 1단계)과 병합 규칙 확정

**Files:**
- Create: `docs/analysis/tool-analysis.md`, `docs/analysis/benchmarks.json`
- Modify: `backend/src/main/resources/merge-rules.yml`, 필요하면 `Pipeline.kt`의 TRANSITIVE_INCOMPLETE 표지
- Test: `IntegrationTest.kt`에 벤치마크 케이스 추가

**벤치마크 세트**: 생태계마다 작은 공개 repo 1개를 고른다. 대상 생태계는 npm, maven, gradle, lockfile 없는 pip(`requirements.txt`만), poetry, go, cargo, .NET, C/C++(conan 또는 vendored 소스)이고, 여기에 `node:16-slim`을 더한다. `benchmarks.json`에 `{name, url, sha}`로 고정한다(sha는 `git ls-remote`로 얻는다).

**`tool-analysis.md` 내용:**
1. 도구별 탐지 근거: lockfile, manifest, 설치 DB, 바이너리 시그니처, 파일 매칭. 각 도구의 문서와 `raw/` 결과에서 확인한 내용을 쓴다.
2. 벤치마크별 필드 커버리지 실측표: 도구 × 필드(버전, 라이선스, 해시, 다운로드 URL, 공급자, 소스 저장소, 그래프, scope)의 채움률. 각 job의 `raw/`에 대해 `Readers.read`로 계산하며, 계산 스크립트는 테스트 유틸 함수 하나다.
3. 스펙 §11 가정 4개의 결과.
   - trivy의 deb 라이선스 커버리지.
   - cdxgen 이미지의 툴체인 누락 여부.
   - scancode 소요 시간.
   - 이미지 npm 207 대 214 차이의 원인.
4. 그 결과로 바꾼 `merge-rules.yml` 항목과 이유.

- [ ] **Step 1: 벤치마크 고정.** `benchmarks.json` 작성, `IntegrationTest`에 벤치마크별로 "상태가 FAILED가 아니고 컴포넌트가 1개 이상" 케이스를 추가한다.
- [ ] **Step 2: 실행.** `./gradlew integrationTest -Dsca.keepJobs=true` → 각 job의 `raw/`와 `result.json`을 확보한다.
- [ ] **Step 3: 커버리지 표와 분석 문서 작성.**
- [ ] **Step 4: 규칙 조정.** 실측에 맞게 `merge-rules.yml`과 TRANSITIVE_INCOMPLETE 표지를 고친다. 그다음 `./gradlew test integrationTest` → PASS. 단위 테스트의 기대 출처(`Sourced("MIT", "cdxgen")` 등)가 바뀌면 그 이유를 문서에 적고 테스트를 함께 수정한다.
- [ ] **Step 5: 커밋.** `docs: tool analysis with measured field coverage; tune merge rules`
