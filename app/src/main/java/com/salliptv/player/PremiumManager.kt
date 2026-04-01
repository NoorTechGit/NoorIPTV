package com.salliptv.player

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

/**
 * Premium manager — server is the source of truth (like TiviMate).
 *
 * Flow:
 * 1. On init: register device with server (fingerprint-based)
 * 2. Check premium status from server
 * 3. Cache locally for offline fallback only
 * 4. Google Play Billing can activate, but server validates
 * 5. BuildConfig.FORCE_PREMIUM only for dev/testing
 */
class PremiumManager(private val context: Context) {

    companion object {
        private const val TAG = "PremiumMgr"
        private const val PRODUCT_ID = "salliptv_pro"

        // API base
        private const val API_BASE = "https://salliptv.com/api"

        // Poll interval when waiting for activation (10 seconds)
        private const val POLL_INTERVAL_MS = 10_000L

        // Obfuscated pref keys (look like analytics prefs)
        private const val PREF_NAME = "app_analytics"
        private const val KEY_STATE = "session_quality"
        private const val KEY_INTEGRITY = "render_mode"
        private const val KEY_DEVICE_ID = "scroll_position"

        // Replace with your actual release signing certificate SHA-256
        private val EXPECTED_SIGNATURE_HASH: String? = null // Set after generating keystore

        fun sha256(input: String): String {
            return try {
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(input.toByteArray(Charsets.UTF_8))
                bytesToHex(digest)
            } catch (e: Exception) {
                input
            }
        }

        fun bytesToHex(bytes: ByteArray): String {
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null
    private var callback: PremiumCallback? = null
    private val httpClient = OkHttpClient()
    private val pollHandler = Handler(Looper.getMainLooper())
    private var isPolling = false

    private var verifiedState = -1 // -1=unknown, 0=free, 1=premium

    fun interface PremiumCallback {
        fun onPremiumStatusChanged(isPremium: Boolean)
    }

    // ==========================================
    // PUBLIC API
    // ==========================================

    fun init(callback: PremiumCallback) {
        this.callback = callback

        // Layer 1: BuildConfig flavor (dev/testing only)
        if (BuildConfig.FORCE_PREMIUM) {
            setPremiumState(true)
            notifyCallback(true)
            return
        }

        // Layer 2: APK integrity check
        if (!verifyAppIntegrity()) {
            Log.w(TAG, "Integrity check failed")
            setPremiumState(false)
            notifyCallback(false)
            return
        }

        // Layer 3: Register device with server + check server status
        registerDeviceAndCheckStatus()

        // Layer 4: Google Play Billing (for Play Store purchases)
        initBilling()
    }

    fun isPremium(): Boolean {
        if (BuildConfig.FORCE_PREMIUM) return true
        if (!verifyAppIntegrity()) return false
        if (verifiedState == 1) return true
        if (verifiedState == 0) return false
        return readPremiumState() // offline fallback
    }

    /**
     * Get the unique device ID.
     * Format: XXXX-XXXX (8 hex chars from hardware fingerprint)
     */
    fun getDeviceId(): String = generateDeviceId()

    /**
     * Get the full hardware fingerprint (sent to server for anti-spoof).
     */
    fun getFingerprint(): String = generateFingerprint()

    /**
     * Start polling the device inbox for activation/upgrade.
     */
    fun startPollingActivation() {
        if (isPolling || isPremium()) return
        isPolling = true
        pollHandler.post(pollRunnable)
    }

    fun stopPollingActivation() {
        isPolling = false
        pollHandler.removeCallbacks(pollRunnable)
    }

    fun purchase(activity: Activity) {
        val client = billingClient
        val details = productDetails
        if (client == null || details == null) {
            Log.e(TAG, "Billing not ready")
            return
        }

        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(params))
            .build()

        client.launchBillingFlow(activity, flowParams)
    }

    fun destroy() {
        stopPollingActivation()
        billingClient?.endConnection()
        billingClient = null
    }

    // ==========================================
    // DEVICE REGISTRATION & SERVER CHECK
    // ==========================================

    /**
     * Register this device with the server and check premium status.
     * This is the core of the TiviMate-like model:
     * the server is the single source of truth.
     */
    private fun registerDeviceAndCheckStatus() {
        // Show cached state immediately while we check server
        val cached = readPremiumState()
        if (cached) {
            verifiedState = 1
            notifyCallback(true)
        }

        Thread {
            try {
                // Step 1: Register device
                val deviceId = getDeviceId()
                val fingerprint = getFingerprint()

                val json = """{"device_id":"$deviceId","fingerprint":"$fingerprint","is_premium":$cached}"""
                val body = json.toRequestBody("application/json".toMediaType())

                val regRequest = Request.Builder()
                    .url("$API_BASE/device/register")
                    .post(body)
                    .header("User-Agent", "SallIPTV/1.0")
                    .build()

                httpClient.newCall(regRequest).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "Device register failed: ${resp.code}")
                    }
                }

                // Step 2: Check server-side premium status
                val statusRequest = Request.Builder()
                    .url("$API_BASE/device/$deviceId/status")
                    .header("User-Agent", "SallIPTV/1.0")
                    .build()

                httpClient.newCall(statusRequest).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val respJson = resp.body?.string()
                        if (respJson != null) {
                            val root = JsonParser.parseString(respJson).asJsonObject
                            val serverPremium = root.has("is_premium") && root.get("is_premium").asBoolean

                            // Server is authoritative
                            setPremiumState(serverPremium)
                            verifiedState = if (serverPremium) 1 else 0
                            notifyCallback(serverPremium)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server check failed: ${e.message}")
                // Keep cached state on network error
                if (verifiedState == -1) {
                    val fallback = readPremiumState()
                    verifiedState = if (fallback) 1 else 0
                    notifyCallback(fallback)
                }
            }
        }.start()
    }

    // ==========================================
    // POLLING (for inbox — activation from phone)
    // ==========================================

    private val pollRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!isPolling) return
            checkDeviceStatus { activated ->
                if (activated) {
                    stopPollingActivation()
                } else if (isPolling) {
                    pollHandler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * Check device premium status on server.
     */
    private fun checkDeviceStatus(cb: ((Boolean) -> Unit)?) {
        Thread {
            try {
                val request = Request.Builder()
                    .url("$API_BASE/device/${getDeviceId()}/status")
                    .header("User-Agent", "SallIPTV/1.0")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val respJson = response.body?.string()
                        if (respJson != null) {
                            val root = JsonParser.parseString(respJson).asJsonObject
                            val premium = root.has("is_premium") && root.get("is_premium").asBoolean

                            if (premium) {
                                setPremiumState(true)
                                verifiedState = 1
                                notifyCallback(true)
                            } else if (verifiedState == 1) {
                                // Was premium but server says no → revoked
                                setPremiumState(false)
                                verifiedState = 0
                                notifyCallback(false)
                            }

                            cb?.let { pollHandler.post { it(premium) } }
                            return@Thread
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Status check failed: ${e.message}")
            }

            cb?.let { pollHandler.post { it(false) } }
        }.start()
    }

    // ==========================================
    // DEVICE ID & FINGERPRINT
    // ==========================================

    @Suppress("DEPRECATION")
    private fun generateDeviceId(): String {
        // Short 8-char hex ID from hardware fingerprint
        val hash = sha256(generateFingerprint())
        return hash.substring(0, 4).uppercase() + "-" + hash.substring(4, 8).uppercase()
    }

    @Suppress("DEPRECATION")
    private fun generateFingerprint(): String {
        // Compound fingerprint from multiple hardware sources — hard to spoof
        val data = "${Build.FINGERPRINT}|${Build.SERIAL}|${Build.MODEL}|${Build.BOARD}|${Build.HARDWARE}|${Build.MANUFACTURER}"
        return sha256(data)
    }

    // ==========================================
    // GOOGLE PLAY BILLING
    // ==========================================

    private fun initBilling() {
        billingClient = BillingClient.newBuilder(context)
            .setListener(::onPurchasesUpdated)
            .enablePendingPurchases()
            .build()

        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryExistingPurchases()
                    queryProductDetails()
                }
                // If billing not available (Fire TV), server status is already checked
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing disconnected")
            }
        })
    }

    private fun queryExistingPurchases() {
        billingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                for (p in purchases) {
                    if (p.products.contains(PRODUCT_ID) && p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        // Play Store says premium — sync to server
                        verifiedState = 1
                        setPremiumState(true)
                        notifyCallback(true)
                        syncPurchaseToServer(p)
                        return@queryPurchasesAsync
                    }
                }
            }
        }
    }

    private fun queryProductDetails() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient?.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(product))
                .build()
        ) { _, detailsList ->
            if (detailsList.isNotEmpty()) {
                productDetails = detailsList[0]
            }
        }
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (p in purchases) {
                if (p.products.contains(PRODUCT_ID) && p.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    verifiedState = 1
                    setPremiumState(true)
                    notifyCallback(true)

                    // Sync to server so it knows this device is premium
                    syncPurchaseToServer(p)

                    if (!p.isAcknowledged) {
                        billingClient?.acknowledgePurchase(
                            AcknowledgePurchaseParams.newBuilder()
                                .setPurchaseToken(p.purchaseToken)
                                .build()
                        ) { Log.i(TAG, "Purchase acknowledged") }
                    }
                    return
                }
            }
        }
    }

    /**
     * After a Play Store purchase, tell the server this device is now premium.
     * This ensures the server stays in sync as the source of truth.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun syncPurchaseToServer(purchase: Purchase) {
        Thread {
            try {
                val json = """{"device_id":"${getDeviceId()}","fingerprint":"${getFingerprint()}","is_premium":true}"""
                val body = json.toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$API_BASE/device/register")
                    .post(body)
                    .header("User-Agent", "SallIPTV/1.0")
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    Log.i(TAG, "Purchase synced to server: ${resp.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync purchase: ${e.message}")
            }
        }.start()
    }

    // ==========================================
    // ANTI-CRACK: INTEGRITY VERIFICATION
    // ==========================================

    @Suppress("DEPRECATION")
    private fun verifyAppIntegrity(): Boolean {
        if (EXPECTED_SIGNATURE_HASH == null) return true // dev mode

        return try {
            val info = context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNATURES
            )

            if (info.signatures.isNullOrEmpty()) return false

            val sig = info.signatures[0]
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(sig.toByteArray())
            val currentHash = bytesToHex(digest)

            EXPECTED_SIGNATURE_HASH.equals(currentHash, ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "Integrity check error: ${e.message}")
            false
        }
    }

    // ==========================================
    // OBFUSCATED STATE STORAGE (offline cache only)
    // ==========================================

    private fun setPremiumState(premium: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATE, generateStateToken(premium))
            .putString(KEY_INTEGRITY, generateIntegrityHash(premium))
            .putString(KEY_DEVICE_ID, getDeviceId())
            .apply()
        verifiedState = if (premium) 1 else 0
    }

    private fun readPremiumState(): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val stateValue = prefs.getString(KEY_STATE, null)
        val integrityValue = prefs.getString(KEY_INTEGRITY, null)

        if (stateValue == null || integrityValue == null) return false

        val claimsPremium = stateValue.startsWith("p_")
        val expectedIntegrity = generateIntegrityHash(claimsPremium)

        if (integrityValue != expectedIntegrity) {
            Log.w(TAG, "State integrity mismatch, resetting")
            setPremiumState(false)
            return false
        }

        return claimsPremium
    }

    private fun generateStateToken(premium: Boolean): String {
        val base = (if (premium) "p_" else "f_") + context.packageName
        return sha256(base).substring(0, 16)
    }

    @Suppress("DEPRECATION")
    private fun generateIntegrityHash(premium: Boolean): String {
        val data = "${context.packageName}|${if (premium) "1" else "0"}|${Build.FINGERPRINT}|${Build.SERIAL}"
        return sha256(data)
    }

    // ==========================================
    // UTILS
    // ==========================================

    private fun notifyCallback(isPremium: Boolean) {
        callback?.let { cb ->
            if (context is Activity) {
                context.runOnUiThread { cb.onPremiumStatusChanged(isPremium) }
            } else {
                cb.onPremiumStatusChanged(isPremium)
            }
        }
    }

}
