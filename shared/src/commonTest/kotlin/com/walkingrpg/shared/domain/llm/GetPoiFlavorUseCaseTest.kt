package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.osm.PoiKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 散歩中の読み出し（[GetPoiFlavorUseCase]）。
 *
 * 見たいのは1つ：**必ず文章が出る**。生成できていなくても定型文で成立するので、
 * 散歩は通信ゼロでも壊れない（design.md §7・architecture.md §1）。
 */
class GetPoiFlavorUseCaseTest {

    private val cache = FakeLlmCacheRepository()
    private val useCase = GetPoiFlavorUseCase(cache)

    private suspend fun putFlavor(poiId: String, text: String, promptHash: String) {
        cache.save(
            LlmCacheEntry(
                cacheKey = llmCacheKey(LlmTaskKind.POI_FLAVOR, poiFlavorLogicalKey(poiId)),
                kind = LlmTaskKind.POI_FLAVOR,
                promptHash = promptHash,
                text = text,
                createdAtMs = 1L,
            ),
        )
    }

    private fun promptHashOf(kind: PoiKind, name: String?) = PoiFlavorPromptBuilder
        .request(
            facts = PoiFlavorFacts(kind = kind, name = name),
            maxTokens = LlmGenerationConfig.DEFAULT.poiFlavorMaxTokens,
        )
        .promptHash()

    @Test
    fun 生成済みならその文章を返す() = runTest {
        val poi = testPoi("node/1", PoiKind.PARK, name = "テスト公園")
        putFlavor("node/1", "木陰が涼しい。", promptHashOf(PoiKind.PARK, "テスト公園"))

        val flavor = useCase(poi)

        assertEquals("木陰が涼しい。", flavor.text)
        assertTrue(flavor.isGenerated)
    }

    @Test
    fun 未生成なら種別ごとの定型文を返す() = runTest {
        val poi = testPoi("node/2", PoiKind.WATER)

        val flavor = useCase(poi)

        assertEquals(PoiFlavorFallback.text(PoiKind.WATER), flavor.text)
        assertFalse(flavor.isGenerated)
    }

    @Test
    fun プロンプトが変わっていれば定型文に落とす() = runTest {
        // 古い方針で書かれた文章を出し続けるより定型文のほうが安全
        // （次のドレインが作り直す）
        val poi = testPoi("node/3", PoiKind.SHRINE)
        putFlavor("node/3", "古い方針の一言。", promptHash = "古いプロンプトの指紋")

        val flavor = useCase(poi)

        assertEquals(PoiFlavorFallback.text(PoiKind.SHRINE), flavor.text)
        assertFalse(flavor.isGenerated)
    }

    @Test
    fun 別の地点の文章を混ぜない() = runTest {
        putFlavor("node/1", "1の一言。", promptHashOf(PoiKind.PARK, null))

        val flavor = useCase(testPoi("node/2", PoiKind.PARK))

        assertFalse(flavor.isGenerated, "キーは地点ごとに分かれている")
    }
}
