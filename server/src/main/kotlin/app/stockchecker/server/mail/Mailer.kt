package app.stockchecker.server.mail

import app.stockchecker.server.AppConfig
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.Properties

interface Mailer {
    val isReal: Boolean
    suspend fun send(to: String, subject: String, text: String)
}

/** Dev mailer: prints emails to the server log. */
class ConsoleMailer : Mailer {
    private val log = LoggerFactory.getLogger("Mailer")
    val sent = mutableListOf<Triple<String, String, String>>()
    override val isReal = false

    override suspend fun send(to: String, subject: String, text: String) {
        sent += Triple(to, subject, text)
        log.info("EMAIL to={} subject=\"{}\"\n{}", to, subject, text)
    }
}

class SmtpMailer(private val config: AppConfig.SmtpConfig) : Mailer {
    override val isReal = true

    private val session: Session by lazy {
        val props = Properties().apply {
            put("mail.smtp.host", config.host)
            put("mail.smtp.port", config.port.toString())
            put("mail.smtp.auth", (config.username != null).toString())
            put("mail.smtp.starttls.enable", config.startTls.toString())
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
        }
        val authenticator = config.username?.let { user ->
            object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(user, config.password ?: "")
            }
        }
        Session.getInstance(props, authenticator)
    }

    override suspend fun send(to: String, subject: String, text: String) = withContext(Dispatchers.IO) {
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.from))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject, "UTF-8")
            setText(text, "UTF-8")
        }
        Transport.send(message)
    }
}
