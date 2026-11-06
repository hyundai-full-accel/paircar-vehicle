package com.paircar.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.paircar.domain.model.CostRules
import com.paircar.domain.model.EfficiencySource
import com.paircar.domain.model.ReceiverAccount
import com.paircar.domain.model.SettlementPolicy
import com.paircar.domain.model.SettlementRequest
import com.paircar.domain.model.SettlementSettings
import com.paircar.domain.repository.SettlementSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.time.LocalDate
import java.time.YearMonth
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 계좌번호처럼 그대로 두면 안 되는 값을 감싸고 푼다 */
interface AccountCipher {
    fun encrypt(plain: String): String

    /** 풀지 못하면(키가 사라짐 등) null */
    fun decrypt(sealed: String): String?
}

/**
 * Android Keystore 의 AES-256 키로 AES/GCM 암호화한다. 키는 기기 밖으로 나오지 않고,
 * 앱을 지우면 키도 함께 지워진다. 저장 형식은 "iv:암호문" (각각 Base64).
 */
class KeystoreAccountCipher : AccountCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "${cipher.iv.base64()}:${sealed.base64()}"
    }

    override fun decrypt(sealed: String): String? = runCatching {
        val (iv, data) = sealed.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.onFailure { Log.w(TAG, "decrypt failed: $it") }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private companion object {
        const val TAG = "AccountCipher"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "paircar_receiver_account"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

/**
 * 정산 설정·요청 기록을 SharedPreferences 에 둔다 (정산 서버 미구축).
 * 계좌번호는 [cipher] 로 암호화한 값만 저장한다.
 */
class PrefsSettlementSettingsStore(
    context: Context,
    private val cipher: AccountCipher,
) : SettlementSettingsStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    override val settings: StateFlow<SettlementSettings> = _settings.asStateFlow()

    private val _requests = MutableStateFlow(loadRequests())
    override val requests: StateFlow<List<SettlementRequest>> = _requests.asStateFlow()

    override suspend fun saveSettings(settings: SettlementSettings) {
        val account = settings.account
        val sealed = account?.let { withContext(Dispatchers.Default) { cipher.encrypt(it.number) } }
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_BANK, account?.bank)
                .putString(KEY_ACCOUNT, sealed)
                .putBoolean(KEY_CHARGE, settings.rules.includeCharge)
                .putBoolean(KEY_TOLL, settings.rules.includeToll)
                .putBoolean(KEY_PARKING, settings.rules.includeParking)
                .putBoolean(KEY_ROUND_UP, settings.rules.roundUp)
                .putString(KEY_PRICE, settings.chargePricePerKwh?.toString())
                .putString(KEY_EFFICIENCY, settings.efficiencyKmPerKwh?.toString())
                .commit()
        }
        _settings.value = settings
    }

    override suspend fun saveRequest(request: SettlementRequest) {
        val next = _requests.value.filterNot { it.month == request.month } + request
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_REQUESTS, JSONArray(next.map { it.toJson() }).toString()).commit()
        }
        _requests.value = next
    }

    private fun loadSettings(): SettlementSettings {
        val bank = prefs.getString(KEY_BANK, null)
        val number = prefs.getString(KEY_ACCOUNT, null)?.let(cipher::decrypt)
        return SettlementSettings(
            account = if (bank != null && number != null) ReceiverAccount(bank, number) else null,
            rules = CostRules(
                includeCharge = prefs.getBoolean(KEY_CHARGE, true),
                includeToll = prefs.getBoolean(KEY_TOLL, true),
                includeParking = prefs.getBoolean(KEY_PARKING, false),
                roundUp = prefs.getBoolean(KEY_ROUND_UP, true),
            ),
            chargePricePerKwh = prefs.getString(KEY_PRICE, null)?.toIntOrNull(),
            efficiencyKmPerKwh = prefs.getString(KEY_EFFICIENCY, null)?.toDoubleOrNull(),
        )
    }

    private fun loadRequests(): List<SettlementRequest> = runCatching {
        val array = JSONArray(prefs.getString(KEY_REQUESTS, null) ?: return emptyList())
        (0 until array.length()).map { array.getJSONObject(it).toRequest() }
    }.onFailure { Log.w(TAG, "requests load failed: $it") }.getOrDefault(emptyList())

    private fun SettlementRequest.toJson() = JSONObject()
        .put("month", month.toString())
        .put("requestedAt", requestedAt.toString())
        .put("efficiency", policy.efficiencyKmPerKwh)
        .put("efficiencySource", policy.efficiencySource.name)
        .put("price", policy.chargePricePerKwh)
        .put("charge", policy.rules.includeCharge)
        .put("toll", policy.rules.includeToll)
        .put("parking", policy.rules.includeParking)
        .put("roundUp", policy.rules.roundUp)

    private fun JSONObject.toRequest() = SettlementRequest(
        month = YearMonth.parse(getString("month")),
        requestedAt = LocalDate.parse(getString("requestedAt")),
        policy = SettlementPolicy(
            efficiencyKmPerKwh = getDouble("efficiency"),
            efficiencySource = EfficiencySource.valueOf(getString("efficiencySource")),
            chargePricePerKwh = getInt("price"),
            rules = CostRules(
                includeCharge = getBoolean("charge"),
                includeToll = getBoolean("toll"),
                includeParking = getBoolean("parking"),
                roundUp = getBoolean("roundUp"),
            ),
        ),
    )

    private companion object {
        const val TAG = "SettlementSettings"
        const val PREFS = "settlement_settings"
        const val KEY_BANK = "account_bank"
        const val KEY_ACCOUNT = "account_number_sealed"
        const val KEY_CHARGE = "rule_charge"
        const val KEY_TOLL = "rule_toll"
        const val KEY_PARKING = "rule_parking"
        const val KEY_ROUND_UP = "rule_round_up"
        const val KEY_PRICE = "charge_price_per_kwh"
        const val KEY_EFFICIENCY = "efficiency_km_per_kwh"
        const val KEY_REQUESTS = "requests"
    }
}
