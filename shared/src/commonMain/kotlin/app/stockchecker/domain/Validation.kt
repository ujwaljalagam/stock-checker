package app.stockchecker.domain

import app.stockchecker.model.CreateWatchRequest

object Validation {
    private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val zipRegex = Regex("^\\d{5}$")
    private val skuRegex = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val codeRegex = Regex("^\\d{6}$")

    const val MIN_RADIUS = 1
    const val MAX_RADIUS = 250

    fun normalizeEmail(email: String): String = email.trim().lowercase()

    fun isValidEmail(email: String): Boolean = email.length <= 254 && emailRegex.matches(email.trim())

    fun isValidCode(code: String): Boolean = codeRegex.matches(code.trim())

    /** Returns a list of human-readable problems; empty means valid. */
    fun validateWatch(request: CreateWatchRequest, knownRetailers: Set<String>? = null): List<String> = buildList {
        if (knownRetailers != null && request.retailer !in knownRetailers) add("Unknown retailer '${request.retailer}'.")
        if (!skuRegex.matches(request.sku.trim())) add("SKU must be 1-64 letters, digits, '.', '_' or '-'.")
        if (!zipRegex.matches(request.zip.trim())) add("ZIP code must be 5 digits.")
        if (request.radiusMiles !in MIN_RADIUS..MAX_RADIUS) add("Radius must be between $MIN_RADIUS and $MAX_RADIUS miles.")
        if ((request.productName?.length ?: 0) > 200) add("Product name is too long.")
    }
}
