package com.manette.app.connect

import com.manette.app.protocol.PairingCode
import com.manette.app.protocol.Protocol
import com.manette.app.protocol.RejectReason
import com.manette.app.transport.udp.ManualIpProvider

/** What the user typed on the connect screen, checked. Pure Kotlin so it is unit tested. */
sealed interface ConnectInput {
    class Ready(val host: String, val port: Int, val secret: ByteArray) : ConnectInput
    class Invalid(val message: String) : ConnectInput
}

object ConnectInputParser {
    /**
     * [codeText] is either the typed code from the PC ("ABCD-EFGH-...") or the whole manette:// link.
     * [hostText] may be blank when a link was pasted (its first address is used); if both are given, the typed host wins.
     */
    fun parse(hostText: String, codeText: String): ConnectInput {
        val code = codeText.trim()
        val link = PairingCode.parseLink(code)
        val secret = link?.secret ?: PairingCode.parseTypedCode(code)
            ?: return ConnectInput.Invalid("That pairing code is not valid. Copy it exactly as the PC shows it.")

        if (hostText.isNotBlank()) {
            val ep = ManualIpProvider.parse(hostText, link?.port ?: Protocol.DEFAULT_PORT)
                ?: return ConnectInput.Invalid("Enter the PC's address like 192.168.1.20 (optionally :port).")
            return ConnectInput.Ready(ep.host, ep.port, secret)
        }
        if (link != null) return ConnectInput.Ready(link.hosts.first(), link.port, secret)
        return ConnectInput.Invalid("Enter the PC's IP address (run ipconfig on the PC).")
    }
}

object RejectMessages {
    fun describe(reason: RejectReason): String = when (reason) {
        RejectReason.NOT_PAIRED -> "The PC does not know this phone. Pair again with a new code."
        RejectReason.PAIRING_NOT_OPEN -> "The pairing code expired or was already used. Press p on the PC for a new one."
        RejectReason.SERVER_FULL -> "The PC has no free controller slot (Windows allows 4)."
        RejectReason.UNSUPPORTED_VERSION -> "The PC app and this app are different versions."
        RejectReason.RATE_LIMITED -> "Too many attempts. Wait a few seconds and try again."
    }
}
