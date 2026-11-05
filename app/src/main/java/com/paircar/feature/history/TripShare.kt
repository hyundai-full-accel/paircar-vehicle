package com.paircar.feature.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/**
 * 요약 문구를 안드로이드 공유 창(ACTION_SEND)으로 넘긴다.
 * 차량에 받을 앱이 없으면(에뮬레이터가 그렇다) 클립보드에 복사한다. 결과 안내 문구를 돌려준다.
 */
internal fun shareTripSummary(context: Context, text: String): String {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    if (send.resolveActivity(context.packageManager) != null) {
        context.startActivity(Intent.createChooser(send, "운행 요약 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "공유할 앱을 골라 주세요"
    }
    val clipboard = context.getSystemService(ClipboardManager::class.java)
        ?: return "이 차량에는 공유할 수 있는 앱이 없어요"
    clipboard.setPrimaryClip(ClipData.newPlainText("운행 요약", text))
    return "공유할 앱이 없어 요약 문구를 복사했어요"
}
