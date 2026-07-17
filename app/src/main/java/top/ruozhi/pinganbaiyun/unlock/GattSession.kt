package top.ruozhi.pinganbaiyun.unlock

import top.ruozhi.pinganbaiyun.model.DoorConfig
import top.ruozhi.pinganbaiyun.protocol.SafeBaiyunProtocol

enum class GattFeature { READ, WRITE, NOTIFY, INDICATE }

data class GattChannel(
    val id: String,
    val features: Set<GattFeature>,
    val hasClientConfiguration: Boolean,
)

interface GattClient {
    interface Callback {
        fun onConnected(success: Boolean)
        fun onServicesDiscovered(success: Boolean, serviceFound: Boolean, channels: List<GattChannel>)
        fun onDescriptorWritten(success: Boolean)
        fun onSeedRead(success: Boolean, seed: ByteArray)
        fun onCommandWritten(success: Boolean)
    }

    fun connect(mac: String, callback: Callback): Boolean
    fun discoverServices(): Boolean
    fun enableNotifications(channel: GattChannel): Boolean
    fun writeClientConfiguration(channel: GattChannel, indicate: Boolean): Boolean
    fun read(channel: GattChannel): Boolean
    fun write(channel: GattChannel, value: ByteArray): Boolean
    fun close()
}

/** Pure, deterministic GATT operation queue. Android API calls live in [AndroidGattClient]. */
class GattSession(
    private val client: GattClient,
    private val frameBuilder: (String, String, ByteArray) -> Result<ByteArray> = SafeBaiyunProtocol::buildUnlockFrame,
) : UnlockTransport, GattClient.Callback {
    private var listener: UnlockTransport.Listener? = null
    private var door: DoorConfig? = null
    private var ended = false
    private var writeChannel: GattChannel? = null
    private var readChannel: GattChannel? = null
    private val subscriptions = ArrayDeque<Pair<GattChannel, Boolean>>()

    override fun start(door: DoorConfig, listener: UnlockTransport.Listener) {
        check(this.listener == null) { "GattSession cannot be reused" }
        this.door = door.copy()
        this.listener = listener
        listener.onStage(UnlockStage.CONNECTING)
        if (!client.connect(door.mac, this)) fail("无法发起蓝牙连接")
    }

    override fun cancel() = finish { }

    override fun onConnected(success: Boolean) {
        if (ended) return
        if (!success) return fail("门禁设备连接失败")
        listener?.onStage(UnlockStage.DISCOVERING_SERVICES)
        if (!client.discoverServices()) fail("无法发起服务发现")
    }

    override fun onServicesDiscovered(success: Boolean, serviceFound: Boolean, channels: List<GattChannel>) {
        if (ended) return
        if (!success) return fail("服务发现失败")
        if (!serviceFound) return fail("门禁协议服务不兼容")
        val sorted = channels.sortedBy { it.id }
        readChannel = sorted.firstOrNull { GattFeature.READ in it.features }
        writeChannel = sorted.firstOrNull { GattFeature.WRITE in it.features }
        val notify = sorted.firstOrNull { GattFeature.NOTIFY in it.features }
        val indicate = sorted.firstOrNull { GattFeature.INDICATE in it.features }
        if (readChannel == null || writeChannel == null || notify == null || indicate == null) {
            return fail("门禁协议特征不完整")
        }
        val selected = listOf(notify to false, indicate to true).distinctBy { it.first.id }
        if (selected.any { !it.first.hasClientConfiguration }) return fail("门禁通知描述符缺失")
        subscriptions.addAll(selected)
        listener?.onStage(UnlockStage.PREPARING_CHANNEL)
        configureNextOrRead()
    }

    override fun onDescriptorWritten(success: Boolean) {
        if (ended) return
        if (!success) return fail("通信通道初始化失败")
        configureNextOrRead()
    }

    override fun onSeedRead(success: Boolean, seed: ByteArray) {
        if (ended) return
        if (!success) return fail("读取设备随机数失败")
        val snapshot = door ?: return fail("门禁任务已失效")
        val frame = frameBuilder(snapshot.mac, snapshot.key, seed)
            .getOrElse { return fail("设备协议数据不受支持") }
        val channel = writeChannel ?: return fail("写入特征不可用")
        listener?.onStage(UnlockStage.SENDING_COMMAND)
        if (!client.write(channel, frame)) fail("无法发起开门指令写入")
    }

    override fun onCommandWritten(success: Boolean) {
        if (ended) return
        if (!success) return fail("开门指令写入失败")
        finish { listener?.onSent() }
    }

    private fun configureNextOrRead() {
        val next = subscriptions.removeFirstOrNull()
        if (next != null) {
            if (!client.enableNotifications(next.first)) return fail("无法启用设备通知")
            if (!client.writeClientConfiguration(next.first, next.second)) fail("无法初始化通信通道")
            return
        }
        val channel = readChannel ?: return fail("读取特征不可用")
        listener?.onStage(UnlockStage.READING_SEED)
        if (!client.read(channel)) fail("无法发起随机数读取")
    }

    private fun fail(message: String) = finish { listener?.onFailure(message) }

    private fun finish(notify: () -> Unit) {
        if (ended) return
        ended = true
        subscriptions.clear()
        client.close()
        notify()
    }
}
