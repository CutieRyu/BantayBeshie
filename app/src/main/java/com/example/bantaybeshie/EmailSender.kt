package com.example.bantaybeshie

import android.util.Log
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

object EmailSender {
    private const val TAG = "EmailSender"

    /**
     * Sends an email synchronously. Call from Dispatchers.IO.
     *
     * @param smtpUser the full gmail address (e.g. your@gmail.com)
     * @param smtpAppPassword the 16-char app password from Google
     * @param to array of recipient emails
     * @param subject email subject
     * @param body email body (plain text)
     */
    fun sendEmail(
        smtpUser: String,
        smtpAppPassword: String,
        to: Array<String>,
        subject: String,
        body: String
    ): Boolean {
        try {
            val props = Properties().apply {
                put("mail.smtp.host", "smtp.gmail.com")
                put("mail.smtp.socketFactory.port", "465")
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                put("mail.smtp.auth", "true")
                put("mail.smtp.port", "465")
            }

            val session = Session.getDefaultInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(smtpUser, smtpAppPassword)
                }
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(smtpUser))
                setRecipients(Message.RecipientType.TO, to.joinToString(",") { it })
                setSubject(subject)
                setText(body)
            }

            Transport.send(message)
            Log.d(TAG, "Email sent to ${to.joinToString(",")}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Email send failed: ${e.message}", e)
            return false
        }
    }
}
