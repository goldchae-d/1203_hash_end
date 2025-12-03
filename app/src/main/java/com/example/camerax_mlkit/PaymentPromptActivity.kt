package com.example.camerax_mlkit

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.example.camerax_mlkit.crypto.RetrofitProvider
import com.example.camerax_mlkit.crypto.SessionKeyManager
import com.example.camerax_mlkit.security.QrToken
import com.example.camerax_mlkit.security.SecureQr
import com.example.camerax_mlkit.security.SignatureVerifier
import com.example.camerax_mlkit.security.WhitelistManager
import com.example.camerax_mlkit.security.QrRawWhitelist
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch

class PaymentPromptActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QR_CODE = "extra_qr_code"
        const val EXTRA_TRIGGER = "extra_trigger"
        private const val TAG   = "PaymentPromptActivity"
        private const val TAG_PP = "PayPrompt"

        // 공격 시연용: UI 스와프(검증 우회) 허용 여부
        const val ALLOW_UI_SWAP_BYPASS = false
    }

    private var dialog: BottomSheetDialog? = null
    private var sheetView: View? = null
    private var latestQrText: String? = null

    private var selectedStoreName: String? = null
    private var selectedLocationId: String? = null
    private var fenceId: String = "unknown"

    // ✅ 재등장 방지 가드
    private var selectionHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WhitelistManager.load(this)

        // ── 트리거/컨텍스트 수집 ──
        val trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "UNKNOWN"
        val geo     = intent.getBooleanExtra("geo", false)
        val beacon  = intent.getBooleanExtra("beacon", false)
        val wifiOk  = TriggerGate.allowedForQr()
        fenceId     = intent.getStringExtra("fenceId") ?: "unknown"

        // ✅ (1) BT/GPS 하드가드
        if (!isBtOn() || !isLocationOn()) {
            Log.d(TAG, "blocked: BT/GPS OFF")
            Toast.makeText(this, "블루투스/위치가 꺼져 있어 결제창을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        // 정책: (지오∧비콘) OR (신뢰 Wi-Fi) OR (USER)
        val allow = ((geo && beacon) || wifiOk || trigger == "USER")
        if (!allow) { finish(); return }

        // 잠금화면에서도 표시
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // ① 매장 선택 먼저
        handleStoreSelectionFromIntent(intent)

        // ② 선택 반영해 토큰 생성 (LV3 대비 — 현재 LV2 데모에서는 화면에만 표시 가능)
        lifecycleScope.launch {
            try {
                val (kid, sk) = SessionKeyManager.ensureKey(this@PaymentPromptActivity, RetrofitProvider.keyApi)
                val sid = SessionIdProvider.get(this@PaymentPromptActivity)
                val meta  = TriggerGate.getCurrentBeacon()
                val entry = meta?.let { WhitelistManager.findBeacon(it.uuid, it.major, it.minor) }
                val locId = selectedLocationId ?: (entry?.locationId ?: fenceId)
                val merchantId = entry?.merchantId ?: "merchant_unknown"

                val qrText = SecureQr.buildEncryptedToken(
                    kid, sk, sid, merchantId, null,
                    extra = mapOf("type" to "account", "location_id" to locId, "fence_id" to fenceId)
                )
                latestQrText = qrText
                setTokenTextIfPresent(qrText)
            } catch (_: Throwable) {
                latestQrText = null
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        fenceId = intent?.getStringExtra("fenceId") ?: fenceId

        // ✅ (2) newIntent 시에도 즉시 가드
        if (!isBtOn() || !isLocationOn()) {
            Log.d(TAG, "blocked(newIntent): BT/GPS OFF")
            Toast.makeText(this, "블루투스/위치가 꺼져 있어 결제창을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        handleStoreSelectionFromIntent(intent)
    }

    /** 상태 확인 함수 */
    private fun isBtOn(): Boolean =
        android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.isEnabled == true

    private fun isLocationOn(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }

    /** 우리 앱의 CameraX 화면을 ‘일반카메라(Plain)’ 모드(자유 스캔)로 전환 */
    private fun openPlainCameraFree() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("plainCamera", true)         // 자유 스캔
            // forceLv2 넣지 않음
        )
        finish()
    }

    /** 우리 앱의 CameraX 화면을 ‘일반카메라(Plain)’ 모드(LV2 강제)로 전환 */
    private fun openPlainCameraLv2() {
        val ctx = ctxLocationId()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("plainCamera", true)
                .putExtra("forceLv2", true)             // ✅ LV2 강제
                .putExtra("ctxLocationId", ctx)         // ✅ 선택 매장 기준 강제 검증
        )
        finish()
    }

    /**
     * 매장 선택 처리
     * - 후보가 0개면: ✅ 곧바로 Plain 카메라(자유 스캔)
     * - 후보가 1개면: 다이얼로그 없이 즉시 처리
     * - 후보가 2개 이상이면: 다이얼로그 표시
     * - 여기서는 “카메라 사용하기” 항목을 **추가하지 않음** (라우터에서 이미 처리)
     */
    private fun handleStoreSelectionFromIntent(intent: Intent?) {
        if (selectionHandled) return

        val storeNames  = intent?.getStringArrayListExtra("extra_store_names") ?: arrayListOf()
        val locationIds = intent?.getStringArrayListExtra("extra_locations")   ?: arrayListOf()

        // ✅ 후보 없음 → Plain 카메라(자유 스캔)
        if (storeNames.isEmpty() || locationIds.isEmpty()) {
            openPlainCameraFree()
            return
        }

        // ✅ 후보가 1개면 즉시 처리
        if (storeNames.size == 1 && locationIds.size == 1) {
            selectionHandled = true
            val onlyName = storeNames[0]
            val onlyLoc  = locationIds[0]

            if (onlyLoc == "__camera__") {
                // 매장선택창의 '카메라 사용하기'는 자유 스캔 유지
                openPlainCameraFree()
                return
            } else {
                selectedStoreName  = onlyName
                selectedLocationId = onlyLoc
                openPaymentForStore(onlyLoc, onlyName)
                return
            }
        }

        // ✅ 2개 이상일 때만 다이얼로그 표시
        android.app.AlertDialog.Builder(this)
            .setTitle("결제하실 매장을 선택하세요")
            .setItems(storeNames.toTypedArray()) { _, which ->
                if (selectionHandled) return@setItems
                selectionHandled = true

                val chosenName = storeNames.getOrNull(which)
                val chosenLoc  = locationIds.getOrNull(which)

                if (chosenLoc == "__camera__") {
                    // 매장선택창의 '카메라 사용하기'는 자유 스캔 유지
                    openPlainCameraFree()
                    return@setItems
                }
                selectedStoreName  = chosenName
                selectedLocationId = chosenLoc
                openPaymentForStore(chosenLoc, chosenName)
            }
            .setCancelable(false)
            .show()
    }

    private fun openPaymentForStore(locationId: String?, storeName: String?) {
        showOrExpandPayChooser(
            title = storeName ?: getString(R.string.title_pay),
            message = getString(R.string.subtitle_pay)
        )
    }

    // ── 결제 바텀시트 (여기 ‘카메라 사용하기’는 LV2 강제 모드로 열기) ──
    private fun showOrExpandPayChooser(title: String, message: String) {
        dialog?.let { d ->
            d.findViewById<TextView>(R.id.tvTitle)?.text = title
            d.findViewById<TextView>(R.id.tvSubtitle)?.text = message
            d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
                BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED
                sheetView = it
            }
            latestQrText?.let { setTokenTextIfPresent(it) }
            return
        }

        val d = BottomSheetDialog(this)
        d.setContentView(R.layout.dialog_pay_chooser)
        d.setDismissWithAnimation(true)
        d.setOnShowListener {
            val sheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let { BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED; sheetView = it }

            d.findViewById<TextView>(R.id.tvTitle)?.text = title
            d.findViewById<TextView>(R.id.tvSubtitle)?.text = message
            latestQrText?.let { setTokenTextIfPresent(it) }

            d.findViewById<View>(R.id.btnKakao)?.setOnClickListener { d.dismiss(); showKakaoPreview() }
            d.findViewById<View>(R.id.btnNaver)?.setOnClickListener { d.dismiss(); showNaverPreview() }
            d.findViewById<View>(R.id.btnToss )?.setOnClickListener { d.dismiss(); openTtareungi(); finish() }
            // ✅ 결제선택창 ‘카메라 사용하기’ → LV2 강제
            d.findViewById<View>(R.id.btnInApp)?.setOnClickListener { d.dismiss(); openPlainCameraLv2() }
            d.findViewById<View>(R.id.btnCancel)?.setOnClickListener { d.dismiss(); finish() }
        }
        d.setOnCancelListener { finish() }
        d.show()
        dialog = d
    }

    /** 레이아웃에 tvToken id가 있을 때만 표시 */
    private fun setTokenTextIfPresent(text: String) {
        val tv = sheetView?.findViewById<TextView?>(R.id.tvToken) ?: return
        tv.text = text
        tv.visibility = View.VISIBLE
    }

    // (미리보기/검증 유틸 등)
    private fun showPreview(@DrawableRes imgRes: Int, onClick: () -> Unit) {
        val dialog = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.dialog_qr_preview, null, false)
        dialog.setContentView(v)
        val img = v.findViewById<ImageView>(R.id.imgPreview)
        img.setImageResource(imgRes)
        img.setOnClickListener { onClick() }

        img.setOnLongClickListener { view ->
            view.alpha = 0.4f
            Toast.makeText(this, "해시(Hash) 분석 중입니다…", Toast.LENGTH_SHORT).show()

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE,
                    com.google.mlkit.vision.barcode.common.Barcode.FORMAT_AZTEC,
                    com.google.mlkit.vision.barcode.common.Barcode.FORMAT_DATA_MATRIX,
                    com.google.mlkit.vision.barcode.common.Barcode.FORMAT_PDF417
                ).build()
            val scanner = BarcodeScanning.getClient(options)
            val bmp = BitmapFactory.decodeResource(resources, imgRes)
            val image = InputImage.fromBitmap(bmp, 0)

            scanner.process(image)
                .addOnSuccessListener { list ->
                    view.alpha = 1f
                    val raw = list.firstOrNull()?.rawValue
                    if (raw == null) {
                        Toast.makeText(this, "QR을 인식하지 못했습니다.", Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }

                    // 🔍 해시 디버그 로그: raw / hash / 화이트리스트 매칭 여부
                    QrRawWhitelist.debugLog(raw)

                    // 0) 공격 시연용 우회(필요 시에만 true로) — 기본 false 권장
                    if (ALLOW_UI_SWAP_BYPASS) {
                        dialog.dismiss()
                        proceedPayment()
                        return@addOnSuccessListener
                    }

                    // 1) ✅ 항상 LV2 검증을 먼저 수행
                    val okLv2 = verifyRawByWhitelistAndBeacon(raw)

                    if (okLv2) {
                        // 2) 통과 시: 토스트 + (raw가 URL이면) 그 URL로 이동
                        Toast.makeText(this, "Lv2 + 해시 검증 통과", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()

                        if (raw.startsWith("http://") || raw.startsWith("https://")) {
                            startActivity(Intent(Intent.ACTION_VIEW, raw.toUri()))
                        } else {
                            // URL이 아닌 케이스면, 기존 결제 바텀시트로
                            showOrExpandPayChooser(getString(R.string.title_pay), getString(R.string.subtitle_pay))
                        }
                    } else {
                        // 3) 실패 시: 절대 URL 열지 않음
                        Toast.makeText(this, "등록되지 않았거나 위치가 불일치하는 QR입니다.", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    view.alpha = 1f
                    Toast.makeText(this, "분석 실패", Toast.LENGTH_SHORT).show()
                }

            true
        }

        dialog.setOnCancelListener { }
        dialog.show()
    }

    // 선택된 매장(선택창) → 없으면 TriggerGate가 해석한 현재 매장
    private fun resolvedLocId(): String? =
        selectedLocationId ?: TriggerGate.resolvedLocationId()

    // 현재 매장(locationId)에 따라 브랜드별 QR 이미지/키 결정
    private fun selectQrDrawable(brand: String): Pair<Int, String> {
        val loc = resolvedLocId()?.lowercase()

        val isA = when (loc) {
            "store_duksung_a", "store_a", "a" -> true
            else -> false
        }

        return when (brand) {
            "kakao" -> {
                if (isA) R.drawable.kakaopay_qr_a to "kakaopay_qr_a.png"
                else     R.drawable.kakaopay_qr_b to "kakaopay_qr_b.png"
            }
            "naver" -> {
                if (isA) R.drawable.npay_qr_a to "npay_qr_a.png"
                else     R.drawable.npay_qr_b to "npay_qr_b.png"
            }
            else -> error("Unknown brand: $brand")
        }
    }

    private fun showKakaoPreview() {
        val (imgRes, _) = selectQrDrawable("kakao")
        showPreview(imgRes) {
            // 단일 탭은 미리보기 → 결제 진행(시연용), 검증은 long-press에서 raw로 처리
            proceedPayment()
        }
    }

    /** 현재 컨텍스트 locationId (선택창 우선 → 없으면 TriggerGate 판단) */
    private fun ctxLocationId(): String? =
        selectedLocationId ?: TriggerGate.resolvedLocationId()

    private fun showNaverPreview() {
        val (imgRes, _) = selectQrDrawable("naver")
        showPreview(imgRes) { proceedPayment() }
    }

    private fun proceedPayment() {
        // 지금은 바텀시트를 다시 펼치는 동작으로 충분 (시연용)
        showOrExpandPayChooser(getString(R.string.title_pay), getString(R.string.subtitle_pay))
    }

    private fun openTtareungi() {
        val pkg = "com.dki.spb_android"
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) { startActivity(launch); return }
        try { startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$pkg".toUri())) }
        catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$pkg".toUri())) }
    }

    // (LV3용: 서명 토큰 검증 경로. 현재 LV2 데모에선 미사용)
    private fun verifyQrAgainstContext(rawQr: String): Boolean {
        val parsed = QrToken.parse(rawQr) ?: return false
        val (payload, sig) = parsed
        val meta = TriggerGate.getCurrentBeacon() ?: return false
        val pubPem = WhitelistManager.getMerchantPubKey(payload.merchantId) ?: return false
        if (!SignatureVerifier.verifyEcdsaP256(pubPem, QrToken.normalizedMessageForSign(payload), sig)) return false
        val beaconLoc = meta.locationId ?: return false
        if (payload.locationId != beaconLoc) return false
        if (fenceId != "unknown" && payload.locationId != fenceId) return false
        val beaconNonce = meta.nonce ?: return false
        if (payload.nonce != beaconNonce) return false
        val nowSec = System.currentTimeMillis() / 1000
        return payload.expiry >= nowSec
    }

    /** 화이트리스트 + 현재 컨텍스트(locationId) 일치 검증 (LV2) */
    private fun verifyRawByWhitelistAndBeacon(raw: String): Boolean {
        // 1) 화이트리스트에서 raw가 어느 매장 소속인지 확인
        val qrLoc = QrRawWhitelist.locationOf(raw) ?: return false

        // 2) 현재 컨텍스트 locationId (선택창 우선 → 없으면 TriggerGate 판단)
        val ctxLoc = ctxLocationId() ?: return false

        // 3) 매장 일치해야 통과
        return qrLoc == ctxLoc
    }

    override fun onDestroy() {
        dialog?.setOnShowListener(null)
        dialog?.setOnCancelListener(null)
        dialog = null
        sheetView = null
        super.onDestroy()
    }
}
