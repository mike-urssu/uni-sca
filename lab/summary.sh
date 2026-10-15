#!/usr/bin/env bash
# Comparison summary built from the JSON in out/. Tools that haven't run show "-".
set -uo pipefail
cd "$(dirname "$0")"
note() { printf '\033[2m# %s\033[0m\n' "$*"; }
j() { [ -s "$2" ] && jq "$1" "$2" || echo -; }   # jq on a file, "-" if it doesn't exist
# Normalize each finding to its CVE when one exists, otherwise keep the original ID (GHSA, etc.)
ids() {
  case $1 in
    grype)  jq -r '.matches[] | ([.vulnerability.id] + [.relatedVulnerabilities[]?.id]) | (map(select(startswith("CVE")))[0] // .[0])' "$2" ;;
    trivy)  jq -r '.Results[]?.Vulnerabilities[]?.VulnerabilityID' "$2" ;;
    # OSV groups several CVEs under one Debian DLA/DSA advisory (upstream), so expand them all
    osv)    jq -r '.results[]?.packages[]?.vulnerabilities[]? | ([.id] + (.aliases // []) + (.upstream // [])) | (map(select(startswith("CVE"))) | if length > 0 then .[] else empty end) // .[0]' "$2" ;;
    retire) jq -r '.data[]?.results[]?.vulnerabilities[]? | (.identifiers.CVE[0] // .identifiers.githubID // .identifiers.summary)' "$2" ;;
  esac | sort -u
}
mkdir -p out
for f in grype-dir grype-img trivy-dir trivy-img osv-dir osv-img retire; do
  [ -s out/$f.json ] && ids ${f%%-*} out/$f.json > out/ids-$f.txt || rm -f out/ids-$f.txt
done
n() { [ -f out/ids-$1.txt ] && wc -l < out/ids-$1.txt | tr -d ' ' || echo -; }

printf '\n\033[1m[Packages found]\033[0m\n'
printf '%-14s %8s %8s\n' TOOL DIR IMAGE
printf '%-14s %8s %8s\n' syft    "$(j '.artifacts|length' out/syft-dir.json)" "$(j '.artifacts|length' out/syft-img.json)"
printf '%-14s %8s %8s\n' trivy   "$(j '.components|length' out/trivy-dir.cdx.json)" -
printf '%-14s %8s %8s\n' cdxgen  "$(j '.components|length' out/cdxgen.json)" "$(j '.components|length' out/cdxgen-img.json)"

printf '\n\033[1m[Unique vulnerability IDs]  (*-only = found by this tool only, not by the other two)\033[0m\n'
printf '%-14s %8s %8s %10s %10s\n' TOOL DIR IMAGE "DIR-only" "IMG-only"
only() {   # number of IDs in $1 that none of the other existing files contain
  [ -f out/ids-$1.txt ] || { echo -; return; }
  cat /dev/null $(for x in grype trivy osv; do [ $x != ${1%%-*} ] && ls out/ids-$x-${1#*-}.txt 2>/dev/null; done) | sort -u | comm -23 out/ids-$1.txt - | wc -l | tr -d ' '
}
for t in grype trivy osv; do
  printf '%-14s %8s %8s %10s %10s\n' $t "$(n $t-dir)" "$(n $t-img)" "$(only $t-dir)" "$(only $t-img)"
done
printf '%-14s %8s %8s\n' retire.js "$(n retire)" -
printf '%-14s %8s %8s\n' "npm audit" "$(j '.metadata.vulnerabilities.total' out/npm-audit.json)" "-  (package-level count)"
note "Image differences mostly come from how each tool handles Debian unfixed/negligible findings and which advisory source it uses (DLA vs CVE tracker)"
note "Inspect the IDs: comm -3 out/ids-grype-img.txt out/ids-trivy-img.txt"

printf '\n\033[1m[Feature matrix]\033[0m\n'
cat <<'EOF'
TOOL          SBOM   VULNS  SECRETS LICENSE IaC  IMAGE  VULN DB                     NOTES
syft          ●      -      -       ●       -    ●      -                           Formats: syft/CycloneDX/SPDX. Pairs with Grype
grype         -      ●      -       -       -    ●      Anchore DB (NVD+GHSA+distro) Takes SBOM input, VEX, EPSS/KEV
trivy         ●      ●      ●       ●       ●    ●      trivy-db (NVD+GHSA+distro)  All-in-one, k8s/repo scans
osv-scanner   -      ●      -       ●       -    ●      OSV.dev                     Lockfile-focused, call analysis (Go), guided fixes
cdxgen        ●      -      -       ●       -    ●      -                           Dependency graph/evidence, many languages
retire.js     -      ●      -       -       -    -      retire.js repo              JS file fingerprints (vendored libs)
npm audit     -      ●      -       -       -    -      GHSA                        Built-in, npm only, audit fix
dep-check     -      ●      -       -       -    -      NVD (CPE matching)          Strong on Java/.NET, many false positives
Dependency-Track: not a CLI. A server that ingests SBOMs and keeps monitoring them (an operations platform for the tools above).
EOF
