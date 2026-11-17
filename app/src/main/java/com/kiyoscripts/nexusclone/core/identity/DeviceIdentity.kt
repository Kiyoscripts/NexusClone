package com.kiyoscripts.nexusclone.core.identity

import java.security.SecureRandom
import java.util.Locale

/**
 * A synthetic device identity for one clone.
 *
 * Every field is generated once and then frozen, because apps fingerprint the tuple and a
 * value that changes between launches is a stronger signal than a shared one.
 */
data class DeviceIdentity(
    val id: Long = 0L,
    val name: String,
    val brand: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val product: String,
    val board: String,
    val hardware: String,
    val fingerprint: String,
    val buildId: String = "",
    val sdk: Int = 31,
    val abi: String = "arm64-v8a",
    val androidId: String,
    val imei: String,
    val meid: String,
    val macWifi: String,
    val serial: String,
    val gsfId: String,
    val locale: String,
    val timezone: String,
    val densityDpi: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val createdAt: Long = System.currentTimeMillis(),
    /** Epoch millis of the last bind, 0 when the profile was never bound. */
    val lastUsedAt: Long = 0L
) {
    /** Build.MODEL etc. as a map, for the diagnostics screen and the hook layer. */
    fun buildFields(): Map<String, String> = linkedMapOf(
        "BRAND" to brand,
        "MANUFACTURER" to manufacturer,
        "MODEL" to model,
        "DEVICE" to device,
        "PRODUCT" to product,
        "BOARD" to board,
        "HARDWARE" to hardware,
        "FINGERPRINT" to fingerprint,
        "SERIAL" to serial
    )

    /**
     * The same identity as Android system property name / value pairs.
     *
     * This is the form the native property hook consumes. The names are the ones
     * {@code VirtualSpoof.cpp} intercepts, so a clone that binds this identity answers
     * {@code __system_property_get} with its own values instead of the shared fallback.
     */
    fun spoofedProperties(): Map<String, String> = linkedMapOf(
        "ro.product.brand" to brand,
        "ro.product.manufacturer" to manufacturer,
        "ro.product.name" to product,
        "ro.product.model" to model,
        "ro.product.device" to device,
        "ro.product.board" to board,
        "ro.hardware" to hardware,
        "ro.boot.hardware" to hardware,
        "ro.build.id" to buildId,
        "ro.build.version.sdk" to sdk.toString(),
        "ro.build.fingerprint" to fingerprint,
        "ro.product.cpu.abi" to abi,
        "ro.serialno" to serial
    )

    /**
     * Flatten [spoofedProperties] into the alternating name/value array that
     * {@code AppConfig.spoofedProps} carries across the process bind.
     */
    fun toSpoofPairs(): Array<String> {
        val props = spoofedProperties()
        val out = ArrayList<String>(props.size * 2)
        for ((key, value) in props) {
            out.add(key)
            out.add(value)
        }
        return out.toTypedArray()
    }
}

/**
 * Generates coherent device identities.
 *
 * The point of coherence: a Pixel 6 fingerprint with a Samsung model string is an instant
 * flag. Each template below is internally consistent, and the random parts (ids, macs) are
 * generated with the right format and check digits.
 */
object IdentityFactory {

    private val rng = SecureRandom()

    /** Internally consistent device templates. */
    private data class Template(
        val brand: String,
        val manufacturer: String,
        val model: String,
        val device: String,
        val product: String,
        val board: String,
        val hardware: String,
        val release: String,
        val sdk: Int,
        val buildId: String,
        val incremental: String,
        val abi: String = "arm64-v8a"
    )

    private val templates = listOf(
        Template("google", "Google", "Pixel 6", "oriole", "oriole",
            "oriole", "oriole", "12", 31, "SP1A.210812.015", "7679548", "arm64-v8a"),
        Template("google", "Google", "Pixel 7", "panther", "panther",
            "panther", "panther", "13", 33, "TQ1A.230205.002", "9471150", "arm64-v8a"),
        Template("google", "Google", "Pixel 5", "redfin", "redfin",
            "redfin", "redfin", "11", 30, "RQ3A.210805.001.A1", "7474174", "arm64-v8a"),
        Template("samsung", "samsung", "SM-G991B", "o1s", "o1sxxx",
            "exynos2100", "exynos2100", "12", 31, "SP1A.210812.016", "G991BXXU3BUK8", "arm64-v8a"),
        Template("samsung", "samsung", "SM-S901B", "r0s", "r0sxxx",
            "exynos2200", "exynos2200", "13", 33, "TP1A.220624.014", "S901BXXU1AVC6", "arm64-v8a"),
        Template("Xiaomi", "Xiaomi", "M2101K6G", "sweet", "sweet_global",
            "atoll", "qcom", "12", 31, "SKQ1.211006.001", "V13.0.5.0.SKFMIXM", "arm64-v8a"),
        Template("OnePlus", "OnePlus", "LE2123", "OnePlus9Pro", "OnePlus9Pro_EEA",
            "lahaina", "qcom", "12", 31, "RKQ1.211119.001", "c21da0e0", "arm64-v8a"),
        Template("motorola", "motorola", "moto g(60)", "elish", "elish_retail",
            "lahaina", "qcom", "12", 31, "S1RQS32.20-42-10-9", "b3a1a", "arm64-v8a")
    )

    /** Generate a fresh identity. */
    fun generate(name: String? = null): DeviceIdentity {
        val t = templates[rng.nextInt(templates.size)]
        val fingerprint = "${t.brand}/${t.product}/${t.device}:${t.release}/" +
                "${t.buildId}/${t.incremental}:user/release-keys"
        return DeviceIdentity(
            name = name ?: "${t.model} #${rng.nextInt(9000) + 1000}",
            brand = t.brand,
            manufacturer = t.manufacturer,
            model = t.model,
            device = t.device,
            product = t.product,
            board = t.board,
            hardware = t.hardware,
            fingerprint = fingerprint,
            buildId = t.buildId,
            sdk = t.sdk,
            abi = t.abi,
            androidId = randomAndroidId(),
            imei = randomImei(),
            meid = randomMeid(),
            macWifi = randomMac(),
            serial = randomSerial(t.brand),
            gsfId = randomGsfId(),
            locale = "en-US",
            timezone = "UTC",
            densityDpi = 420,
            screenWidth = 1080,
            screenHeight = 2400
        )
    }

    /** 16 hex chars, the Android ID format. */
    fun randomAndroidId(): String =
        (1..16).map { HEX[rng.nextInt(HEX.length)] }.joinToString("")

    /**
     * 15-digit IMEI with a valid Luhn check digit.
     *
     * TAC prefix 35 is the reporting-body reserved range, so a generated IMEI cannot
     * collide with a real handset.
     */
    fun randomImei(): String {
        val body = StringBuilder("35")
        repeat(12) { body.append(rng.nextInt(10)) }
        val digits = body.toString()
        return digits + luhnCheckDigit(digits)
    }

    /** 14 hex chars, the MEID format. */
    fun randomMeid(): String =
        (1..14).map { HEX[rng.nextInt(HEX.length)] }.joinToString("")

    /** Locally administered unicast MAC: second nibble is 2, 6, A or E. */
    fun randomMac(): String {
        val first = listOf(0x02, 0x06, 0x0A, 0x0E)[rng.nextInt(4)]
        val bytes = IntArray(6)
        bytes[0] = first
        for (i in 1..5) bytes[i] = rng.nextInt(256)
        return bytes.joinToString(":") { String.format(Locale.US, "%02X", it) }
    }

    /**
     * A serial in the format the given vendor actually ships.
     *
     * A 12 char generic string matches no vendor, so it is a fingerprint on its own.
     * Samsung uses an 11 char R prefix, Google 14 alnum, Xiaomi 12 alnum, OnePlus 16 hex
     * and motorola 10 alnum. Unknown brands fall back to the generic 12 alnum shape.
     */
    fun randomSerial(brand: String): String = when (brand.lowercase(Locale.US)) {
        "samsung" -> "R" + alnum(10)
        "google" -> alnum(14)
        "xiaomi" -> alnum(12)
        "oneplus" -> hex(16)
        "motorola" -> alnum(10)
        else -> alnum(12)
    }

    /** Generic 12 alphanumeric serial, kept for callers that have no brand at hand. */
    fun randomSerial(): String = randomSerial("generic")

    private fun alnum(count: Int): String =
        (1..count).map { ALNUM[rng.nextInt(ALNUM.length)] }.joinToString("")

    private fun hex(count: Int): String =
        (1..count).map { HEX[rng.nextInt(HEX.length)] }.joinToString("")

    /** 16 hex chars, the GSF ID format. */
    fun randomGsfId(): String =
        (1..16).map { HEX[rng.nextInt(HEX.length)] }.joinToString("")

    /** Luhn check digit for a numeric string. */
    fun luhnCheckDigit(digits: String): Char {
        var sum = 0
        var double = true
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return ('0' + ((10 - (sum % 10)) % 10))
    }

    /** True when [imei] is 15 digits and its last digit is the correct Luhn check digit. */
    fun isValidImei(imei: String): Boolean {
        if (imei.length != 15 || imei.any { !it.isDigit() }) return false
        return luhnCheckDigit(imei.substring(0, 14)) == imei[14]
    }

    private const val HEX = "0123456789abcdef"
    private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
}
