package com.jjmluz.quemdiria

/**
 * The home Wi-Fi the TV is on, to show in the room so guests can join it. Android doesn't let apps read a saved
 * Wi-Fi password, so it comes from a file kept out of git (`private/wifi.json`), bundled into local builds.
 */
data class Wifi(val name: String, val password: String) {
    /**
     * The text of a Wi-Fi QR code, which phone cameras offer to join: `WIFI:T:WPA;S:<name>;P:<password>;;`, with
     * `\`, `;`, `,`, `:` and `"` escaped by a backslash. With no password, an open network.
     */
    val qrText: String
        get() = if (password.isEmpty()) "WIFI:T:nopass;S:${escape(name)};;" else "WIFI:T:WPA;S:${escape(name)};P:${escape(password)};;"

    private fun escape(s: String) = s.replace(Regex("""([\\;,:"])"""), """\\$1""")
}
