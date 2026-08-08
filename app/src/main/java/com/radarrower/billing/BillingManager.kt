package com.radarrower.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Obsługa zakupu wersji Pro przez Google Play Billing.
 *
 * ZAŁOŻENIA DO PODMIANY PRZED WYDANIEM:
 *  1. [PRO_PRODUCT_ID] musi odpowiadać identyfikatorowi produktu jednorazowego
 *     (typ „Produkt w aplikacji", nie subskrypcja) założonemu w Play Console.
 *  2. Zakup zadziała dopiero po wgraniu podpisanego buildu na kanał testowy —
 *     w buildzie debug Play zawsze odpowie BILLING_UNAVAILABLE i aplikacja
 *     pokaże się jako Free. To oczekiwane, nie błąd.
 *
 * Zakup jest NIEKONSUMOWALNY i potwierdzany (acknowledge) — brak potwierdzenia
 * w ciągu 3 dni oznacza automatyczny zwrot pieniędzy przez Google.
 */
class BillingManager(
    context: Context,
    private val proRepository: ProRepository,
) {

    companion object {
        private const val TAG = "BillingManager"

        /** Identyfikator produktu z Play Console. */
        const val PRO_PRODUCT_ID = "radar_pro_lifetime"
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _price = MutableStateFlow<String?>(null)

    /** Sformatowana cena z Play (z walutą użytkownika) albo null, gdy nieznana. */
    val price = _price.asStateFlow()

    private val _available = MutableStateFlow(false)

    /** Czy Play jest dostępne i da się kupić — steruje widocznością przycisku. */
    val available = _available.asStateFlow()

    private var productDetails: ProductDetails? = null

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach { handlePurchase(it) }
        } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.w(TAG, "Zakup nieudany: ${result.responseCode} ${result.debugMessage}")
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(purchasesListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    /** Łączy się z Play i odtwarza stan zakupu. Wołane przy starcie aplikacji. */
    fun start() {
        if (client.isReady) {
            refresh()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                _available.value = ok
                if (ok) refresh() else Log.i(TAG, "Play niedostępne: ${result.debugMessage}")
            }

            override fun onBillingServiceDisconnected() {
                _available.value = false
            }
        })
    }

    fun stop() {
        runCatching { client.endConnection() }
    }

    /** Odpytuje Play o cenę i o już posiadane zakupy (odtworzenie po reinstalacji). */
    fun refresh() {
        queryProductDetails()
        queryExistingPurchases()
    }

    private fun queryProductDetails() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRO_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "Brak danych produktu: ${result.debugMessage}")
                return@queryProductDetailsAsync
            }
            val product = details.firstOrNull { it.productId == PRO_PRODUCT_ID }
            productDetails = product
            _price.value = product?.oneTimePurchaseOfferDetails?.formattedPrice
        }
    }

    /**
     * Odtworzenie zakupu — wołane przy każdym starcie, więc po reinstalacji
     * albo zmianie telefonu Pro wraca samo, bez „przywróć zakupy" wciskanego
     * przez użytkownika. Przycisk w UI jest tylko awaryjny.
     */
    private fun queryExistingPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val owned = purchases.any { purchase ->
                purchase.products.contains(PRO_PRODUCT_ID) &&
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            purchases.forEach { handlePurchase(it) }
            // brak zakupu = brak Pro (np. zwrot pieniędzy przez Google)
            if (!owned) scope.launch { proRepository.setPro(false) }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (!purchase.products.contains(PRO_PRODUCT_ID)) return
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        scope.launch { proRepository.setPro(true) }

        // bez potwierdzenia Google zwróci pieniądze po 3 dniach
        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            client.acknowledgePurchase(params) { result ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "Potwierdzenie zakupu nieudane: ${result.debugMessage}")
                }
            }
        }
    }

    /** Otwiera okno zakupu Play. Zwraca false, gdy nie ma czego kupić. */
    fun launchPurchase(activity: Activity): Boolean {
        val details = productDetails ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }
}
