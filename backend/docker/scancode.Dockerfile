# ScanCode publishes no prebuilt image; build: docker build -t sca-scancode:32.5.0 -f docker/scancode.Dockerfile docker/
FROM python:3.11-slim
RUN apt-get update && apt-get install -y --no-install-recommends bzip2 xz-utils zlib1g libxml2-dev libxslt1-dev libgomp1 libpopt0 \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir "setuptools<81" scancode-toolkit==32.5.0 \
    && scancode --reindex-licenses
ENTRYPOINT ["scancode"]
