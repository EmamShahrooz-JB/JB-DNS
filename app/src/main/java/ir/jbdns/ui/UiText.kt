package ir.jbdns.ui

/** ابزارهای کوچک نمایشی. */
object UiText {

    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    /** ارقام لاتین را به فارسی تبدیل می‌کند؛ آی‌پی‌ها و نشانی‌ها دست‌نخورده می‌مانند. */
    fun fa(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in input) {
            if (c in '0'..'9') sb.append(persianDigits[c - '0']) else sb.append(c)
        }
        return sb.toString()
    }

    fun shortAddress(address: String, max: Int = 44): String =
        if (address.length <= max) address else address.take(max - 1) + "…"
}
