#!/usr/bin/env bash
# SCA tool hands-on demo. Prints each command, runs it, and shows the real output.
#   ./demo.sh            pause at each step (Enter to continue)
#   PAUSE=0 ./demo.sh    run everything without stopping
#   RUN_DC=1 ./demo.sh   also run OWASP Dependency-Check (first NVD download takes a long time)
set -uo pipefail
cd "$(dirname "$0")"
IMG=${IMG:-node:16-slim}
mkdir -p out

title() { printf '\n\033[1;35m━━━ %s ━━━\033[0m\n' "$*"; }
note()  { printf '\033[2m# %s\033[0m\n' "$*"; }
run()   { printf '\n\033[1;32m$ %s\033[0m\n' "$*"; [ "${PAUSE:-1}" = 0 ] || read -rp '⏎ '; time eval "$@"; }

title "0. Prep"
note "Install whatever is missing. The sample deps are pinned to old, vulnerable versions (scripts are not run)"
command -v syft >/dev/null || brew install syft grype trivy osv-scanner jq dependency-check
command -v cdxgen >/dev/null || npm i -g @cyclonedx/cdxgen retire
run "npm install --ignore-scripts --no-audit --silent"
run "docker pull -q $IMG"
note "Download vulnerability DBs ahead of time (otherwise the first scan includes DB download time, and grype can come back empty)"
run "grype db update -q; trivy image -q --download-db-only"

title "A. SBOM generation — how many packages does each tool find?"
note "Syft: Anchore. Broad ecosystem coverage, many output formats (syft-json/CycloneDX/SPDX). Has no vulnerability DB of its own"
run "syft dir:. -q -o table -o syft-json=out/syft-dir.json -o spdx-json=out/syft-dir.spdx.json | head -20"
run "syft $IMG -q -o syft-json=out/syft-img.json -o spdx-json=out/syft-img.spdx.json -o table | tail -15"
note "Trivy: Aqua. Also emits SBOMs (CycloneDX/SPDX). One binary does SBOM + vulns + secrets + IaC + licenses"
run "trivy fs -q --format cyclonedx -o out/trivy-dir.cdx.json . && jq '.components|length' out/trivy-dir.cdx.json"
note "cdxgen: OWASP CycloneDX. Includes the dependency graph (dependencies) and evidence fields, with deep per-language analysis"
run "cdxgen -t js -o out/cdxgen.json . >/dev/null && jq '{components: (.components|length), dependencyEdges: (.dependencies|length)}' out/cdxgen.json"

title "B. Vulnerability scanning — same target, different DBs and matching"
note "npm audit: built-in baseline. Only covers npm packages, GitHub Advisory DB"
run "npm audit --json > out/npm-audit.json; npm audit | tail -8"
note "Grype: Syft's partner. Can rescan an already-built SBOM later (SBOM → vulnerabilities pipeline)"
run "grype sbom:out/syft-dir.json -q -o table -o json=out/grype-dir.json"
run "grype $IMG -q -o json=out/grype-img.json -o table | tail -15"
note "Trivy: its own trivy-db (NVD + GHSA + distro advisories), with per-severity counts"
run "trivy fs -q --scanners vuln -f json -o out/trivy-dir.json . && trivy convert -q -f table out/trivy-dir.json"
run "trivy image -q --scanners vuln -f json -o out/trivy-img.json $IMG && trivy convert -q -f table out/trivy-img.json | head -25"
note "OSV-Scanner: Google. Lockfile-based, OSV.dev DB (aggregates GHSA, PyPA, Go, Debian, and more)"
run "osv-scanner scan source -L package-lock.json --format json > out/osv-dir.json; osv-scanner scan source -L package-lock.json | tail -25"
note "By default OSV hides Debian findings marked unimportant. --all-vulns shows them all (the summary uses that value)"
run "osv-scanner scan image --format json $IMG > out/osv-img-default.json 2>/dev/null; osv-scanner scan image --all-vulns --format json $IMG > out/osv-img.json 2>/dev/null; jq -s 'map([.results[].packages[].vulnerabilities[]?]|length) | {default: .[0], all_vulns: .[1]}' out/osv-img-default.json out/osv-img.json"
note "retire.js: JS-only. Fingerprints the .js files themselves (finds vendored, copied jquery and similar)"
run "retire --path . --outputformat json --outputpath out/retire.json; retire --path . 2>&1 | tail -12"

title "C. Extra features — differences beyond vulnerabilities"
note "Trivy secret/license scanning. Planted a fake GitHub token (well-known samples like AKIA...EXAMPLE are allowlisted and not reported)"
printf 'GITHUB_TOKEN=ghp_%s\n' "Zx9Qw3Er5Ty7Ui1Op2As4Df6Gh8Jk0LmN3bV" > fake-secret.env
run "trivy fs -q --scanners secret ."
run "trivy fs -q --scanners license . | head -20"
rm -f fake-secret.env
note "License fields: how Syft (SPDX) and cdxgen (CycloneDX) record the same package"
run "jq -c '.packages[] | select(.name==\"lodash\") | {name, versionInfo, licenseConcluded, licenseDeclared}' out/syft-dir.spdx.json; jq -c '.components[] | select(.name==\"lodash\") | {name, version, licenses}' out/cdxgen.json"

if [ "${RUN_DC:-0}" = 1 ]; then
  title "D. OWASP Dependency-Check — NVD/CPE based, strong on Java"
  note "Set NVD_API_KEY for a much faster NVD download"
  run "dependency-check --scan . --format JSON --out out/ --disableAssembly ${NVD_API_KEY:+--nvdApiKey $NVD_API_KEY} | tail -5"
fi

title "E. Summary at a glance"
./summary.sh
