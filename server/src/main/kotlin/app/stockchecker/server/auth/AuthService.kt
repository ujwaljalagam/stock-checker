package app.stockchecker.server.auth

import app.stockchecker.domain.Validation
import app.stockchecker.model.User
import app.stockchecker.server.db.Repository
import app.stockchecker.server.mail.Mailer
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Crypto {
    private val random = SecureRandom()

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    fun randomToken(bytes: Int = 32): String {
        val buf = ByteArray(bytes).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    fun sixDigitCode(): String = (random.nextInt(900_000) + 100_000).toString()

    fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

sealed interface AuthResult {
    data class Success(val token: String, val user: User) : AuthResult
    data class Failure(val status: Int, val message: String) : AuthResult
}

/** Passwordless email-code sign-in with opaque bearer session tokens. */
class AuthService(
    private val repo: Repository,
    private val mailer: Mailer,
    private val devMode: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)

    companion object {
        const val CODE_TTL_MS = 10 * 60 * 1000L
        const val RESEND_COOLDOWN_MS = 30 * 1000L
        const val MAX_ATTEMPTS = 5
        const val SESSION_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }

    /** @return the code in dev mode (for convenience), null otherwise. Throws [IllegalStateException] on cooldown. */
    suspend fun requestCode(rawEmail: String): String? {
        val email = Validation.normalizeEmail(rawEmail)
        require(Validation.isValidEmail(email)) { "Enter a valid email address." }
        val existing = repo.loginCode(email)
        val t = now()
        check(existing == null || t - existing.createdAt >= RESEND_COOLDOWN_MS) { "Please wait a few seconds before requesting another code." }
        val code = Crypto.sixDigitCode()
        repo.saveLoginCode(email, hashCode(email, code), t + CODE_TTL_MS, t)
        if (devMode) log.info("DEV login code for {}: {}", email, code)
        mailer.send(
            to = email,
            subject = "Your Stock Checker sign-in code: $code",
            text = "Your sign-in code is $code\n\nIt expires in 10 minutes. If you didn't ask for it, ignore this email.",
        )
        return if (devMode) code else null
    }

    suspend fun verify(rawEmail: String, rawCode: String): AuthResult {
        val email = Validation.normalizeEmail(rawEmail)
        val code = rawCode.trim()
        val stored = repo.loginCode(email) ?: return AuthResult.Failure(400, "Request a new code.")
        if (stored.expiresAt < now()) {
            repo.deleteLoginCode(email)
            return AuthResult.Failure(400, "That code has expired. Request a new one.")
        }
        if (stored.attempts >= MAX_ATTEMPTS) {
            repo.deleteLoginCode(email)
            return AuthResult.Failure(429, "Too many attempts. Request a new code.")
        }
        if (!Crypto.constantTimeEquals(stored.codeHash, hashCode(email, code))) {
            repo.incrementCodeAttempts(email)
            return AuthResult.Failure(400, "That code is not right.")
        }
        repo.deleteLoginCode(email)
        val t = now()
        val user = repo.findOrCreateUser(email, t)
        val token = Crypto.randomToken()
        repo.createSession(Crypto.sha256Hex(token), user.id, t, t + SESSION_TTL_MS)
        return AuthResult.Success(token, user)
    }

    suspend fun userForToken(token: String): User? = repo.userForSession(Crypto.sha256Hex(token), now())

    suspend fun logout(token: String) = repo.deleteSession(Crypto.sha256Hex(token))

    private fun hashCode(email: String, code: String) = Crypto.sha256Hex("$email:$code")
}
