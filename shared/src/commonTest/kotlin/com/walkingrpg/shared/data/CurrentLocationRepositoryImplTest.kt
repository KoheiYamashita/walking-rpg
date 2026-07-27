package com.walkingrpg.shared.data

import com.walkingrpg.shared.domain.walk.LocationFix
import com.walkingrpg.shared.domain.walk.LocationPermissionRepository
import com.walkingrpg.shared.domain.walk.LocationPermissionStatus
import com.walkingrpg.shared.domain.walk.LocationUnavailableException
import com.walkingrpg.shared.platform.LocationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurrentLocationRepositoryImplTest {

    private val fix = LocationFix(
        timestampMs = 1_000L,
        latitude = 35.5,
        longitude = 139.5,
        accuracyMeters = 8.0,
    )

    private class FakePermissionRepository(
        status: LocationPermissionStatus,
    ) : LocationPermissionRepository {

        override val status: StateFlow<LocationPermissionStatus> = MutableStateFlow(status)

        var refreshed = 0
            private set

        override fun refresh() {
            refreshed++
        }

        override fun request() = Unit
    }

    private class SingleFixProvider(private val fix: LocationFix) : LocationProvider {
        var subscribed = 0
            private set

        override fun updates(intervalMs: Long): Flow<LocationFix> {
            subscribed++
            return flowOf(fix)
        }
    }

    @Test
    fun `権限があれば最初の1点を返す`() = runTest {
        val permission = FakePermissionRepository(LocationPermissionStatus.GRANTED)

        val actual = CurrentLocationRepositoryImpl(SingleFixProvider(fix), permission).currentFix()

        assertEquals(fix, actual)
        assertTrue(permission.refreshed > 0, "判定前に権限を読み直すこと")
    }

    @Test
    fun `権限がなければ測位せずnullを返す`() = runTest {
        val provider = SingleFixProvider(fix)

        val actual = CurrentLocationRepositoryImpl(
            provider,
            FakePermissionRepository(LocationPermissionStatus.DENIED),
        ).currentFix()

        assertNull(actual)
        assertEquals(0, provider.subscribed, "権限がないなら測位を開始しないこと")
    }

    @Test
    fun `測位が失敗してもnullを返すだけで落ちない`() = runTest {
        val provider = FakeLocationProvider(LocationUnavailableException("測位できません"))

        val actual = CurrentLocationRepositoryImpl(
            provider,
            FakePermissionRepository(LocationPermissionStatus.GRANTED),
        ).currentFix()

        assertNull(actual)
    }
}
