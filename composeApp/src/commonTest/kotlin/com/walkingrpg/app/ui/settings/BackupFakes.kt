package com.walkingrpg.app.ui.settings

import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.domain.backup.BackupCodec
import com.walkingrpg.shared.domain.backup.BackupData
import com.walkingrpg.shared.domain.backup.BackupFormatException
import com.walkingrpg.shared.domain.backup.BackupStore
import com.walkingrpg.shared.domain.growth.WayGrowth
import com.walkingrpg.shared.domain.growth.WayGrowthRepository
import com.walkingrpg.shared.platform.BackupArchive
import com.walkingrpg.shared.platform.BackupEntry
import com.walkingrpg.shared.platform.FilePicker
import com.walkingrpg.shared.platform.FileShare

/**
 * バックアップまわり（issue #18）のテスト用差し替え。
 *
 * zipの中身とDBの往復は shared 側（`BackupRoundTripTest` / `BackupArchiveCodecTest` /
 * `AndroidBackupArchiveTest`）で見ているので、ここでは**画面の振る舞い**だけを
 * 見られる最小の実装にする（`ReviewFakes` / `SetupFakes` と同じ方針）。
 */

/** 何も入っていないバックアップ（件数だけが画面に出るので中身は要らない）。 */
internal val EMPTY_BACKUP_DATA = BackupData(
    sessions = emptyList(),
    samples = emptyList(),
    passages = emptyList(),
    stepImports = emptyList(),
    sessionWeathers = emptyList(),
    utterances = emptyList(),
    llmCacheEntries = emptyList(),
    snapshots = emptyList(),
    snapshotImages = emptyList(),
)

/** 読み書きが起きたことだけを覚えるストア。 */
internal class FakeBackupStore(
    private val data: BackupData = EMPTY_BACKUP_DATA,
    private val failOnRead: Throwable? = null,
) : BackupStore {

    /** 置き換えが走った回数（「何も書かずに失敗する」の検証に使う）。 */
    var replaceCount = 0
        private set

    override suspend fun read(): BackupData {
        failOnRead?.let { throw it }
        return data
    }

    override suspend fun replaceAll(data: BackupData) {
        replaceCount++
    }
}

/** エントリ列は素通し。zipの構造はここでは見ない。 */
internal class FakeBackupArchive : BackupArchive {
    override fun write(entries: List<BackupEntry>): ByteArray = ByteArray(entries.size)
    override fun read(zipBytes: ByteArray): List<BackupEntry> = emptyList()
}

/**
 * [decodeFailure] を渡すと「壊れたzipを選んだ」を再現する
 * （＝`ImportBackupUseCase` が書き込みに進まないことを見られる）。
 */
internal class FakeBackupCodec(
    private val data: BackupData = EMPTY_BACKUP_DATA,
    private val decodeFailure: BackupFormatException? = null,
) : BackupCodec {

    override fun encode(data: BackupData, exportedAtMs: Long): List<BackupEntry> =
        listOf(BackupEntry("manifest.json", ByteArray(1)))

    override fun decode(entries: List<BackupEntry>): BackupData {
        decodeFailure?.let { throw it }
        return data
    }
}

/** [bytes] が `null` なら「選ばずに閉じた」。 */
internal class FakeFilePicker(
    private val bytes: ByteArray? = ByteArray(1),
) : FilePicker {
    var pickCount = 0
        private set

    override suspend fun pickBytes(mimeType: String): ByteArray? {
        pickCount++
        return bytes
    }
}

/** 共有UIに渡ったファイル名を覚える。 */
internal class RecordingFileShare(
    private val failWith: Throwable? = null,
) : FileShare {

    var sharedFileName: String? = null
        private set

    override suspend fun shareText(fileName: String, mimeType: String, content: String) {
        failWith?.let { throw it }
        sharedFileName = fileName
    }

    override suspend fun shareBytes(fileName: String, mimeType: String, bytes: ByteArray) {
        failWith?.let { throw it }
        sharedFileName = fileName
    }
}

/** 導出の作り直しが走った回数だけを見る。 */
internal class CountingWayGrowthRepository : WayGrowthRepository {
    var replaceCount = 0
        private set

    override suspend fun replaceAllGrowths(growths: List<WayGrowth>) {
        replaceCount++
    }

    override suspend fun growths(): List<WayGrowth> = emptyList()
    override suspend fun growth(wayId: Long): WayGrowth? = null
}

/** 固定時刻（エクスポートのファイル名に入る）。 */
internal class FixedClock(private val nowMs: Long = 1_767_225_600_000L) : Clock {
    override fun nowMillis(): Long = nowMs
}
