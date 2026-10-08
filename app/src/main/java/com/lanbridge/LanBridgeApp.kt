package com.lanbridge

import android.app.Application
import com.lanbridge.server.FileStore
import com.lanbridge.server.SessionState
import com.lanbridge.server.TransferEngine

/** Application 初始化（T01）：全局会话态 + 传输引擎 + Glide 默认配置。
 *  在 Application 初始化 TransferEngine，确保任何 exported 组件（如 ShareReceiverActivity）
 *  被外部调起时 fileStore/hub 已就绪，避免 UninitializedPropertyAccessException 崩溃。 */
class LanBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionState.init(this)
        TransferEngine.init(FileStore(FileStore.dirOf(this)))
    }
}
