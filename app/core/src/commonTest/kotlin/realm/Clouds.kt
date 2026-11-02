package realm

val ACCOUNT = Account("a1", "Jo's account")

/** The worker as the release CI builds it; sha256 of "abc", as FIPS 180-2 gives it. */
val MANIFEST = Manifest(
    version = 3,
    name = "realm",
    mainModule = "worker.js",
    sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    compatibilityDate = "2026-10-01",
    buckets = listOf(BucketBinding("BUCKET", "realm")),
    databases = listOf(DatabaseBinding("DB", "realm")),
    crons = listOf("17 4 * * *"),
)
val RELEASE = Release(Semver(1, 0, 0), MANIFEST, "abc".encodeToByteArray(), null)

fun found(
    script: Existing? = null,
    buckets: Set<String> = emptySet(),
    databases: Set<String> = emptySet(),
    subdomain: String? = "jo",
    workersDevOn: Boolean = false,
    domains: List<Domain> = emptyList(),
) = Found(ACCOUNT, script, buckets, databases, true, subdomain, workersDevOn, emptyList(), domains)
