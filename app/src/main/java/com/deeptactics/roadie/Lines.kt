package com.deeptactics.roadie

// 반려차 대사. 주행 중 대사는 Course의 장면에 들어 있다
object Lines {
    fun greeting(km: Float, count: Int) = when {
        count == 0 -> "처음이네. 같이 달려 보자. 오늘 바다 쪽 어때?"
        km >= 100 -> "우리 벌써 ${km.toInt() / 100 * 100}km 같이 달렸어. 오늘은 어디로 갈까?"
        else -> "오늘 바다 쪽 어때?"
    }

    fun arrive(scenes: List<Scene>, firstDrive: Boolean, crossedKm: Int?, revisit: Boolean): String {
        val main = when (scenes.firstOrNull()?.kind) {
            SceneKind.TUNNEL_EXIT -> "터널 빠져나올 때 그 순간, 나도 좋았어."
            SceneKind.SEA -> "바다 옆으로 달린 거, 오래 기억할게."
            SceneKind.PASS -> "고갯마루에서 확 트이던 거 좋았지?"
            SceneKind.BRIDGE, SceneKind.NIGHT -> "불빛 사이로 달리니까 좋더라."
            null -> "오늘은 여기까지. 다음에 또 달리자."
        }
        val extra = when {
            firstDrive -> "우리 첫 드라이브였어."
            crossedKm != null -> "우리 이제 ${crossedKm}km 넘게 달렸어."
            revisit -> "이 길 또 왔네. 나 여기 좋아해."
            else -> null
        }
        return listOfNotNull(main, extra).joinToString(" ")
    }
}
