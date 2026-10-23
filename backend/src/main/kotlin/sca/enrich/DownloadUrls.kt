package sca.enrich

import com.github.packageurl.PackageURL
import java.net.URLDecoder

/** Where a package version can actually be downloaded from, computed from its purl alone. */
object DownloadUrls {
    fun forPurl(purl: String): String? {
        val p = runCatching { PackageURL(purl) }.getOrNull() ?: return null
        val q = p.qualifiers ?: emptyMap()
        q["download_url"]?.let { return it }
        val n = p.name
        val v = p.version ?: return null
        return when (p.type) {
            "npm" -> "https://registry.npmjs.org/${p.namespace?.let { "$it/" } ?: ""}$n/-/$n-$v.tgz"
            "maven" -> p.namespace?.let { g ->
                "https://repo.maven.apache.org/maven2/${g.replace('.', '/')}/$n/$v/$n-$v${q["classifier"]?.let { "-$it" } ?: ""}.${q["type"] ?: "jar"}"
            }
            "pypi" -> "https://pypi.org/packages/source/${n.first()}/$n/$n-$v.tar.gz"
            // packageurl-java lowercases golang paths, but the proxy needs the original case: read it from the raw purl
            "golang" -> "https://proxy.golang.org/${goEscape(URLDecoder.decode(purl.removePrefix("pkg:golang/").substringBefore('@'), Charsets.UTF_8))}/@v/$v.zip"
            "cargo" -> "https://crates.io/api/v1/crates/$n/$v/download"
            "nuget" -> n.lowercase().let { ln -> "https://api.nuget.org/v3-flatcontainer/$ln/${v.lowercase()}/$ln.${v.lowercase()}.nupkg" }
            "gem" -> "https://rubygems.org/downloads/$n-$v.gem"
            // ponytail: assumes the "main" component; contrib/non-free and security updates need a snapshot.debian.org lookup,
            // add when those show up unresolved. Releases up to 10 (buster) only live on the archive; bump when bullseye moves there.
            "deb" -> q["arch"]?.takeIf { p.namespace == "debian" }?.let { arch ->
                val src = q["upstream"]?.substringBefore(' ')?.substringBefore('@') ?: n
                val prefix = if (src.startsWith("lib")) src.take(4) else src.take(1)
                val major = q["distro"]?.removePrefix("debian-")?.substringBefore('.')?.toIntOrNull()
                val host = if (major != null && major <= 10) "archive.debian.org" else "deb.debian.org"
                "http://$host/debian/pool/main/$prefix/$src/${n}_${v.substringAfter(':')}_$arch.deb"
            }
            else -> null
        }
    }

    fun defaultRegistry(type: String): String? = when (type) {
        "npm" -> "https://registry.npmjs.org"
        "maven" -> "https://repo.maven.apache.org/maven2"
        "pypi" -> "https://pypi.org"
        "golang" -> "https://proxy.golang.org"
        "cargo" -> "https://crates.io"
        "nuget" -> "https://api.nuget.org/v3/index.json"
        "gem" -> "https://rubygems.org"
        "deb" -> "http://deb.debian.org/debian"
        else -> null
    }

    /** Go module proxy case-encoding: each uppercase letter becomes '!' + lowercase. */
    private fun goEscape(path: String) = path.replace(Regex("[A-Z]")) { "!" + it.value.lowercase() }
}
