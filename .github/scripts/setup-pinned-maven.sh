#!/usr/bin/env bash
# Central 0.9.0 staging and its offline probes must use one Maven distribution for the entire job.
set -euo pipefail
MAVEN_VERSION=3.9.16
MAVEN_SHA512=831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6
DOWNLOAD_DIR="$(mktemp -d "$RUNNER_TEMP/maven-download.XXXXXX")"
ARCHIVE="$DOWNLOAD_DIR/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
# Maven Central first; archive.apache.org throttles and resets connections from GitHub runners. Integrity does not
# depend on the source: the SHA-512 check below is unchanged.
DOWNLOADED=false
for URL in \
  "https://repo1.maven.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
  "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"; do
  if curl --fail --location --silent --show-error --retry 3 --retry-all-errors --connect-timeout 30 --max-time 300 \
    "$URL" --output "$ARCHIVE"; then
    DOWNLOADED=true
    break
  fi
  echo "Maven download from $URL failed; trying the next source" >&2
done
if [ "$DOWNLOADED" != true ]; then
  echo "Could not download Maven ${MAVEN_VERSION} from any source" >&2
  exit 1
fi
printf '%s  %s\n' "$MAVEN_SHA512" "$ARCHIVE" | sha512sum --check --strict
tar -xzf "$ARCHIVE" -C "$DOWNLOAD_DIR"
printf '%s\n' "$DOWNLOAD_DIR/apache-maven-${MAVEN_VERSION}/bin" >> "$GITHUB_PATH"
