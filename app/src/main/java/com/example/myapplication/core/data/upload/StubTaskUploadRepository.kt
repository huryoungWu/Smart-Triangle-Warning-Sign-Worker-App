package com.example.myapplication.core.data.upload

import com.example.myapplication.core.data.api.TaskUploadRepository
import com.example.myapplication.core.data.api.UploadResult
import com.example.myapplication.core.model.vehicle.WorkRecord
import kotlinx.coroutines.delay
import java.io.IOException

/** 占位实现（无服务器时演示）：模拟应答。 */
class StubTaskUploadRepository : TaskUploadRepository {

    /** 演示用：置 true 则下一次上传返回失败 */
    var failNext = false

    override suspend fun upload(record: WorkRecord, deviceCode: String): Result<UploadResult> {
        delay(300)
        if (failNext) {
            failNext = false
            return Result.failure(IOException("演示占位：模拟上传失败"))
        }
        return Result.success(
            UploadResult("stub-${record.id.take(8)}", System.currentTimeMillis()),
        )
    }
}
