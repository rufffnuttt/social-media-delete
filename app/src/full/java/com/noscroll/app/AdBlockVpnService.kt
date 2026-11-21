package com.noscroll.app

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A local, on-device "VPN" that only carries DNS lookups.
 *
 * Android is told to use a fake DNS server (10.111.222.53) that only exists inside
 * this VPN. Every lookup arrives here: ad / tracker (and optionally adult) domains get
 * a "does not exist" answer, everything else is forwarded to a real DNS server.
 * No other traffic goes through the VPN, so browsing speed is unaffected.
 */
class AdBlockVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var pool: ExecutorService? = null
    private lateinit var prefs: Prefs

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        prefs = Prefs(this)
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (running) return
        Blocklist.load(this)
        val builder = Builder()
            .setSession("NoScroll ad blocker")
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(DNS_ADDRESS)
            .addRoute(DNS_ADDRESS, 32)
            .setBlocking(true)
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {}
        val iface = try { builder.establish() } catch (e: Exception) { null } ?: run {
            stopSelf()
            return
        }
        tun = iface
        running = true
        pool = Executors.newFixedThreadPool(8)
        reader = Thread({ readLoop(iface) }, "noscroll-dns").also { it.start() }
    }

    private fun readLoop(iface: ParcelFileDescriptor) {
        val input = FileInputStream(iface.fileDescriptor)
        val output = FileOutputStream(iface.fileDescriptor)
        val buf = ByteArray(32_767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n <= 0) continue
                val packet = buf.copyOf(n)
                pool?.execute { handlePacket(packet, output) }
            }
        } catch (e: IOException) {
            // Interface closed – VPN stopped.
        } catch (e: Exception) {
        }
    }

    private fun handlePacket(pkt: ByteArray, output: FileOutputStream) {
        try {
            if (pkt.size < 28) return
            val version = (pkt[0].toInt() shr 4) and 0xF
            if (version != 4) return
            val ihl = (pkt[0].toInt() and 0xF) * 4
            if (pkt[9].toInt() != 17) return                // UDP only
            val srcIp = pkt.copyOfRange(12, 16)
            val dstIp = pkt.copyOfRange(16, 20)
            val srcPort = u16(pkt, ihl)
            val dstPort = u16(pkt, ihl + 2)
            if (dstPort != 53) return
            val udpLen = u16(pkt, ihl + 4)
            val end = minOf(pkt.size, ihl + udpLen)
            if (end <= ihl + 8 + 12) return
            val query = pkt.copyOfRange(ihl + 8, end)

            val name = Dns.questionName(query)
            val response = if (name != null && Blocklist.isBlocked(name, blockAdultDns())) {
                blockedCount.incrementAndGet()
                Dns.nxdomain(query)
            } else {
                forward(query)
            } ?: return

            val reply = buildUdpPacket(dstIp, srcIp, 53, srcPort, response)
            synchronized(output) { output.write(reply) }
        } catch (e: Exception) {
        }
    }

    private fun blockAdultDns() = prefs.blockAdult

    private fun forward(query: ByteArray): ByteArray? {
        val upstream = if (prefs.adBlockUpstream == "adult") UPSTREAM_ADULT else UPSTREAM_PLAIN
        for (server in upstream) {
            try {
                DatagramSocket().use { socket ->
                    protect(socket)
                    socket.soTimeout = 4000
                    val addr = InetAddress.getByName(server)
                    socket.send(DatagramPacket(query, query.size, addr, 53))
                    val buf = ByteArray(4096)
                    val resp = DatagramPacket(buf, buf.size)
                    socket.receive(resp)
                    return buf.copyOf(resp.length)
                }
            } catch (e: Exception) {
                // Try the next server.
            }
        }
        return null
    }

    private fun buildUdpPacket(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[6] = 0x40                                         // don't fragment
        p[8] = 64                                           // TTL
        p[9] = 17                                           // UDP
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        val sum = checksum(p, 0, 20)
        p[10] = (sum shr 8).toByte(); p[11] = sum.toByte()
        p[20] = (srcPort shr 8).toByte(); p[21] = srcPort.toByte()
        p[22] = (dstPort shr 8).toByte(); p[23] = dstPort.toByte()
        val udpLen = 8 + payload.size
        p[24] = (udpLen shr 8).toByte(); p[25] = udpLen.toByte()
        // UDP checksum 0 = "not computed", which is allowed for IPv4.
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun u16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun stopVpn() {
        running = false
        try { tun?.close() } catch (e: Exception) {}
        tun = null
        pool?.shutdownNow()
        pool = null
        reader = null
    }

    override fun onRevoke() {
        // Another VPN took over, or the user turned it off in system settings.
        Prefs(this).adBlockEnabled = false
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.noscroll.app.ADBLOCK_START"
        const val ACTION_STOP = "com.noscroll.app.ADBLOCK_STOP"
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val DNS_ADDRESS = "10.111.222.53"
        // CleanBrowsing adult filter: blocks adult sites and enforces SafeSearch.
        private val UPSTREAM_ADULT = listOf("185.228.168.10", "185.228.169.11")
        private val UPSTREAM_PLAIN = listOf("1.1.1.1", "8.8.8.8")

        @Volatile var running = false
            private set
        val blockedCount = AtomicInteger(0)

        fun start(context: Context) {
            context.startService(Intent(context, AdBlockVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AdBlockVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** Just enough DNS parsing to read the question and build a "blocked" answer. */
object Dns {
    /** The domain being asked about, e.g. "ads.example.com". */
    fun questionName(q: ByteArray): String? {
        if (q.size < 13) return null
        val qdCount = ((q[4].toInt() and 0xFF) shl 8) or (q[5].toInt() and 0xFF)
        if (qdCount < 1) return null
        val sb = StringBuilder()
        var i = 12
        while (i < q.size) {
            val len = q[i].toInt() and 0xFF
            if (len == 0) return sb.toString().lowercase()
            if (len and 0xC0 != 0) return null              // compression – not used in questions
            if (i + 1 + len > q.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(q, i + 1, len, Charsets.US_ASCII))
            i += 1 + len
        }
        return null
    }

    /** A copy of the question marked as a reply with "no such domain". */
    fun nxdomain(q: ByteArray): ByteArray? {
        var i = 12
        while (i < q.size && q[i].toInt() != 0) i += 1 + (q[i].toInt() and 0xFF)
        val questionEnd = i + 1 + 4                           // zero byte + type + class
        if (questionEnd > q.size) return null
        val r = q.copyOf(questionEnd)
        r[2] = (0x80 or (q[2].toInt() and 0x79)).toByte()      // QR=1, keep opcode + RD
        r[3] = 0x83.toByte()                                  // RA=1, RCODE=3 (NXDOMAIN)
        r[4] = 0; r[5] = 1                                    // 1 question
        for (k in 6 until 12) r[k] = 0                        // no answers / authority / additional
        return r
    }
}
