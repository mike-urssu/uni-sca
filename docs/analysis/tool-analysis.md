# SCA 도구 분석: 탐지 원리와 실측 커버리지

- 작성일: 2026-10-15
- 대상 버전: cdxgen 11.11.0, syft 1.54.1, trivy 0.76.0, scancode-toolkit 32.5.0
- 실측 데이터: [`coverage.md`](coverage.md)(전체 표), [`benchmarks.json`](benchmarks.json)(대상 repo, 태그, 해석된 커밋 SHA)
- 재현:
  1. `./build.sh`로 jar를 만든다.
  2. `benchmarks.json`의 각 대상에 `java -jar backend/build/libs/sca.jar scan <target> --ref <ref> -o <dir>/<name>`을 실행한다.
  3. `cd backend && ./gradlew analysis -Dsca.benchDir=<dir> -Dsca.labOut=../lab/out`로 표를 만든다.

이 문서는 스펙의 1단계 산출물입니다. 병합 우선순위(`backend/src/main/resources/merge-rules.yml`)와 병합 로직은 이 실측을 근거로 확정했습니다.

## 1. 도구별 탐지 원리

| 도구 | 무엇을 근거로 찾는가 | transitive 해석 | 이번 실측에서 드러난 특징 |
|---|---|---|---|
| **cdxgen** | 언어별 manifest와 lockfile 파서. lockfile이 없으면 **빌드 도구를 실제로 실행**한다(`npm install`, `mvn`, `gradle`, `pip`, `dotnet restore` 등). 이미지는 레이어를 풀어 OS 패키지 DB와 언어 manifest를 읽는다. | lockfile이 있으면 그 그래프를 쓰고, 없으면 빌드 도구의 해석 결과를 쓴다. 이 경우 **유일하게 transitive를 얻는 도구**다. | 의존 그래프, `scope`, evidence를 낸다. lockfile의 `resolved` URL과 integrity 해시도 낸다. 이미지에서는 실행 파일을 버전 없는 `pkg:generic`으로 대량 출력하고(node:16-slim에서 312개), OS 패키지를 **소스 패키지** 이름(`acl`)으로 내기도 한다. |
| **syft** | 카탈로거 방식이다. 설치 DB(dpkg/rpm/apk), lockfile과 manifest, 바이너리 시그니처(Go buildinfo, jar의 pom.properties 등)를 읽는다. 빌드는 실행하지 않는다. | lockfile에 그래프가 있을 때만 `dependency-of` 관계를 낸다. | **설치된 바이너리 패키지** 단위로 내고 `upstream` qualifier로 소스 패키지를 알려 준다. 설치 위치마다 항목을 하나씩 낸다. deb 라이선스는 copyright 파일의 Debian 표기(`GPL-2+`)나 해시로 나온다. 공급자(deb maintainer)를 채우는 유일한 도구다(이미지 99%). |
| **trivy** | analyzer 방식이다. 원리는 syft와 비슷하다(lockfile, OS 패키지 DB, jar). 빌드는 실행하지 않는다. | lockfile과 OS DB에 있는 관계만 쓴다. | lockfile과 OS 패키지 DB를 PURL 없는 `application`/`operating-system` 노드로 묶는다. deb copyright를 **SPDX ID로 정규화**한다(이미지 라이선스 94%). epoch를 `epoch` qualifier로 분리한다. lockfile이 없는 npm(express)에서는 0개다. |
| **scancode** | 모든 파일의 내용을 라이선스와 저작권 규칙 DB와 대조하고, 패키지 manifest도 탐지한다. | 해석하지 않는다. | jq(파일 540개)에서 패키지는 0개였지만 파일 단위 라이선스 검출은 10종이 넘었다(BSD, `GPL-3.0-or-later WITH Bison-exception-2.2`, ICU 등). **vendored C/C++ 코드는 컴포넌트가 아니라 파일 단위로만 드러난다.** |

## 2. 실측 요약 (조정 후, `coverage.md`의 "Merged result" 표)

| 벤치마크 | 컴포넌트 | direct | 라이선스 | 다운로드 URL | 해시 | 검토 필요 |
|---|---|---|---|---|---|---|
| npm express (lockfile 없음) | 382 | 47 | 97% | 97% | 97% | 0 |
| maven jsoup | 33 | 5 | 63% | 63% | 0% | 0 |
| gradle gs-rest-service | 83 | 3 | 90% | 90% | 0% | 0 |
| pip microblog (requirements.txt만) | 54 | 54 | 100% | 100% | 0% | 0 |
| poetry cleo | 66 | 2 | 92% | 92% | 0% | 0 |
| go cobra | 12 | 4 | 50% | 50% | 33% | 0 |
| cargo ripgrep | 72 | 20 | 83% | 83% | 70% | 10 |
| .NET serilog | 94 | 11 | 90% | 97% | 86% | 0 |
| C jq (`--deep`) | 29 | 14 | 48% | 48% | 0% | 0 |
| image node:16-slim | 298 | 86 | 95% | 99% | 94% | 35 |

필드별로 관찰한 내용:

- **transitive 의존성**: lockfile이 없는 npm, Maven, Gradle, .NET에서는 cdxgen만 transitive를 얻는다. 예를 들어 express에서 cdxgen은 380개를 찾았고, syft는 9개, trivy는 0개였다. 빌드 해석을 하는 도구가 반드시 하나는 있어야 한다는 스펙의 가정이 확인됐다.
- **라이선스**: 도구 출력만 보면 앱 생태계는 npm과 pypi를 빼고 대부분 0%다. **deps.dev와 ClearlyDefined 보강이 63~92%를 채운다.** OS 패키지는 trivy가 SPDX ID를 주므로 trivy를 우선한다.
- **다운로드 URL**: lockfile `resolved`(cdxgen, syft)가 있으면 그 값을 쓰고, 없으면 PURL 템플릿이 채운다(이미지 297/298). 빈 칸은 거의 모두 템플릿이 없는 PURL type이다(아래 §5).
- **공급자**: 앱 생태계는 모든 도구가 0%라서 NTIA 경고의 주된 원인이다(§5). OS 패키지만 syft가 채운다.

## 3. 스펙 §11 가정의 검증 결과

1. **trivy가 deb 라이선스를 SPDX ID로 주는가**: 그렇다. node:16-slim에서 trivy 94%, syft 84%(Debian 표기라 SPDX가 아님), cdxgen 62%였다. 병합된 OS 패키지 라이선스는 trivy 71건, cdxgen 209건(npm), syft 4건이 출처다. syft와 trivy의 표기가 달라 생긴 라이선스 충돌 35건만 "검토 필요"로 남는다.
2. **cdxgen 공식 이미지에 빌드 툴체인이 다 있는가**: 이번 대상(npm, Maven, Gradle, pip, poetry, Go, Cargo, .NET)에서는 그렇다. 모두 `TRANSITIVE_INCOMPLETE` 경고 0건으로 해석됐다. 이미지 하나(4.4GB)로 충분해서 언어별 이미지 변형은 필요 없다.
3. **scancode 소요 시간**: jq-1.7.1(파일 540개)에서 `--deep` 전체가 125~146초였다. 나머지 도구는 각 10초 안팎이라 대부분이 scancode 시간이다. 기본값을 끄고 옵션으로 둔 결정이 맞다.
4. **이미지 npm 결과가 207개 대 214개로 다른 이유**: 고유 PURL 수는 같다. syft는 중첩된 `node_modules`에 설치된 **복사본마다** 항목을 내고(4개 PURL에 복사본 7개 추가), cdxgen은 PURL 단위로 중복을 제거한다. 병합은 PURL 키로 1건이 되고, `locations`에 모든 설치 경로가 남는다.

## 4. 측정으로 바꾼 것

| 변경 | 측정 근거 | 위치 |
|---|---|---|
| "한 도구만 발견"을 `needsReview` 조건에서 뺐다. 이 수는 리포트와 UI 요약에 따로 표시한다. | lockfile이 없는 생태계에서는 구조적으로 cdxgen만 볼 수 있어서 검토 필요가 express 377/382, gradle 79/83, .NET 92/94였다. 조정 후 검토 필요는 실제 충돌(이미지 35)과 PURL 없음(ripgrep 워크스페이스 크레이트 10)만 남는다. | `Merger.kt`, `ReportWriter.kt`, `Summary.tsx` |
| direct 판정의 roots는, 그래프를 고른 도구가 빈 roots를 내면 다른 도구의 roots로 넘어가고, 아무 도구도 모르면 "아무도 의존하지 않는 노드"로 정한다. | cdxgen이 requirements.txt와 .NET에 대해 `dependsOn: []`을 내서 direct가 0이었다. 조정 후 pip 54, .NET 11이다. | `Merger.kt` |
| deb/rpm epoch를 키 버전에 합친다(`epoch=1` qualifier를 `1:` 접두사로). 소스와 바이너리 패키지 대응은 epoch를 뺀 버전으로도 맞춘다. | trivy와 syft/cdxgen의 epoch 표기가 달라 `bsdutils` 같은 패키지가 둘로 쪼개졌다. 조정 후 이미지 deb는 97개에서 86개가 됐고, syft 실측 85개와 맞는다. | `Normalizer.kt` |
| 이미지 tar와 레이어 압축 해제용 `/tmp`를 도구별로 작업 디렉터리 아래 호스트 디렉터리로 마운트한다. | non-root로 실행한 syft가 root 소유 tmpfs에 쓰지 못해 실패했다. | `Scanners.kt` |

`merge-rules.yml`의 도구 우선순위는 바꾸지 않았다. 실측이 초기 가설을 지지한다.
- 앱 라이선스와 그래프는 cdxgen이 가장 넓다.
- OS 라이선스는 trivy만 SPDX를 준다.
- OS 공급자는 syft만 준다.
- 다운로드 URL은 lockfile을 읽는 cdxgen과 syft가 같은 값을 낸다(lab에서 100% 일치).

## 5. 남은 한계와 다음 단계

| 한계 | 영향 | 제안 |
|---|---|---|
| 앱 생태계의 **공급자**를 어느 도구도 내지 않는다. | 모든 repo 스캔에서 `NTIA_MISSING: supplier` 경고가 난다. | 레지스트리 메타데이터로 보강한다(Maven POM `organization`/`developers`, npm `author`/`maintainers`, PyPI `author`). 추측값(예: groupId)을 공급자로 넣는 일은 제출 문서의 정확성을 해치므로 하지 않았다. |
| Maven/Gradle/poetry **해시**가 0%다. | 무결성 정보가 빠진다. | Maven Central의 `.sha1`/`.sha256`, PyPI JSON API의 `digests`로 보강한다. |
| **GitHub Actions**(`pkg:github/...`)가 컴포넌트로 들어온다(cdxgen과 syft가 `.github/workflows`를 읽음). | go와 C의 라이선스 및 다운로드 URL 미해결분 대부분이 여기서 나온다. 배포물에 포함되지 않는 CI 의존성이다. | 제출 정책에 따라 `scope=excluded`로 표시하거나 제외하는 옵션을 둔다. 정책 결정이 필요하다. |
| **vendored C/C++**는 scancode가 파일 단위 라이선스만 준다. | jq의 oniguruma 같은 내장 라이브러리가 컴포넌트로 나타나지 않는다. | 디렉터리 단위로 라이선스와 저작권 검출을 묶어 "vendored component"로 올리는 리더를 추가한다. |
| pypi 다운로드 URL은 sdist 경로 템플릿이다. | 실제 설치된 wheel과 다를 수 있다. | PyPI JSON API의 `urls`로 실제 파일을 고른다. |
| deb 다운로드 URL은 `main` 컴포넌트와 기본 미러를 가정한다. | contrib/non-free나 보안 업데이트 패키지는 404가 날 수 있다. | snapshot.debian.org 조회를 추가한다. |
