# ScanCode publishes no prebuilt image; build: docker build -t sca-scancode:32.5.0 -f docker/scancode.Dockerfile docker/
# pluggy<1.6: plugincode 32.x finds no hooks with pluggy 1.6. System libarchive/7z/libmagic: extractcode and typecode ship native libs for x86_64 only.
FROM python:3.11-slim
RUN apt-get update && apt-get install -y --no-install-recommends bzip2 xz-utils zlib1g libxml2-dev libxslt1-dev libgomp1 libpopt0 \
        libarchive-dev p7zip-full libmagic-dev libmagic-mgc \
    && rm -rf /var/lib/apt/lists/* \
    && ln -s "$(ls /usr/lib/*/libarchive.so | head -1)" /usr/lib/libarchive.so \
    && ln -s "$(ls /usr/lib/*/libmagic.so | head -1)" /usr/lib/libmagic.so
ENV EXTRACTCODE_LIBARCHIVE_PATH=/usr/lib/libarchive.so EXTRACTCODE_7Z_PATH=/usr/bin/7z \
    TYPECODE_LIBMAGIC_PATH=/usr/lib/libmagic.so TYPECODE_LIBMAGIC_DB_PATH=/usr/lib/file/magic.mgc
# Build the license index once at image build time (cache is world-writable so non-root runs can take its lock).
ENV SCANCODE_CACHE=/opt/scancode-cache SCANCODE_TEMP=/tmp/scancode
RUN pip install --no-cache-dir "pluggy<1.6" scancode-toolkit==32.5.0 \
    && scancode --license --json /tmp/warmup.json /etc/hostname && rm -rf /tmp/warmup.json /tmp/scancode \
    && chmod -R a+rwX /opt/scancode-cache
ENTRYPOINT ["scancode"]
