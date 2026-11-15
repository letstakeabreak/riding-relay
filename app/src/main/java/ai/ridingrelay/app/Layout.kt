package ai.ridingrelay.app

/** 창 배치. 가로로 긴 차량 화면(아이오닉 5처럼 세로가 낮은 화면)에서는 같은 내용을 낮은 높이에 맞춰 놓는다. */
object Layout {
    const val WIDE_MIN_WIDTH_DP = 1000
    const val SHORT_MAX_HEIGHT_DP = 900

    /** 두 단 배치를 쓸 만큼 넓은 창인지. */
    fun isWide(widthDp: Int) = widthDp >= WIDE_MIN_WIDTH_DP

    /** 넓지만 세로가 낮아 가로형 배치가 필요한 창인지. 좁은 창은 스크롤하므로 해당하지 않는다. */
    fun isShort(widthDp: Int, heightDp: Int) = isWide(widthDp) && heightDp < SHORT_MAX_HEIGHT_DP
}
