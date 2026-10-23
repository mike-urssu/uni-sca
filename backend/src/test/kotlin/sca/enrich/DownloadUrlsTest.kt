package sca.enrich

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadUrlsTest {
    @Test fun npm() {
        assertEquals("https://registry.npmjs.org/lodash/-/lodash-4.17.15.tgz", DownloadUrls.forPurl("pkg:npm/lodash@4.17.15"))
        assertEquals("https://registry.npmjs.org/@types/node/-/node-20.1.0.tgz", DownloadUrls.forPurl("pkg:npm/%40types/node@20.1.0"))
    }

    @Test fun maven() {
        assertEquals("https://repo.maven.apache.org/maven2/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar",
            DownloadUrls.forPurl("pkg:maven/org.apache.commons/commons-lang3@3.12.0"))
        assertEquals("https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-epoll/4.1.0/netty-transport-native-epoll-4.1.0-linux-x86_64.jar",
            DownloadUrls.forPurl("pkg:maven/io.netty/netty-transport-native-epoll@4.1.0?classifier=linux-x86_64"))
        assertEquals("https://repo.maven.apache.org/maven2/org/x/bom/1.0/bom-1.0.pom", DownloadUrls.forPurl("pkg:maven/org.x/bom@1.0?type=pom"))
    }

    @Test fun pypi() = assertEquals("https://pypi.org/packages/source/r/requests/requests-2.31.0.tar.gz", DownloadUrls.forPurl("pkg:pypi/requests@2.31.0"))

    @Test fun golang() = assertEquals("https://proxy.golang.org/github.com/!burnt!sushi/toml/@v/v1.3.2.zip",
        DownloadUrls.forPurl("pkg:golang/github.com/BurntSushi/toml@v1.3.2"))

    @Test fun cargo() = assertEquals("https://crates.io/api/v1/crates/serde/1.0.190/download", DownloadUrls.forPurl("pkg:cargo/serde@1.0.190"))

    @Test fun nuget() = assertEquals("https://api.nuget.org/v3-flatcontainer/newtonsoft.json/13.0.3/newtonsoft.json.13.0.3.nupkg",
        DownloadUrls.forPurl("pkg:nuget/Newtonsoft.Json@13.0.3"))

    @Test fun gem() = assertEquals("https://rubygems.org/downloads/rails-7.1.0.gem", DownloadUrls.forPurl("pkg:gem/rails@7.1.0"))

    @Test fun deb() {
        assertEquals("http://deb.debian.org/debian/pool/main/a/acl/libacl1_2.2.53-4_arm64.deb",
            DownloadUrls.forPurl("pkg:deb/debian/libacl1@2.2.53-4?arch=arm64&upstream=acl"))
        assertEquals("http://deb.debian.org/debian/pool/main/a/attr/attr_2.4.48-4_arm64.deb", DownloadUrls.forPurl("pkg:deb/debian/attr@1%3A2.4.48-4?arch=arm64"))
        assertEquals("http://deb.debian.org/debian/pool/main/libx/libxml2/libxml2_2.9.4_amd64.deb",
            DownloadUrls.forPurl("pkg:deb/debian/libxml2@2.9.4?arch=amd64"))   // lib* sources use a 4-letter pool prefix
        assertEquals("http://archive.debian.org/debian/pool/main/a/adduser/adduser_3.118_all.deb",
            DownloadUrls.forPurl("pkg:deb/debian/adduser@3.118?arch=all&distro=debian-10.13"))   // buster left the main mirror
        assertNull(DownloadUrls.forPurl("pkg:deb/debian/attr@2.4.48-4"))   // no arch
    }

    @Test fun `explicit download_url qualifier wins`() =
        assertEquals("https://example.com/x.tgz", DownloadUrls.forPurl("pkg:npm/x@1?download_url=https%3A%2F%2Fexample.com%2Fx.tgz"))

    @Test fun unsupported() {
        assertNull(DownloadUrls.forPurl("pkg:rpm/redhat/bash@5.1"))
        assertNull(DownloadUrls.forPurl("not a purl"))
    }

    @Test fun registries() {
        assertEquals("https://registry.npmjs.org", DownloadUrls.defaultRegistry("npm"))
        assertNull(DownloadUrls.defaultRegistry("rpm"))
    }
}
