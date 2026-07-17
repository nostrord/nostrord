package org.nostr.nostrord.auth.pomegranate

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.nostr.nostrord.network.createHttpClient
import org.nostr.nostrord.nostr.Crypto
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.hexToByteArray
import org.nostr.nostrord.nostr.toHexString
import org.nostr.nostrord.utils.epochMillis
import org.nostr.nostrord.utils.epochSeconds
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The pomegranate registration/recovery protocol against the central server and the
 * shard operators. HTTP + event signing are shared; only the popups and the dealer are
 * platform-gated (see [PomegranatePopups] / [PomegranateDealer]). After login the
 * returned `bunker://` URL rides the app's normal NIP-46 path — nothing downstream
 * knows the signer is threshold-based.
 *
 * Popup-opening methods must be invoked from a user gesture (click) or the browser
 * blocks the window; keep any await before them short.
 */
class PomegranateService {
    private val http by lazy { createHttpClient() }
    private val json = Json { ignoreUnknownKeys = true }

    /** Gates every UI entry point: web with the feature flag on. */
    val isAvailable: Boolean get() = PomegranateConfig.ENABLED && PomegranatePopups.isAvailable

    data class StartedLogin(
        val central: String,
        val token: GoogleToken,
        val hasAccount: Boolean,
    )

    data class LoginOutcome(
        val bunkerUrl: String,
        val central: String,
    )

    data class Recovery(
        val token: GoogleToken,
        val account: PomegranateAccount,
    )

    /**
     * First half of the login: Google popup + account existence check. When an account
     * exists its operators/threshold are fixed server-side; otherwise [finishLogin]
     * creates one with the default config.
     */
    suspend fun startLogin(
        centralUrl: String = PomegranateConfig.CENTRAL_URL,
        onStatus: (PomegranateStatus) -> Unit,
    ): StartedLogin {
        val central = normalizePomegranateOrigin(centralUrl)
        onStatus(PomegranateStatus.WaitingForGoogle)
        val token = authenticateWithGoogle(central)
        onStatus(PomegranateStatus.Checking)
        return StartedLogin(central, token, getAccount(central, token) != null)
    }

    /**
     * Second half: creates the account when needed (key sharded across the default
     * operators), ensures a signing profile, and returns the bunker URL to log in with
     * plus the central origin to persist on the account. Opens no popup.
     */
    suspend fun finishLogin(
        started: StartedLogin,
        onStatus: (PomegranateStatus) -> Unit,
    ): LoginOutcome {
        if (!started.hasAccount) {
            onStatus(PomegranateStatus.Creating)
            createAccount(started.central, started.token)
        }
        var profiles = listProfiles(started.central, started.token)
        if (profiles.isEmpty()) {
            profiles = listOf(createProfile(started.central, started.token, "default"))
        }
        return LoginOutcome(bunkerUrl(started.central, profiles.first()), started.central)
    }

    /**
     * Export-nsec entry: authenticates with Google and returns the account (operators +
     * threshold) after verifying it matches the locally active pubkey, so signing in
     * with the wrong Google account fails up front instead of recovering another key.
     */
    suspend fun startRecovery(
        centralUrl: String,
        expectedPubkey: String,
    ): Recovery {
        val central = normalizePomegranateOrigin(centralUrl)
        val token = authenticateWithGoogle(central)
        val account =
            getAccount(central, token)
                ?: throw Exception("No pomegranate account found for this Google login")
        if (account.pubkey != expectedPubkey) throw PomegranatePubkeyMismatchException()
        return Recovery(token, account)
    }

    /** Recovers one shard via the operator's Google recovery popup. User gesture required. */
    suspend fun recoverShard(operator: PomegranateOperator): String {
        val origin = normalizePomegranateOrigin(operator.url)
        val shard = PomegranatePopups.awaitShardFromPopup("$origin/po/recover/google", origin)
        if (!shard.startsWith(operator.pubshard)) {
            throw Exception("Recovered shard does not match the operator")
        }
        return shard
    }

    /** Aggregates threshold-many shards back into the key hex, verified against the account pubkey. */
    fun aggregateKeyHex(
        shardHexes: List<String>,
        expectedPubkey: String,
    ): String {
        val secretHex = PomegranateDealer.aggregate(shardHexes)
        val derived = Crypto.getPublicKeyXOnly(secretHex.hexToByteArray()).toHexString()
        if (derived != expectedPubkey) throw Exception("Recovered key does not match the account")
        return secretHex
    }

    /**
     * Unlinks the account from the central signer (DELETE /account). The key still
     * exists; the account stays usable via an exported nsec. Verifies the Google
     * account maps to [expectedPubkey] before deleting.
     */
    suspend fun disconnectAccount(
        centralUrl: String,
        expectedPubkey: String,
    ) {
        val central = normalizePomegranateOrigin(centralUrl)
        val token = authenticateWithGoogle(central)
        val account = getAccount(central, token)
        if (account == null || account.pubkey != expectedPubkey) {
            throw PomegranatePubkeyMismatchException()
        }
        val res = http.delete("$central/account") { header("Authorization", "Token ${token.raw}") }
        if (!res.status.isSuccess()) throw Exception("Account deletion failed")
    }

    // --- internal -------------------------------------------------------------

    private suspend fun authenticateWithGoogle(central: String): GoogleToken {
        val raw = PomegranatePopups.awaitTokenFromPopup("$central/login/google", central)
        return decodeGoogleToken(raw)
    }

    /** GET /account — the account, or null when this Google login has none yet. */
    private suspend fun getAccount(
        central: String,
        token: GoogleToken,
    ): PomegranateAccount? {
        val res = http.get("$central/account") { header("Authorization", "Token ${token.raw}") }
        if (res.status == HttpStatusCode.Unauthorized) {
            throw Exception("Google session expired, please sign in again")
        }
        if (!res.status.isSuccess()) return null
        return try {
            json.decodeFromString<PomegranateAccount>(res.bodyAsText()).takeIf { it.pubkey.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Creates a new account: generates a key, shards it via the trusted dealer, and
     * registers with the central server (kind 20445) and every operator (kind 20444).
     * The key only signs these registration events and is then dropped — it never
     * exists whole anywhere afterwards.
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun createAccount(
        central: String,
        token: GoogleToken,
    ) {
        val operators = PomegranateConfig.OPERATOR_URLS.map { normalizePomegranateOrigin(it) }
        check(operators.size >= 2) { "At least 2 operators are required" }
        val threshold = PomegranateConfig.defaultThreshold(operators.size)
        check(threshold in 1..operators.size) { "Invalid signing threshold" }
        val session = Uuid.random().toString()

        val keyPair = KeyPair.generate()
        val shards = PomegranateDealer.deal(keyPair.privateKeyHex, threshold, operators.size)

        val regEvent =
            Event(
                pubkey = keyPair.publicKeyHex,
                createdAt = epochSeconds(),
                kind = KIND_ACCOUNT_REGISTRATION,
                tags =
                buildList {
                    add(listOf("threshold", threshold.toString()))
                    operators.forEachIndexed { i, op -> add(listOf("operator", op, shards[i].pubShardHex)) }
                },
                content = "",
            ).sign(keyPair)
        val regRes =
            http.post("$central/register") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Token ${token.raw}")
                header("X-Pomegranate-Session", session)
                setBody(regEvent.toJsonString())
            }
        if (regRes.status.value != 200) throw Exception("Central server registration failed")

        // Operators in parallel; a few may fail — the account works as long as at
        // least `threshold` of them hold their shard.
        val registered =
            coroutineScope {
                operators
                    .mapIndexed { i, operator ->
                        async {
                            val event =
                                Event(
                                    pubkey = keyPair.publicKeyHex,
                                    createdAt = epochSeconds(),
                                    kind = KIND_OPERATOR_REGISTRATION,
                                    tags = listOf(listOf("central", central), listOf("email", token.email)),
                                    content = shards[i].shardHex,
                                ).sign(keyPair)
                            try {
                                http
                                    .post("$operator/po/register") {
                                        contentType(ContentType.Application.Json)
                                        header("X-Pomegranate-Operator-Token", operatorToken(session, operator))
                                        setBody(event.toJsonString())
                                    }.status.isSuccess()
                            } catch (c: CancellationException) {
                                throw c
                            } catch (_: Throwable) {
                                false
                            }
                        }
                    }.awaitAll()
                    .count { it }
            }
        if (registered < threshold) {
            throw Exception("Could not register with enough operators ($registered/$threshold). Please try again.")
        }
    }

    /** GET /profiles — the NIP-46 signing profiles owned by the account. */
    private suspend fun listProfiles(
        central: String,
        token: GoogleToken,
    ): List<PomegranateProfile> {
        val res = http.get("$central/profiles") { header("Authorization", "Token ${token.raw}") }
        if (!res.status.isSuccess()) throw Exception("Failed to load signing profiles")
        return try {
            json.decodeFromString<List<PomegranateProfile>>(res.bodyAsText())
        } catch (_: Exception) {
            throw Exception("Failed to load signing profiles")
        }
    }

    /** POST /profiles — creates a signing profile and returns it. */
    private suspend fun createProfile(
        central: String,
        token: GoogleToken,
        name: String,
    ): PomegranateProfile {
        val res =
            http.post("$central/profiles") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Token ${token.raw}")
                setBody("""{"name":"$name"}""")
            }
        if (!res.status.isSuccess()) throw Exception("Signing profile creation failed")
        val profile =
            try {
                json.decodeFromString<PomegranateProfile>(res.bodyAsText())
            } catch (_: Exception) {
                null
            }
        if (profile == null || profile.handlerPubkey.length != 64) {
            throw Exception("Signing profile creation did not complete")
        }
        return profile
    }

    private fun bunkerUrl(
        central: String,
        profile: PomegranateProfile,
    ): String {
        val relay = central.replaceFirst("http", "ws")
        return "bunker://${profile.handlerPubkey}?relay=${relay.encodeURLParameter()}"
    }

    private fun operatorToken(
        session: String,
        operatorUrl: String,
    ): String = Crypto.sha256("$session:$operatorUrl").toHexString()

    /** Decodes the base64 token the central popup posts; rejects expired/garbled ones. */
    @OptIn(ExperimentalEncodingApi::class)
    internal fun decodeGoogleToken(raw: String): GoogleToken {
        val parsed =
            try {
                val decoded = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(raw).decodeToString()
                json.parseToJsonElement(decoded).jsonObject
            } catch (_: Exception) {
                throw Exception("Invalid Google sign-in token")
            }
        val createdAtMillis =
            (parsed["created_at"] as? JsonPrimitive)?.longOrNull?.times(1000)
                ?: throw Exception("Invalid Google sign-in token")
        if (epochMillis() - createdAtMillis > TOKEN_MAX_AGE_MS) {
            throw Exception("Google sign-in token expired, please try again")
        }
        val email =
            (parsed["tags"] as? JsonArray)?.firstNotNullOfOrNull { tag ->
                val arr = tag as? JsonArray ?: return@firstNotNullOfOrNull null
                if (arr.size > 1 && (arr[0] as? JsonPrimitive)?.content == "email") {
                    (arr[1] as? JsonPrimitive)?.content
                } else {
                    null
                }
            } ?: ""
        return GoogleToken(raw, email, createdAtMillis)
    }

    private companion object {
        const val KIND_ACCOUNT_REGISTRATION = 20445
        const val KIND_OPERATOR_REGISTRATION = 20444
        const val TOKEN_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}

/** Normalizes a central/operator URL to its origin (scheme://host[:port], no path). */
internal fun normalizePomegranateOrigin(input: String): String {
    var url = input.trim().trimEnd('/')
    if (!url.startsWith("http")) {
        url = "http" + (if (url.startsWith("localhost")) "" else "s") + "://" + url
    }
    val u = Url(url)
    return buildString {
        append(u.protocol.name).append("://").append(u.host)
        if (u.specifiedPort != 0 && u.specifiedPort != u.protocol.defaultPort) {
            append(':').append(u.specifiedPort)
        }
    }
}
