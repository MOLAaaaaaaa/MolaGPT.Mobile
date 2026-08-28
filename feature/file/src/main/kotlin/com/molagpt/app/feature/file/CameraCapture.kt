package com.molagpt.app.feature.file

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 拍照附件的临时落点。
 *
 * `ActivityResultContracts.TakePicture` 不会替你建文件——它把相机 app 拍到的图写进你给的
 * `content://`，只回一个 Boolean。所以这里先在 cache 目录建空文件，再用 [FileProvider]
 * 换出一个可写 URI 交给相机。
 *
 * 用 cacheDir 而不是 filesDir：这份文件是中转的，选中后 [AttachmentStore]（BYOK）或
 * 上传（MolaGPT 账户）会各自复制走，之后就没用了。系统在存储紧张时能直接回收 cache。
 *
 * 不需要 `CAMERA` 权限——拍照是委托给系统相机 app，权限归它。反过来说**也不能声明**：
 * 一旦 manifest 里出现 `android.permission.CAMERA`，系统就会强制要求先运行时授权
 * 才允许启动 `ACTION_IMAGE_CAPTURE`，白白多一个弹窗。
 */
object CameraCapture {

    /** 相对 cacheDir 的子目录，需与 `res/xml/file_paths.xml` 里的 cache-path 一致。 */
    const val DIR = "camera"

    /** FileProvider authority 后缀，需与 app manifest 里的 `${applicationId}.fileprovider` 一致。 */
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    /** 超过这个时长的中转文件视为残留，下次拍照时顺手清掉。 */
    private const val STALE_AFTER_MS = 24L * 60 * 60 * 1000

    /**
     * 建一个新的拍照落点并返回可交给相机的 URI；建不出来（无存储空间等）返回 null。
     *
     * 用户在相机里按取消时 `TakePicture` 回 false，这个空文件就留在原地了——没有回调能
     * 可靠地告诉我们"这次白拍了"，所以不做即时删除，靠 [sweepStale] 下次进来兜底。
     */
    fun newPhotoUri(context: Context): Uri? = runCatching {
        val appContext = context.applicationContext
        val dir = File(appContext.cacheDir, DIR).apply { if (!exists()) mkdirs() }
        sweepStale(dir)
        val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        file.createNewFile()
        FileProvider.getUriForFile(appContext, appContext.packageName + AUTHORITY_SUFFIX, file)
    }.getOrNull()

    private fun sweepStale(dir: File) {
        val deadline = System.currentTimeMillis() - STALE_AFTER_MS
        dir.listFiles().orEmpty().forEach { file ->
            if (file.lastModified() < deadline) file.delete()
        }
    }
}
