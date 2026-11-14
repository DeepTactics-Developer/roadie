package com.deeptactics.roadie

// 반려차 대사. 주행 중 장면 대사는 도로 데이터에 들어 있다
object Lines {
    fun greeting(km: Float, count: Int, last: Drive?, hour: Int, fav: Tag?): String {
        val time = when (hour) {
            in 5..10 -> "좋은 아침. "
            in 19..23, in 0..4 -> "밤공기 좋겠다. "
            else -> ""
        }
        val before = km - (last?.km?.toFloat() ?: 0f) // 지난 드라이브 전 누적
        return when {
            count == 0 -> "안녕, 난 로디야. 오늘 우리 첫 드라이브네. 어디로 갈지 말해 줘."
            (km / 100).toInt() > (before / 100).toInt() ->
                "${time}우리 벌써 ${km.toInt() / 100 * 100}km 같이 달렸어. 오늘은 어디로 갈까?"
            fav != null && count >= 2 -> "${time}요즘 ${fav.word} 자주 갔지. 비슷한 길로 하나 짜 놨어."
            last != null -> "${time}지난번엔 ${last.name.removeSuffix(" 한 바퀴")} 갔었지. 오늘은 어떤 길 갈까?"
            else -> "${time}오늘은 어떤 길 갈까?"
        }
    }

    const val PROMPT = "몇 분 정도, 어떤 느낌으로 달릴지 말해 줘."
    const val NOT_HEARD = "잘 못 들었어. 아래 예시를 눌러도 돼."
    const val START = "출발! 음악 깔게."
    const val JAM = "좀 막히네. 음악은 기다려 줄게."

    fun composed(w: Wish, c: Course): String {
        val mood = w.tags.joinToString(", ") { it.word }.ifEmpty { "기분 좋은" }
        val fork = if (c.fork != null) " 중간에 갈림길도 하나 있어." else ""
        return "좋아, $mood 쪽으로 ${c.minutes.toInt()}분짜리 한 바퀴 짰어.$fork"
    }

    fun forkAsk(f: Fork) = "곧 갈림길이야. 왼쪽은 ${f.left.label}, 오른쪽은 ${f.right.label}. 어디로 갈래?"
    fun forkPicked(c: Choice) = "좋아, ${c.label} 쪽으로 간다."
    fun forkDefault(c: Choice) = "대답 없으니까 ${c.label} 쪽으로 갈게."

    // 드롭이 끝나고 여운이 지난 뒤 한마디
    fun afterScene(kind: SceneKind) = when (kind) {
        SceneKind.TUNNEL_EXIT -> "터널 나오는 순간, 이거지."
        SceneKind.SEA -> "바다 냄새 난다."
        SceneKind.LAKE -> "호수 잔잔하다."
        SceneKind.PASS -> "여기서 보는 거 최고야."
        SceneKind.VIEW -> "공기부터 다르다."
        SceneKind.BRIDGE -> "다리 위가 제일 시원해."
    }

    fun arrive(scenes: List<Scene>, firstDrive: Boolean, crossedKm: Int?, revisit: Boolean): String {
        // 가장 인상적인 장면 하나로 말한다
        val best = scenes.minByOrNull { Course.PRIORITY.indexOf(it.kind) }
        val main = when (best?.kind) {
            SceneKind.TUNNEL_EXIT -> "터널 빠져나올 때 그 순간, 나도 좋았어."
            SceneKind.SEA -> "바다 옆으로 달린 거, 오래 기억할게."
            SceneKind.LAKE -> "호숫가 지날 때 음악이랑 딱 맞았지?"
            SceneKind.PASS -> "고갯마루에서 확 트이던 거 좋았지?"
            SceneKind.VIEW -> "계곡 공기 아직 남아 있는 것 같아."
            SceneKind.BRIDGE -> "다리 건널 때 좋더라."
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

    // 갈림길 대답. 왼쪽/오른쪽이나 길 성격으로 고른다
    fun pick(text: String, f: Fork): Choice? {
        val t = text.replace(" ", "")
        return when {
            Regex("왼|첫|하나|1번").containsMatchIn(t) -> f.left
            Regex("오른|두번|둘|2번").containsMatchIn(t) -> f.right
            words(f.left.label).any { it in t } -> f.left
            words(f.right.label).any { it in t } -> f.right
            else -> null
        }
    }

    private fun words(label: String) = when (label) {
        Tag.SEA.word -> listOf("바다", "해변", "해안")
        Tag.LAKE.word -> listOf("호수")
        Tag.MOUNTAIN.word -> listOf("산", "고개", "고갯")
        else -> listOf("시내", "도시")
    }
}
