# 통합 SCA 툴 설계 (uni-sca)

- 작성일: 2026-10-15
- 상태: 리뷰 대기

## 1. 목적과 성공 기준

GitHub repo URL 또는 도커 이미지(레퍼런스·tar 파일)를 입력받아 **컴플라이언스 제출용 SBOM**을 만든다.
여러 SCA 도구(cdxgen, syft, trivy, scancode)를 샌드박스에서 실행하고, 결과를 PURL 기준으로 병합·보강해 하나의 SBOM으로 낸다.
도구를 여러 개 쓰는 목적은 비교가 아니라 **서로의 빈 필드를 메우는 것**이다. 비교 정보(`foundBy`, `conflicts`)는 감사 근거로 남는 부산물이다.

성공 기준:

1. 컴포넌트마다 이름, 버전, PURL, 라이선스(SPDX 표현식), direct/transitive 구분, 의존 관계, 출처(registry, 공급자, 소스 저장소), **실제 다운로드 URL**, 해시를 제공한다. 값을 구할 수 없으면 `unresolved`로 명시한다.
2. 출력은 CycloneDX 1.6 JSON과 SPDX 2.3 JSON이며, 두 포맷 모두 스키마 검증과 NTIA 최소 요소 검증(공급자, 이름, 버전, 고유 ID, 의존 관계, SBOM 작성자, 타임스탬프)을 통과한다.
3. 모든 필드 값은 어느 도구 또는 API에서 왔는지 추적할 수 있다.
4. 불완전한 결과(스캐너 실패, transitive 해석 실패)가 조용히 넘어가지 않는다. SBOM 메타데이터와 리포트에 드러난다.
5. 지원 생태계: JavaScript(npm/yarn/pnpm), Java(Maven/Gradle), Python(pip/poetry/uv), Go, Rust, .NET, Ruby, PHP 등 사용하는 도구들이 지원하는 생태계 전부, 그리고 이미지 OS 패키지(deb/rpm/apk). C/C++은 conan/vcpkg 매니페스트와, `--deep` 옵션 시 scancode 파일 스캔으로 vendored 소스를 탐지한다.

## 2. 범위와 단계

| 단계 | 산출물 |
|---|---|
| 1. 도구 분석 | `docs/analysis/tool-analysis.md`: 도구별 탐지 근거(lockfile, manifest, 설치 DB, 바이너리 시그니처, 파일 매칭)와 벤치마크 세트(§10)의 필드 커버리지 실측표. §4 ④의 병합 우선순위를 실측으로 확정하거나 수정한다. |
| 2. 엔진 + CLI | Spring Boot(Kotlin) 백엔드의 파이프라인 ①~⑥과 CLI 모드 |
| 3. 웹 UI | React 프론트엔드. 2단계의 REST API를 사용한다. |

비범위 (필요해질 때 추가):

- 취약점(CVE) 스캔. SBOM을 Dependency-Track이나 grype에 넣으면 된다.
- 로그인/권한. 대신 기본 바인딩을 localhost로 둔다.
- DB. 작업 결과는 파일시스템에 둔다.
- cdxgen 컨테이너의 egress를 registry로만 제한하는 프록시.
- 프론트엔드 E2E 테스트.

## 3. 저장소 구성

```
uni-sca/
├── backend/    Spring Boot 3 + Kotlin (Gradle). 엔진, REST, CLI 모드
├── frontend/   React + Vite + TypeScript. 빌드 결과는 backend jar의 static 리소스로 포함
├── lab/        기존 도구 비교 랩 (현재 frontend/의 demo.sh, summary.sh, server.js, index.html, package.json, out/)
└── docs/
```

배포 산출물은 jar 하나다.

```
java -jar sca.jar scan <repo-url | image-ref | image.tar> [--ref <branch|tag>] [--deep] [--strict] -o <dir>
java -jar sca.jar serve [--port 8080]      # 127.0.0.1에 바인딩
```

CLI 모드는 `spring.main.web-application-type=none`으로 실행하며, 같은 파이프라인 서비스를 호출한다.

주요 라이브러리: `packageurl-java`, `cyclonedx-core-java`, `Spdx-Java-Library`, Spring MVC(SSE), Jackson. 스캐너는 `docker` CLI를 `ProcessBuilder`로 호출한다.

## 4. 파이프라인

```
① Acquire → ② Scan(병렬) → ③ Normalize → ④ Merge → ⑤ Enrich → ⑥ Emit
```

### ① Acquire (호스트)

- **repo**: `git clone --recurse-submodules` (`--ref`가 있으면 해당 브랜치/태그). 커밋 SHA를 기록한다. clone 과정에서 repo 코드는 실행하지 않는다(`core.hooksPath=/dev/null`).
- **image-ref**: 호스트에서 `docker pull`한 뒤 `docker save`로 tar를 만든다. private registry 인증은 호스트의 docker 자격 증명만 사용한다. 이미지 digest를 기록한다.
- **image.tar**: 그대로 사용하고 sha256을 기록한다.

### ② Scan (컨테이너)

| 도구 | 대상 | 마운트 | 네트워크 |
|---|---|---|---|
| cdxgen (공식 이미지, 툴체인 포함) | repo, image tar | repo는 **쓰기 가능한 복사본** (빌드 해석 시 `node_modules`, `target/` 생성) | 허용 (빌드 도구의 registry 접근) |
| syft | repo, image tar | read-only | `none` |
| trivy | repo, image tar | read-only, DB 볼륨 | `none` (`--offline-scan`, DB는 서버 기동 시·하루 1회 갱신) |
| scancode (`--deep`일 때) | repo | read-only | `none` |

공통 규칙:

- 이미지는 버전과 digest를 고정하고 `application.yml`에 둔다. 고정한 값은 SBOM `metadata.tools`에 기록한다.
- 컨테이너는 비root로 실행한다. `--cap-drop ALL`, `--security-opt no-new-privileges`, CPU·메모리 제한, 도구별 타임아웃(기본 15분)을 건다.
- docker 소켓은 마운트하지 않는다.
- lockfile이 없을 때 transitive 의존성 해석은 cdxgen의 빌드 해석(mvn, gradle, pip 등)에 맡긴다.
- 도구들은 병렬로 실행한다. 각 도구의 stdout 결과와 로그를 `raw/`에 그대로 보관한다.

### ③ Normalize

각 도구 결과를 공통 모델(§5)로 변환한다.

- **병합 키**: `type/namespace/name@version`. qualifier(arch, distro 등)는 키에서 빼고 속성으로 보관한다. 생태계 규칙에 따라 이름을 정규화한다(pypi는 PEP 503: 소문자, `_`/`.`를 `-`로. npm·golang 등은 PURL 스펙 규칙을 따른다).
- **버전 없는 `pkg:generic` 항목은 제거한다.** 실측에서 cdxgen이 이미지 안의 실행 파일(`bash`, `cat` 등) 312개를 이 형태로 냈다.
- **OS 패키지의 소스/바이너리 매핑**: 컴포넌트 단위는 설치된 바이너리 패키지(예: `libacl1`)다. 소스 패키지(예: cdxgen의 `acl`)는 같은 버전이고 이름이 바이너리 패키지의 `upstream`과 일치하면 그 바이너리 패키지에 흡수한다. 일치하는 것이 없으면 독립 컴포넌트로 남긴다.
- PURL이 없는 항목은 `name@version`을 키로 남기고 `needsReview=true`로 표시한다.

### ④ Merge

- 컴포넌트 집합은 모든 도구 결과의 **합집합**이다. `foundBy`에 발견한 도구 목록을 기록하고, 한 도구에서만 발견된 항목은 리포트에서 따로 표시한다.
- 필드 값은 아래 우선순위로 정한다. 값이 서로 다르면 선택된 값 외의 값들을 `conflicts`에 기록하고 `needsReview=true`로 표시한다.
- 아래 표는 초기 가설이고, 1단계 실측 결과로 확정한다. 우선순위는 코드가 아닌 설정 파일(`merge-rules.yml`)로 둔다.

| 필드 | 우선순위 (앞이 우선) |
|---|---|
| 버전 | lockfile/설치 DB에서 읽은 값 → manifest 선언값 |
| 의존 그래프 | 앱 생태계는 cdxgen → syft → trivy, OS 패키지는 syft → trivy. 선택된 도구의 엣지만 사용한다(엣지 합집합은 거짓 경로를 만든다). |
| direct/transitive | 의존 그래프에서 루트의 직속 자식이면 direct, 나머지는 transitive |
| scope (required/optional/dev) | cdxgen |
| 라이선스 | 패키지 선언(cdxgen, syft) → OS 패키지는 trivy(copyright 파싱) → deps.dev → ClearlyDefined |
| 해시 | lockfile integrity, 설치 DB 체크섬 (cdxgen, syft) |
| 다운로드 URL | lockfile `resolved` → PURL `repository_url`/`download_url` qualifier → 생태계별 URL 템플릿 |
| registry | PURL `repository_url` → 생태계 기본 registry |
| 공급자 | 패키지 메타데이터(deb maintainer, npm author, maven organization) → deps.dev |
| 소스 저장소 | 패키지 메타데이터 → deps.dev `SOURCE_REPO` |
| 파일 위치 | 합집합 |

라이선스는 SPDX 표현식으로 정규화한다. SPDX ID에 매칭되지 않는 텍스트는 `LicenseRef-<해시>`로 두고 원문을 보관한다. syft가 내는 해시 형태의 라이선스(`sha256:...`)는 값으로 쓰지 않고, 그 필드를 빈 값으로 취급한다.

### ⑤ Enrich

- **비어 있는 필드만 채운다.** 도구가 직접 관찰한 값은 덮어쓰지 않는다.
- **다운로드 URL 템플릿** (Kotlin, 생태계별): npm, maven, pypi, golang(proxy.golang.org), cargo(crates.io), nuget, gem, composer, deb/rpm/apk는 distro 미러 경로. 템플릿이 없는 생태계는 `unresolved`로 둔다.
- **deps.dev API v3**: 라이선스, 링크(SOURCE_REPO, HOMEPAGE), registry. npm, maven, pypi, go, cargo, nuget을 지원하고 OS 패키지는 지원하지 않는다.
- **ClearlyDefined**: deps.dev에서 라이선스를 못 구한 경우 사용한다.
- 응답은 PURL 단위로 로컬 파일 캐시(`data/cache/`)에 저장한다. 재시도는 지수 백오프로 최대 3회 하고, 그래도 실패하면 필드를 `unresolved`로 표시한다.

### ⑥ Emit

- `sbom.cdx.json` (CycloneDX 1.6)
  - `metadata`: 대상(repo URL + 커밋 SHA, 또는 이미지 digest), 사용한 도구와 버전, 작성자(이 도구), 타임스탬프, 작업 상태(DONE/PARTIAL)와 경고.
  - 컴포넌트: `purl`, `licenses`, `hashes`, `scope`, `supplier`, `externalReferences`(distribution = 다운로드 URL, vcs = 소스 저장소). 필드 출처와 `foundBy`는 `properties`(`sca:source:<field>`, `sca:foundBy`)에 담는다.
  - `dependencies`: 병합된 의존 그래프.
- `sbom.spdx.json` (SPDX 2.3): 같은 병합 모델에서 직접 생성한다(CycloneDX를 거쳐 변환하지 않는다). `downloadLocation`, `licenseDeclared`, `externalRefs`(purl), `relationships`(DEPENDS_ON)를 쓴다.
- `report.html`: 사람이 검토할 요약 리포트. 정적 HTML 1개다.
- 생성한 두 SBOM은 각각 스키마 검증과 NTIA 최소 요소 검증을 거친다. 실패하면 경고로 남기고, `--strict`일 때는 작업을 실패 처리한다.

## 5. 데이터 모델

```kotlin
data class Sourced<T>(val value: T, val source: String, val evidence: String? = null)
// source 예: "cdxgen", "syft", "trivy", "scancode", "deps.dev", "clearlydefined", "template"

data class Component(
    val key: String,                       // type/namespace/name@version
    val purl: String?,
    val name: String,
    val version: String?,
    val ecosystem: String,
    val qualifiers: Map<String, String>,
    val licenses: Sourced<String>?,        // SPDX 표현식
    val hashes: List<Sourced<Hash>>,
    val scope: Sourced<String>?,
    val downloadUrl: Sourced<String>?,
    val registry: Sourced<String>?,
    val supplier: Sourced<String>?,
    val sourceRepo: Sourced<String>?,
    val locations: Set<String>,
    val foundBy: Set<String>,
    val conflicts: List<Conflict>,         // field, 선택되지 않은 값과 출처
    val unresolved: Set<String>,           // 구하지 못한 필드 이름
    val needsReview: Boolean,
)

data class ScanResult(
    val target: Target,                    // 종류, 원본 입력, 커밋 SHA 또는 digest
    val status: Status,                    // DONE | PARTIAL | FAILED
    val tools: List<ToolRun>,              // 도구, 버전, 성공 여부, 소요 시간, 로그 경로
    val components: List<Component>,
    val edges: Set<Pair<String, String>>,  // key → key
    val roots: Set<String>,
    val warnings: List<Warning>,           // 실패한 도구, transitive 불완전 manifest, NTIA 누락
)
```

`result.json`은 `ScanResult`를 직렬화한 것이며, 웹 UI가 읽는 유일한 결과 형식이다.

## 6. 작업 모델

- 스캔 1건은 `data/jobs/<id>/` 디렉터리 하나에 대응한다.

  ```
  input.json                      입력과 옵션
  status.json                     상태와 현재 단계
  raw/<tool>.json, raw/<tool>.log 도구 원본 결과와 로그
  result.json                     병합 결과 (ScanResult)
  sbom.cdx.json, sbom.spdx.json, report.html
  ```

- 상태: `QUEUED → RUNNING(단계명) → DONE | PARTIAL | FAILED`.
- 실행기는 고정 크기 스레드 풀(기본 2)이다. 서버가 재시작되면 RUNNING 상태로 남은 작업은 FAILED("서버 재시작")로 표시한다.
- 작업 디렉터리(`data/jobs/<id>/`)는 감사 증적이므로 자동으로 삭제하지 않는다. clone 작업 공간과 image-ref로 만든 tar는 작업 종료 후 삭제한다.

## 7. 에러 처리

| 상황 | 처리 |
|---|---|
| 스캐너 1개 실패 또는 타임아웃 | 나머지 결과로 계속 진행한다. 상태는 PARTIAL이고, 실패한 도구와 로그 경로를 SBOM 메타데이터와 리포트에 표시한다. |
| 모든 스캐너 실패 | FAILED |
| clone/pull 실패, 잘못된 입력 | FAILED, 원인 메시지 표시 |
| lockfile 없음 + 빌드 해석 실패 | 해당 manifest마다 "transitive 불완전" 경고 |
| 보강 API 실패 또는 rate limit | 재시도 후 `unresolved` 표시 |
| NTIA 최소 요소 누락 | 경고. `--strict`일 때는 FAILED |

CLI 종료 코드: `0` DONE, `2` PARTIAL 또는 검증 경고, `1` FAILED.

## 8. REST API

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/scans` | JSON(`{target, ref?, deep?}`) 또는 multipart(이미지 tar 업로드). 응답은 `{id}`. |
| GET | `/api/scans` | 작업 목록 (상태, 대상, 시각) |
| GET | `/api/scans/{id}` | 상태와 도구별 진행 상황 |
| GET | `/api/scans/{id}/events` | SSE: 단계 변경, 도구 완료, 종료 |
| GET | `/api/scans/{id}/result` | `result.json` |
| GET | `/api/scans/{id}/files/{name}` | `sbom.cdx.json`, `sbom.spdx.json`, `report.html`, `raw.zip` |

서버는 기본적으로 `127.0.0.1`에 바인딩하고, Host와 Origin 헤더를 검증한다(기존 랩 `server.js`와 같은 이유: 이 서버는 docker를 실행한다).

입력 검증: repo URL은 `https://` git URL만 받는다. image-ref는 docker 레퍼런스 문법으로 검증한다. 업로드 tar는 크기 상한(기본 10GB)을 둔다. 모든 외부 입력은 셸을 거치지 않고 인자 배열로 프로세스에 넘긴다.

## 9. 웹 UI (React)

1. **스캔 시작**: repo URL(+ ref), 이미지 레퍼런스, 이미지 tar 업로드 중 하나와 `deep` 옵션.
2. **작업 목록과 진행 상황**: 단계별 상태(SSE), 도구별 성공/실패.
3. **결과 화면**
   - 요약: 컴포넌트 수(direct/transitive), 생태계별 수, 라이선스 분포, NTIA 검증 결과, PARTIAL 경고, 검토 필요 건수.
   - 컴포넌트 테이블: 이름, 버전, 생태계, 라이선스, direct/transitive, scope, 다운로드 URL, 공급자·출처, 발견 도구, 검토 필요 표시. 검색과 필터를 지원한다.
   - 의존 트리: 루트부터 펼쳐 볼 수 있다. 컴포넌트를 선택하면 루트에서 그 컴포넌트까지의 경로("왜 포함됐는지")를 보여 준다.
   - 컴포넌트 상세: 필드별 값과 출처, 충돌 내역, unresolved 필드.
4. **다운로드**: CycloneDX, SPDX, report.html, 원본 결과 zip.

## 10. 테스트

- **병합 로직 단위 테스트**: fixture는 랩의 실제 도구 출력(`lab/out/syft-dir.json`, `cdxgen.json`, `trivy-dir.cdx.json`, `syft-img.json`, `cdxgen-img.json`)이다. 검증 대상:
  - `lodash@4.17.15`가 1건으로 병합되고, 다운로드 URL `https://registry.npmjs.org/lodash/-/lodash-4.17.15.tgz`, 라이선스 MIT, foundBy={cdxgen, syft, trivy}를 가진다.
  - 이미지 결과에서 버전 없는 `pkg:generic` 항목이 0건이다.
  - 소스 패키지 `acl`이 `libacl1`에 흡수된다.
  - 의존 그래프에서 `axios`는 direct이고 `follow-redirects`는 transitive다.
- **URL 템플릿**: 생태계별 테이블 테스트.
- **보강**: 녹화한 deps.dev/ClearlyDefined 응답으로 HTTP를 mock한다(MockWebServer). 실패와 429 응답 시 `unresolved` 처리를 확인한다.
- **출력 검증**: 생성한 CycloneDX는 cyclonedx-core-java 검증기, SPDX는 Spdx-Java-Library 검증기로 확인한다. NTIA 검증 로직도 같이 테스트한다.
- **통합 테스트** (`@Tag("integration")`, docker 필요): 생태계별 작은 공개 repo 벤치마크 세트(npm, maven, gradle, lockfile 없는 pip, poetry, go, cargo, .NET, C/C++)와 `node:16-slim` 이미지에 대해 전체 파이프라인을 돌린다. 컴포넌트 수는 허용 오차를 둔 골든 값과 비교한다. 이 세트는 1단계 도구 분석의 실측 데이터로도 쓴다. 대상 repo는 커밋 SHA로 고정한다.
- **프론트엔드**: `tsc --noEmit`과 `vite build`.

## 11. 1단계에서 확인할 가정

- trivy가 deb/rpm 패키지의 라이선스를 copyright 파일에서 SPDX ID로 뽑아 주는지, 그리고 그 커버리지.
- cdxgen 공식 이미지가 지원 생태계의 빌드 툴체인(JDK, gradle, python, go, cargo, dotnet)을 모두 포함하는지. 빠진 게 있으면 언어별 이미지 변형을 쓴다.
- scancode 공식 컨테이너 이미지의 실행 방식과, 중간 크기 C/C++ repo에서의 소요 시간.
- 이미지 입력에서 cdxgen과 syft의 앱 패키지(npm 등) 결과 차이(207 대 214)의 원인.
