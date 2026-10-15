# ScanCode publishes no prebuilt image; build: docker build -t sca-scancode:32.5.0 -f docker/scancode.Dockerfile docker/
# pluggy<1.6: plugincode 32.x finds no hooks with pluggy 1.6. System libarchive/7z: extractcode ships native libs for x86_64 only.
FROM python:3.11-slim
RUN apt-get update && apt-get install -y --no-install-recommends bzip2 xz-utils zlib1g libxml2-dev libxslt1-dev libgomp1 libpopt0 \
        libarchive-dev p7zip-full \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s "$(ls /usr/lib/*/libarchive.so | head -1)" /usr/lib/libarchive.so
ENV EXTRACTCODE_LIBARCHIVE_PATH=/usr/lib/libarchive.so EXTRACTCODE_7Z_PATH=/usr/bin/7z
RUN pip install --no-cache-dir "pluggy<1.6" scancode-toolkit==32.5.0 && scancode --reindex-licenses
ENTRYPOINT ["scancode"]
